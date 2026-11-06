package io.github.monkut.wallscanner.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import io.github.monkut.wallscanner.core.Box
import io.github.monkut.wallscanner.core.FaceMask
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Hides bystanders' faces in a capture's keyframes (`frames/frame-NNNN.jpg`) in place, so the orthophoto, the viewer and
 * the shared zip never show them. Each keyframe is turned upright by its camera pose for the face model, and the
 * faces found are pixelated in the stored JPEG (a mosaic keeps the wall's colours for hold detection, where a
 * solid box could read as a hold). Runs at Finish when the bystander mask is on.
 */
object KeyframeFaces {
    class Result(val frames: Int, val withFaces: Int, val faces: Int, val ms: Long)

    private const val JPEG_QUALITY = 85
    private const val BLOCKS = 6 // mosaic cells across the longer side of a face

    fun mask(dir: File, finder: FaceFinder, progress: (Float) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        val keyframes = CaptureStore.readKeyframes(dir)
        var withFaces = 0; var faces = 0
        for ((i, k) in keyframes.withIndex()) {
            progress(i.toFloat() / keyframes.size)
            val file = File(dir, k.file)
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inMutable = true }) ?: continue
            val rotation = FaceMask.uprightRotation(k.worldFromCamera.r)
            val upright = if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
            val found = finder.detect(upright).map { FaceMask.rotate(it, -rotation) }
            if (upright !== bitmap) upright.recycle()
            if (found.isNotEmpty()) {
                withFaces++; faces += found.size
                for (b in found) pixelate(bitmap, b.expand(FaceMask.MARGIN).clamp())
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
            bitmap.recycle()
        }
        return Result(keyframes.size, withFaces, faces, System.currentTimeMillis() - t0)
    }

    /** Replaces [box] of [bitmap] with a [BLOCKS]-wide mosaic of itself. */
    private fun pixelate(bitmap: Bitmap, box: Box) {
        val l = (box.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val t = (box.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = (box.w * bitmap.width).toInt().coerceIn(1, bitmap.width - l)
        val h = (box.h * bitmap.height).toInt().coerceIn(1, bitmap.height - t)
        val cell = max(1, max(w, h) / BLOCKS)
        val region = Bitmap.createBitmap(bitmap, l, t, w, h)
        val small = Bitmap.createScaledBitmap(region, max(1, w / cell), max(1, h / cell), false)
        val mosaic = Bitmap.createScaledBitmap(small, w, h, false)
        Canvas(bitmap).drawBitmap(mosaic, l.toFloat(), t.toFloat(), null)
        region.recycle(); small.recycle(); mosaic.recycle()
    }
}
