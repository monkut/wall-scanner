package io.github.monkut.wallscanner.climbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import io.github.monkut.wallscanner.core.FaceFrame
import io.github.monkut.wallscanner.core.FaceMask
import io.github.monkut.wallscanner.io.ClipExport
import java.io.File

/**
 * A clip's trimmed range re-encoded with its overlay burnt in (media3 Transformer): face masks from the sidecar
 * and the caption, drawn on a bitmap the size of the decoded, upright frame and uploaded again only when what it
 * shows changes (frame times are relative to the trim start). Audio is copied. A clip with nothing to draw goes
 * through [ClipExport.trimmedCopy] instead.
 */
@OptIn(UnstableApi::class)
object OverlayExport {
    private const val TAG = "OverlayExport"
    /** Masks are re-read when the clip's time moves to a new step of this size. */
    private const val STEP_MS = 40L
    private const val PROGRESS_MS = 300L

    /** Whether [start] would draw anything on [startMs]..[endMs]: the roles chosen have faces there, or a caption is on. */
    fun hasOverlay(frames: List<FaceFrame>, startMs: Long, endMs: Long, climber: Boolean, bystanders: Boolean, caption: CaptionPainter?): Boolean =
        (caption != null && !caption.caption.empty) || FaceMask.any(frames, startMs, endMs, climber, bystanders)

    /**
     * Starts the export of [source]'s [startMs]..[endMs] to [target] and returns the running job (cancel it when
     * the screen goes). [onProgress] gets 0–100 and [onDone] the outcome, both on the main thread. Call on the
     * main thread.
     */
    fun start(
        context: Context, source: File, startMs: Long, endMs: Long, frames: List<FaceFrame>, climber: Boolean, bystanders: Boolean,
        caption: CaptionPainter?, target: File, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit,
    ): Transformer {
        val (width, height) = ClipExport.frameSize(source)
        val overlay = object : BitmapOverlay() {
            private val bitmap = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
            private val canvas = Canvas(bitmap)
            private var step = Long.MIN_VALUE
            private var drawn: List<String>? = null // the boxes on the bitmap; a static caption alone is drawn once

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val tMs = startMs + presentationTimeUs / 1000
                val now = tMs / STEP_MS
                if (now == step) return bitmap
                step = now
                val boxes = FaceMask.boxesAt(frames, tMs, climber, bystanders)
                val key = boxes.map { "${it.role.id}:${"%.3f,%.3f,%.3f,%.3f".format(it.box.x, it.box.y, it.box.w, it.box.h)}" }
                if (key == drawn) return bitmap
                drawn = key
                bitmap.eraseColor(Color.TRANSPARENT) // bumps the bitmap's generation id, so the texture is uploaded again
                val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
                if (boxes.isNotEmpty()) MaskPainter.draw(canvas, boxes, 0f, 0f, w, h)
                caption?.draw(canvas, 0f, 0f, w, h)
                return bitmap
            }
        }
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(source))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of(overlay))))).build()
        val handler = Handler(Looper.getMainLooper())
        lateinit var transformer: Transformer
        lateinit var poll: Runnable
        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { handler.removeCallbacks(poll); onDone(true) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(TAG, "overlay export failed", exportException)
                    handler.removeCallbacks(poll)
                    onDone(false)
                }
            })
            .build()
        val holder = ProgressHolder()
        poll = Runnable {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
            handler.postDelayed(poll, PROGRESS_MS)
        }
        target.delete()
        transformer.start(edited, target.path)
        handler.postDelayed(poll, PROGRESS_MS)
        return transformer
    }
}
