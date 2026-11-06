package io.github.monkut.wallscanner.io

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import io.github.monkut.wallscanner.core.ColorPatches
import io.github.monkut.wallscanner.core.Coverage
import io.github.monkut.wallscanner.core.Definition
import io.github.monkut.wallscanner.core.TapeMarks
import io.github.monkut.wallscanner.core.HoldMerge
import io.github.monkut.wallscanner.core.HoldSearch
import io.github.monkut.wallscanner.core.HoldShapes
import io.github.monkut.wallscanner.core.HoldSplit
import io.github.monkut.wallscanner.core.Image
import io.github.monkut.wallscanner.core.Keyframe
import io.github.monkut.wallscanner.core.OrthophotoBuilder
import io.github.monkut.wallscanner.core.ProcessingStage
import io.github.monkut.wallscanner.core.ProgressListener
import io.github.monkut.wallscanner.core.ScanQuality
import io.github.monkut.wallscanner.core.SceneBuilder
import io.github.monkut.wallscanner.core.SceneJson
import io.github.monkut.wallscanner.core.SceneResult
import io.github.monkut.wallscanner.core.VoxelCloud
import java.io.File
import kotlin.random.Random

/**
 * The whole scene pipeline for a capture directory: SceneBuilder on the point cloud, then an orthophoto of
 * the wall from the saved keyframes (written as wall.png) and hold outlines refined on it, last by SAM
 * ([HoldSam]) from each hold's box. Used at Finish and by Reprocess; [log] receives one line per stage and
 * [progress] the stage under way with how far it has got ([ProcessingStage.CLOUD] to [ProcessingStage.WRITE]).
 */
object ScenePipeline {
    private const val KEYFRAME_SUBSAMPLE = 2 // 960 × 540 is ~1.8 image px per 5 mm wall px at 2 m: enough, half the decode cost

    fun build(dir: File, cloud: VoxelCloud, cameras: List<FloatArray>, definition: Definition, assets: AssetManager?, log: (String) -> Unit, progress: ProgressListener = { _, _ -> }): SceneResult? =
        stages(dir, cloud, cameras, definition, assets, log, progress)?.also { judge(it, cloud, log) }

    /**
     * The scan's quality verdict from the final holds (#35), logged and set on [result]. Only for a gym scan that
     * went through the photo pipeline: the thresholds were calibrated on its hold counts, and outdoor rock has no
     * single wall plane to share.
     */
    private fun judge(result: SceneResult, cloud: VoxelCloud, log: (String) -> Unit) {
        val why = when {
            result.definition.type.outdoor -> "outdoor rock"
            result.photo == null -> "no orthophoto, hold counts are not comparable"
            else -> null
        }
        if (why != null) { log("QUALITY not judged: $why"); return }
        result.quality = ScanQuality.of(cloud, result.surface, result.holds.size, result.extent)
        log("QUALITY ${result.quality}")
    }

