package io.github.monkut.wallscanner.io

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import io.github.monkut.wallscanner.core.Box
import io.github.monkut.wallscanner.core.FaceMask

/**
 * Faces in an upright frame, from MediaPipe's short-range BlazeFace (`assets/blaze_face_short_range.tflite`,
 * Apache-2.0). That model wants a face to fill a good part of its 128 px input, so each frame is searched whole
 * and as a 2 × 2 grid of tiles, and the detections are merged ([FaceMask.merge]). Call from one thread.
 */
class FaceFinder(context: Context) : AutoCloseable {
    private val detector = FaceDetector.createFromOptions(
        context,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(MIN_CONFIDENCE)
            .build(),
    )

    /** Face boxes in [frame], normalised to it. */
    fun detect(frame: Bitmap): List<Box> {
        val found = ArrayList(run(frame, 0f, 0f, 1f, 1f))
        val tw = frame.width / TILES; val th = frame.height / TILES
        if (tw >= MIN_TILE_PX && th >= MIN_TILE_PX) for (ty in 0 until TILES) for (tx in 0 until TILES) {
            val tile = Bitmap.createBitmap(frame, tx * tw, ty * th, tw, th)
            found += run(tile, tx.toFloat() / TILES, ty.toFloat() / TILES, 1f / TILES, 1f / TILES)
            if (tile !== frame) tile.recycle()
        }
        return FaceMask.merge(found)
    }

    /** Detections in [bitmap], mapped into the frame: the bitmap covers the frame from ([ox], [oy]) over ([sx], [sy]) of it. */
    private fun run(bitmap: Bitmap, ox: Float, oy: Float, sx: Float, sy: Float): List<Box> =
        detector.detect(BitmapImageBuilder(bitmap).build()).detections().map { d ->
            val r = d.boundingBox()
            Box(ox + sx * r.left / bitmap.width, oy + sy * r.top / bitmap.height, sx * r.width() / bitmap.width, sy * r.height() / bitmap.height)
        }

    override fun close() = detector.close()

    companion object {
        const val MODEL = "blaze_face_short_range.tflite"
        private const val MIN_CONFIDENCE = 0.5f
        private const val TILES = 2
        private const val MIN_TILE_PX = 64
    }
}
