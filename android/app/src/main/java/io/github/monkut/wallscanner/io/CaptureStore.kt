package io.github.monkut.wallscanner.io

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.content.FileProvider
import io.github.monkut.wallscanner.CrashLog
import io.github.monkut.wallscanner.core.Definition
import io.github.monkut.wallscanner.core.FeetRule
import io.github.monkut.wallscanner.core.Image
import io.github.monkut.wallscanner.core.Keyframe
import io.github.monkut.wallscanner.core.Plane
import io.github.monkut.wallscanner.core.Rigid
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.core.Rules
import io.github.monkut.wallscanner.core.PlyReader
import io.github.monkut.wallscanner.core.ProcessingStage
import io.github.monkut.wallscanner.core.ProgressListener
import io.github.monkut.wallscanner.core.PlyWriter
import io.github.monkut.wallscanner.core.SceneJson
import io.github.monkut.wallscanner.core.Thumbnail
import io.github.monkut.wallscanner.core.VoxelCloud
import io.github.monkut.wallscanner.core.WallFrame
import io.github.monkut.wallscanner.core.WallSurface
import io.github.monkut.wallscanner.core.SceneBuilder
import org.json.JSONObject
import kotlin.math.max
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One capture = one directory under files/captures/<JST timestamp>/ holding scan.log (always),
 * segments/segment-NN.ply and points.ply (once scanning produced points) and scene.json (once processing succeeded).
 */
object CaptureStore {
    const val SCENE_FILE = "scene.json"
    const val PLY_FILE = "points.ply"
    const val SEGMENTS_DIR = "segments"
    const val THUMB_FILE = "thumbnail.png"
    const val META_FILE = "capture.json"
    const val FRAMES_DIR = "frames"
    const val FRAMES_INDEX = "frames.jsonl"
    const val FRAMES_RESOLVED = "frames.json"
    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DIR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun capturesDir(context: Context): File = File(context.filesDir, "captures").apply { mkdirs() }

    fun timestamp(): String = ZonedDateTime.now(JST).format(DIR_FORMAT)

    fun nowIso(): String = ZonedDateTime.now(JST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Captures with a scene, plus incomplete ones that still hold diagnostics. Newest first. */
    fun list(context: Context): List<File> =
        capturesDir(context).listFiles { f -> f.isDirectory && (hasScene(f) || File(f, ScanLog.FILE).exists()) }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun hasScene(dir: File): Boolean = File(dir, SCENE_FILE).exists()

    fun create(context: Context): File {
        val base = timestamp()
        var dir = File(capturesDir(context), base)
        var suffix = 1
        while (dir.exists()) dir = File(capturesDir(context), "$base-${suffix++}")
        dir.mkdirs()
        return dir
    }

    fun writePly(dir: File, cloud: VoxelCloud) =
        BufferedOutputStream(FileOutputStream(File(dir, PLY_FILE))).use { PlyWriter.write(cloud, it) }

    /** One PLY per tracking segment, in world coordinates as placed by its ARCore anchor (before snapping). */
    fun writeSegmentPly(dir: File, segmentId: Int, cloud: VoxelCloud) {
        val segDir = File(dir, SEGMENTS_DIR).apply { mkdirs() }
        val file = File(segDir, "segment-${"%02d".format(segmentId)}.ply")
        BufferedOutputStream(FileOutputStream(file)).use { PlyWriter.write(cloud, it) }
    }

    fun writeScene(dir: File, json: String) = File(dir, SCENE_FILE).writeText(json)

    /** capture.json: location, wall, set and route definition chosen at New scan, written before scanning so post-processing can use them. */
    fun writeMeta(dir: File, definition: Definition, locationId: String, setId: String = "", wallId: String = "") {
        val json = JSONObject()
            .put("version", 2)
            .put("createdAt", nowIso())
            .put("location", locationId)
            .put("set", setId)
            .put("wall", wallId)
            .put("definition", JSONObject()
                .put("type", definition.type.id)
                .put("rules", JSONObject().put("feet", definition.rules.feet.id).put("volumesOn", definition.rules.volumesOn)))
        File(dir, META_FILE).writeText(json.toString(2))
    }

    /** Where a capture was made: a `locations.json` location id and, at a gym, set and wall ids; "" when unset (older or outdoor captures). */
    class Place(val locationId: String, val setId: String, val wallId: String = "")

    fun readPlace(dir: File): Place = runCatching {
        val json = JSONObject(File(dir, META_FILE).readText())
        Place(json.optString("location"), json.optString("set"), json.optString("wall"))
    }.getOrDefault(Place("", ""))

    fun readLocationId(dir: File): String = readPlace(dir).locationId

    /** Re-assigns an existing capture (older ones have no location) without touching the rest of capture.json. */
    fun setLocation(dir: File, locationId: String, setId: String, wallId: String = "") {
        val file = File(dir, META_FILE)
        val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject().put("version", 2))
        file.writeText(json.put("location", locationId).put("set", setId).put("wall", wallId).toString(2))
    }

