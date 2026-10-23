package io.github.monkut.wallscanner.io

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import io.github.monkut.wallscanner.core.ColorPatches
import io.github.monkut.wallscanner.core.Coverage
import io.github.monkut.wallscanner.core.Definition
import io.github.monkut.wallscanner.core.TapeMarks
import io.github.monkut.wallscanner.core.HoldSearch
import io.github.monkut.wallscanner.core.HoldShapes
import io.github.monkut.wallscanner.core.HoldSplit
import io.github.monkut.wallscanner.core.Image
import io.github.monkut.wallscanner.core.Keyframe
import io.github.monkut.wallscanner.core.OrthophotoBuilder
import io.github.monkut.wallscanner.core.SceneBuilder
import io.github.monkut.wallscanner.core.SceneJson
import io.github.monkut.wallscanner.core.SceneResult
import io.github.monkut.wallscanner.core.VoxelCloud
import java.io.File
import kotlin.random.Random

/**
 * The whole scene pipeline for a capture directory: SceneBuilder on the point cloud, then an orthophoto of
 * the wall from the saved keyframes (written as wall.png) and hold outlines refined on it. Used at Finish
 * and by Reprocess; [log] receives one line per stage.
 */
object ScenePipeline {
    private const val KEYFRAME_SUBSAMPLE = 2 // 960 × 540 is ~1.8 image px per 5 mm wall px at 2 m: enough, half the decode cost

    fun build(dir: File, cloud: VoxelCloud, cameras: List<FloatArray>, definition: Definition, assets: AssetManager?, log: (String) -> Unit): SceneResult? {
        var result = SceneBuilder.build(cloud, cameras, Random(42), definition) ?: return null
        result.thinCoverage = Coverage.thinCells(cloud, result.frame, result.extent)
        if (result.thinCoverage.isNotEmpty()) {
            log("COVERAGE thin depth in ${result.thinCoverage.joinToString { "(row ${it.row}, col ${it.col})" }} of a ${Coverage.ROWS}x${Coverage.COLS} grid (row 0 = top, col 0 = left)")
        }
        val keyframes = CaptureStore.readKeyframes(dir)
        val t0 = System.currentTimeMillis()
        val photo = runCatching { OrthophotoBuilder.build(cloud, result.frame, result.extent, keyframes, decode = { decode(dir, it) }) }
            .onFailure { log("orthophoto failed: $it") }
            .getOrNull()
        if (photo == null) {
            log("orthophoto: none (keyframes=${keyframes.size}); hold outlines from relief only")
            return result
        }
        log("orthophoto: ${photo.image.width}x${photo.image.height} px at ${OrthophotoBuilder.MM_PER_PX} mm from ${keyframes.size} keyframes (${System.currentTimeMillis() - t0} ms)")
        val t1 = System.currentTimeMillis()
        val patches = ColorPatches.forHolds(photo, result.wallColor)
        val refined = runCatching { HoldShapes.refine(result.holds, photo, patches, result.wallColor) }
            .onFailure { log("hold shapes failed: $it") }
            .getOrDefault(result.holds)
        log("hold shapes: ${result.holds.size} holds → ${refined.size} refined on the orthophoto (${System.currentTimeMillis() - t1} ms)")
        val t2 = System.currentTimeMillis()
        val extra = runCatching { HoldSearch.find(refined, patches, photo, result.frame, result.extent) }
            .onFailure { log("hold search failed: $it") }
            .getOrDefault(emptyList())
        log("hold search: ${extra.size} image-only holds added from the orthophoto (${System.currentTimeMillis() - t2} ms)")
        val t3 = System.currentTimeMillis()
        val model = assets?.let { HoldModel.get(it) }
        val learned = if (model == null) emptyList() else runCatching { model.find(refined + extra, photo, result.frame) }
            .onFailure { log("hold model failed: $it") }
            .getOrDefault(emptyList())
        log(if (model != null) "hold model: ${learned.size} holds added that no other hold covered (${System.currentTimeMillis() - t3} ms)" else "hold model: ${if (assets == null) "no assets" else HoldModel.loadError}")
        val merged = refined + extra + learned
        val holds = runCatching { HoldSplit.byColor(merged, photo, patches, result.frame, result.wallColor) }
            .onFailure { log("hold split failed: $it") }
            .getOrDefault(merged)
        if (holds.size != merged.size) log("hold split: ${merged.size} holds → ${holds.size} by photo colour")
        val starts = runCatching { TapeMarks.markStarts(holds, photo, result.wallColor, patches, result.extent) }
            .onFailure { log("tape marks failed: $it") }
            .getOrDefault(0)
        log("tape marks: $starts start holds marked from tape strips")
        result = SceneBuilder.withHolds(result, holds)
        CaptureStore.writeImage(dir, SceneJson.PHOTO_FILE, photo.image)
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
