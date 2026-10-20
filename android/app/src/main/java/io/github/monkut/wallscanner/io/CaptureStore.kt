package io.github.monkut.wallscanner.io

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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

/** One capture = one directory under files/captures/<timestamp>/ holding scene.json and points.ply. */
object CaptureStore {
    const val SCENE_FILE = "scene.json"
    const val PLY_FILE = "points.ply"
    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DIR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun capturesDir(context: Context): File = File(context.filesDir, "captures").apply { mkdirs() }

    fun list(context: Context): List<File> =
        capturesDir(context).listFiles { f -> f.isDirectory && File(f, SCENE_FILE).exists() }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun create(context: Context): File {
        val base = ZonedDateTime.now(JST).format(DIR_FORMAT)
        var dir = File(capturesDir(context), base)
        var suffix = 1
        while (dir.exists()) dir = File(capturesDir(context), "$base-${suffix++}")
        dir.mkdirs()
        return dir
    }

    fun nowIso(): String = ZonedDateTime.now(JST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun writePly(dir: File, cloud: VoxelCloud) =
        BufferedOutputStream(FileOutputStream(File(dir, PLY_FILE))).use { PlyWriter.write(cloud, it) }

    fun writeScene(dir: File, json: String) = File(dir, SCENE_FILE).writeText(json)

    fun readScene(dir: File): String = File(dir, SCENE_FILE).readText()

    fun delete(dir: File) = dir.deleteRecursively()

    /** Zips the capture and opens the system share sheet. */
    fun share(activity: Activity, dir: File) {
        val shareDir = File(activity.cacheDir, "share").apply { mkdirs() }
        val zip = File(shareDir, "${dir.name}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                out.putNextEntry(ZipEntry("${dir.name}/${file.name}"))
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