    private fun stages(dir: File, cloud: VoxelCloud, cameras: List<FloatArray>, definition: Definition, assets: AssetManager?, log: (String) -> Unit, progress: ProgressListener): SceneResult? {
        var result = SceneBuilder.build(cloud, cameras, Random(42), definition) { progress(ProcessingStage.CLOUD, it) } ?: return null
        result.thinCoverage = Coverage.thinCells(cloud, result.surface, result.extent)
        if (result.thinCoverage.isNotEmpty()) {
            log("COVERAGE thin depth in ${result.thinCoverage.joinToString { "(row ${it.row}, col ${it.col})" }} of a ${Coverage.ROWS}x${Coverage.COLS} grid (row 0 = top, col 0 = left)")
        }
        val keyframes = CaptureStore.readKeyframes(dir)
        val t0 = System.currentTimeMillis()
        var decoded = 0
        progress(ProcessingStage.ORTHOPHOTO, 0f)
        val photo = runCatching { OrthophotoBuilder.build(cloud, result.surface, result.extent, keyframes, decode = { progress(ProcessingStage.ORTHOPHOTO, decoded++.toFloat() / keyframes.size); decode(dir, it) }) }
            .onFailure { log("orthophoto failed: $it") }
            .getOrNull()
        if (photo == null) {
            log("orthophoto: none (keyframes=${keyframes.size}); hold outlines from relief only")
            return result
        }
        log("orthophoto: ${photo.image.width}x${photo.image.height} px at ${OrthophotoBuilder.MM_PER_PX} mm from ${keyframes.size} keyframes (${System.currentTimeMillis() - t0} ms)")
        val t1 = System.currentTimeMillis()
        progress(ProcessingStage.SHAPES, 0f)
        val patches = ColorPatches.forHolds(photo, result.wallColor)
        val refined = runCatching { HoldShapes.refine(result.holds, photo, patches, result.wallColor) }
            .onFailure { log("hold shapes failed: $it") }
            .getOrDefault(result.holds)
        log("hold shapes: ${result.holds.size} holds → ${refined.size} refined on the orthophoto (${System.currentTimeMillis() - t1} ms)")
        val t2 = System.currentTimeMillis()
        val extra = runCatching { HoldSearch.find(refined, patches, photo, result.surface, result.extent) }
            .onFailure { log("hold search failed: $it") }
            .getOrDefault(emptyList())
        log("hold search: ${extra.size} image-only holds added from the orthophoto (${System.currentTimeMillis() - t2} ms)")
        val t3 = System.currentTimeMillis()
        progress(ProcessingStage.MODEL, 0f)
        val model = assets?.let { HoldModel.get(it) }
        val found = refined + extra
        val seen = if (model == null) HoldModel.Found(emptyList(), emptyList()) else runCatching { model.find(photo, result.surface, result.extent, (found.maxOfOrNull { it.id } ?: 0) + 1) }
            .onFailure { log("hold model failed: $it") }
            .getOrDefault(HoldModel.Found(emptyList(), emptyList()))
        val masks = seen.holds
        val merged = runCatching { HoldMerge.withModel(found, masks, photo, result.surface) }
            .onFailure { log("hold merge failed: $it") }
            .getOrDefault(HoldMerge.Merged(found, 0, 0))
        log(if (model != null) "hold model: ${masks.size} holds, ${seen.tape.size} tape strips; ${merged.split} merged holds split by them, ${merged.added} added where no other hold was (${System.currentTimeMillis() - t3} ms)" else "hold model: ${if (assets == null) "no assets" else HoldModel.loadError}")
        val split = runCatching { HoldSplit.byColor(merged.holds, photo, patches, result.surface, result.wallColor) }
            .onFailure { log("hold split failed: $it") }
            .getOrDefault(merged.holds)
        if (split.size != merged.holds.size) log("hold split: ${merged.holds.size} holds → ${split.size} by photo colour")
        val pruned = runCatching { HoldMerge.prune(split, masks, seen.tape, photo, patches, result.wallColor, result.extent) }
            .onFailure { log("hold prune failed: $it") }
            .getOrDefault(split)
        log("hold prune: ${split.size - pruned.size} dropped: tape, or at the edge or made of bare wall (neither coloured nor seen by the model)")
        val t4 = System.currentTimeMillis()
        progress(ProcessingStage.OUTLINES, 0f)
        val sam = assets?.let { HoldSam.get(it) }
        val refinedBySam = if (sam == null) null else runCatching { sam.refine(pruned, photo, result.surface, masks) { progress(ProcessingStage.OUTLINES, it) } }
            .onFailure { log("hold outlines failed: $it") }
            .getOrNull()
        log(if (refinedBySam != null) "hold outlines: ${refinedBySam.redrawn} of ${pruned.size} redrawn by SAM, ${refinedBySam.merged} pieces merged into the hold around them, ${refinedBySam.added} model holds added where the outlines no longer cover them, ${refinedBySam.dropped} without contrast to the wall dropped (${System.currentTimeMillis() - t4} ms, encoder ${refinedBySam.encodeMs} ms)" else "hold outlines: SAM ${if (assets == null) "no assets" else HoldSam.loadError ?: "failed"}")
        val outlined = refinedBySam?.holds ?: pruned
        val joined = runCatching { HoldMerge.byDepth(outlined, photo, result.surface) }
            .onFailure { log("hold depth merge failed: $it") }
            .getOrDefault(outlined)
        log("hold depth merge: ${outlined.size - joined.size} pieces joined to a touching hold with no depth valley between them")
        val holds = HoldMerge.dropStrips(joined)
        if (holds.size != joined.size) log("hold strips: ${joined.size - holds.size} dropped (under 3 cm across, 6× as long: a column or panel edge)")
        val starts = runCatching { TapeMarks.markStarts(holds, photo, result.wallColor, patches, result.extent) }
            .onFailure { log("tape marks failed: $it") }
            .getOrDefault(0)
        log("tape marks: $starts start holds marked from tape strips")
        result = SceneBuilder.withHolds(result, holds)
        progress(ProcessingStage.WRITE, 0f)
        CaptureStore.writeImage(dir, SceneJson.PHOTO_FILE, photo.image)
        CaptureStore.writeImage(dir, SceneJson.RELIEF_FILE, SceneJson.reliefImage(photo))
        result.photo = photo
        return result
    }

    private fun decode(dir: File, keyframe: Keyframe): Image? {
        val options = BitmapFactory.Options().apply { inSampleSize = KEYFRAME_SUBSAMPLE }
        val bitmap = BitmapFactory.decodeFile(File(dir, keyframe.file).path, options) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = Image(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        return image
    }
}
