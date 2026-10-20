package io.github.monkut.wallscanner.io

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import io.github.monkut.wallscanner.CrashLog
import io.github.monkut.wallscanner.core.Definition
import io.github.monkut.wallscanner.core.FeetRule
import io.github.monkut.wallscanner.core.Image
import io.github.monkut.wallscanner.core.Plane
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.core.Rules
import io.github.monkut.wallscanner.core.PlyReader
import io.github.monkut.wallscanner.core.PlyWriter
import io.github.monkut.wallscanner.core.Thumbnail
import io.github.monkut.wallscanner.core.VoxelCloud
import io.github.monkut.wallscanner.core.WallFrame
import org.json.JSONObject
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

    /** capture.json: the route definition chosen at New scan, written before scanning so post-processing can use it. */
    fun writeMeta(dir: File, definition: Definition) {
        val json = JSONObject()
            .put("version", 1)
            .put("createdAt", nowIso())
            .put("definition", JSONObject()
                .put("type", definition.type.id)
                .put("rules", JSONObject().put("feet", definition.rules.feet.id).put("volumesOn", definition.rules.volumesOn)))
        File(dir, META_FILE).writeText(json.toString(2))
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

    fun writeThumbnail(dir: File, image: Image) {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        FileOutputStream(File(dir, THUMB_FILE)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun readThumbnail(dir: File): Bitmap? = File(dir, THUMB_FILE).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Renders the thumbnail from points.ply and the wall plane in scene.json for captures made before thumbnails existed. */
    fun ensureThumbnail(dir: File): Boolean {
        if (File(dir, THUMB_FILE).exists() || !hasScene(dir) || !File(dir, PLY_FILE).exists()) return false
        return runCatching {
            val wall = JSONObject(readScene(dir)).getJSONObject("wall")
            val o = wall.getJSONArray("origin"); val n = wall.getJSONArray("normal")
            val plane = Plane(
                o.getDouble(0).toFloat(), o.getDouble(1).toFloat(), o.getDouble(2).toFloat(),
                n.getDouble(0).toFloat(), n.getDouble(1).toFloat(), n.getDouble(2).toFloat(),
            )
            val cloud = File(dir, PLY_FILE).inputStream().use { PlyReader.read(it) }
            writeThumbnail(dir, Thumbnail.render(cloud, WallFrame(plane)))
        }.isSuccess
    }

    fun delete(dir: File) = dir.deleteRecursively()

    /** Zips the capture (plus any crash traces) and opens the system share sheet. */
    fun share(activity: Activity, dir: File) {
        val shareDir = File(activity.cacheDir, "share").apply { mkdirs() }
        val zip = File(shareDir, "${dir.name}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            val entries = dir.walkTopDown().filter { it.isFile }.map { "${dir.name}/${it.relativeTo(dir).path}" to it }.toList() +
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