    /** Records the scan's position fix in capture.json (`gps`); later, more accurate fixes overwrite it. */
    fun writeGps(dir: File, point: GeoPoint) {
        val file = File(dir, META_FILE)
        val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject().put("version", 2))
        file.writeText(json.put("gps", point.toJson()).toString(2))
    }

    fun readDefinition(dir: File): Definition {
        val file = File(dir, META_FILE)
        if (!file.exists()) return Definition()
        return runCatching {
            val d = JSONObject(file.readText()).getJSONObject("definition")
            val rules = d.optJSONObject("rules")
            Definition(
                RouteDefinition.fromId(d.optString("type")),
                Rules(FeetRule.fromId(rules?.optString("feet")), rules?.optBoolean("volumesOn", true) ?: true),
            )
        }.getOrDefault(Definition())
    }

    fun readScene(dir: File): String = File(dir, SCENE_FILE).readText()

    fun writeThumbnail(dir: File, image: Image) = writeImage(dir, THUMB_FILE, image)

    fun writeImage(dir: File, name: String, image: Image) {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Keyframes with world poses from frames.json (written at Finish); empty before then or without keyframes. */
    fun readKeyframes(dir: File): List<Keyframe> {
        val file = File(dir, FRAMES_RESOLVED)
        if (!file.exists()) return emptyList()
        return runCatching {
            val frames = JSONObject(file.readText()).getJSONArray("frames")
            List(frames.length()) { i ->
                val f = frames.getJSONObject(i)
                val pose = f.getJSONObject("worldFromCamera")
                val r = pose.getJSONArray("r"); val t = pose.getJSONArray("t")
                val k = f.getJSONObject("intrinsics")
                Keyframe(
                    f.getString("file"),
                    Rigid(DoubleArray(9) { r.getDouble(it) }, DoubleArray(3) { t.getDouble(it) }),
                    k.getDouble("fx").toFloat(), k.getDouble("fy").toFloat(), k.getDouble("cx").toFloat(), k.getDouble("cy").toFloat(),
                    k.getInt("width"), k.getInt("height"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun readThumbnail(dir: File): Bitmap? = File(dir, THUMB_FILE).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Renders the thumbnail from points.ply and the wall plane in scene.json for captures made before thumbnails existed. */
    fun ensureThumbnail(dir: File): Boolean {
        if (File(dir, THUMB_FILE).exists() || !hasScene(dir) || !File(dir, PLY_FILE).exists()) return false
        return runCatching {
            val plane = planeOf(JSONObject(readScene(dir)).getJSONObject("wall"))
            val cloud = File(dir, PLY_FILE).inputStream().use { PlyReader.read(it) }
            writeThumbnail(dir, Thumbnail.render(cloud, WallSurface.single(WallFrame(plane))))
        }.isSuccess
    }

    /** The wall plane stored in scene.json's `wall` object. */
    fun planeOf(wall: JSONObject): Plane {
        val o = wall.getJSONArray("origin"); val n = wall.getJSONArray("normal")
        return Plane(
            o.getDouble(0).toFloat(), o.getDouble(1).toFloat(), o.getDouble(2).toFloat(),
            n.getDouble(0).toFloat(), n.getDouble(1).toFloat(), n.getDouble(2).toFloat(),
        )
    }


    fun delete(dir: File) = dir.deleteRecursively()

    /**
     * Runs the pipeline (wall plane → holds → routes) again on the saved point cloud and rewrites scene.json
     * and the thumbnail, so a detector change applies to earlier captures. Route edits and confirmations in
     * scene.json are replaced; sends.json is left alone. Returns a summary for the user, or null when the
     * capture has no point cloud or no wall plane. Call off the main thread. Logged as `REPROCESS` in scan.log.
     */
    fun reprocess(context: Context, dir: File, progress: ProgressListener = { _, _ -> }): String? {
        val ply = File(dir, PLY_FILE)
        if (!ply.exists()) return null
        val log = ScanLog(File(dir, ScanLog.FILE))
        try {
            val t0 = System.currentTimeMillis()
            val cloud = ply.inputStream().use { PlyReader.read(it) }
            val result = ScenePipeline.build(dir, cloud, readCameraPositions(dir), readDefinition(dir), context.assets, { log.log("REPROCESS $it") }, progress)
                ?.let { SceneBuilder.withFirstRouteId(it, nextRouteId(dir)) }
            if (result == null) {
                log.log("REPROCESS: no wall plane (voxels=${cloud.count})")
                return null
            }
            val summary = "${result.holds.size} holds, ${result.routes.size} routes"
            val p = result.frame.plane
            log.log(
                "REPROCESS: plane origin=(${p.ox}, ${p.oy}, ${p.oz}) normal=(${p.nx}, ${p.ny}, ${p.nz}) " +
                    "inliers=${result.planeInliers}/${cloud.count} faces=${SceneJson.facesSummary(result.surface)} wallColor=${result.wallColor ?: "n/a"} holds=${result.holds.size} " +
                    "unassigned=${result.holds.count { it.routeId == -1 }} " +
                    "routes=${result.routes.joinToString { "${it.name}(${it.holdIds.size})" }} (${System.currentTimeMillis() - t0} ms)"
            )
            progress(ProcessingStage.WRITE, 0.5f)
            writeScene(dir, SceneJson.write(result, cloud.count, nowIso(), Build.MODEL))
            writeThumbnail(dir, Thumbnail.render(cloud, result.surface))
            progress(ProcessingStage.WRITE, 1f)
            return summary
        } finally {
            log.close()
        }
    }

    /** First route id the earlier scene never issued (its `nextRouteId`, or one past its highest route id); 1 without a scene. */
    private fun nextRouteId(dir: File): Int {
        if (!hasScene(dir)) return 1
        return runCatching {
            val scene = JSONObject(readScene(dir))
            val routes = scene.getJSONArray("routes")
            val maxId = (0 until routes.length()).maxOfOrNull { routes.getJSONObject(it).getInt("id") } ?: 0
            max(scene.optInt("nextRouteId", 1), maxId + 1)
        }.getOrDefault(1)
    }

    /** World camera positions from frames.json (written at Finish); empty for captures without keyframes. */
    private fun readCameraPositions(dir: File): List<FloatArray> {
        val file = File(dir, FRAMES_RESOLVED)
        if (!file.exists()) return emptyList()
        return runCatching {
            val frames = JSONObject(file.readText()).getJSONArray("frames")
            List(frames.length()) { i ->
                val t = frames.getJSONObject(i).getJSONObject("worldFromCamera").getJSONArray("t")
                FloatArray(3) { t.getDouble(it).toFloat() }
            }
        }.getOrDefault(emptyList())
    }

    /** Zips the capture (plus any crash traces) and opens the system share sheet. */
    fun share(activity: Activity, dir: File) {
        val shareDir = File(activity.cacheDir, "share").apply { mkdirs() }
        val zip = File(shareDir, "${dir.name}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            // Climb videos stay out: one climb can be hundreds of MB (each clip is shared on its own from playback).
            val climbs = File(dir, RouteStore.CLIMBS_DIR)
            val entries = dir.walkTopDown().onEnter { it != climbs }.filter { it.isFile }.map { "${dir.name}/${it.relativeTo(dir).path}" to it }.toList() +
                CrashLog.files(activity).map { "crashes/${it.name}" to it }
            for ((name, file) in entries) {
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", zip)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, dir.name))
    }
}
