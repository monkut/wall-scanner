package io.github.monkut.wallscanner.io

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.monkut.wallscanner.CrashLog
import io.github.monkut.wallscanner.core.PlyWriter
import io.github.monkut.wallscanner.core.VoxelCloud
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
 * points.ply (once scanning produced points) and scene.json (once processing succeeded).
 */
object CaptureStore {
    const val SCENE_FILE = "scene.json"
    const val PLY_FILE = "points.ply"
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

    fun writeScene(dir: File, json: String) = File(dir, SCENE_FILE).writeText(json)

    fun readScene(dir: File): String = File(dir, SCENE_FILE).readText()

    fun delete(dir: File) = dir.deleteRecursively()

    /** Zips the capture (plus any crash traces) and opens the system share sheet. */
    fun share(activity: Activity, dir: File) {
        val shareDir = File(activity.cacheDir, "share").apply { mkdirs() }
        val zip = File(shareDir, "${dir.name}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            val entries = (dir.listFiles()?.filter { it.isFile }?.map { "${dir.name}/${it.name}" to it } ?: emptyList()) +
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
