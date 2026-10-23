package io.github.monkut.wallscanner.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes the outputs of a YOLO11-seg model (tools/holds_model, exported with ultralytics) and turns its
 * masks into holds on the orthophoto. The model sees the orthophoto letterboxed into a square of
 * [Letterbox.size] pixels; its first output is `[4 + classes + 32] × anchors` (box centre and size in input
 * pixels, class scores already through a sigmoid, 32 mask coefficients), its second the 32 prototype masks at
 * a quarter of the input size. A detection's mask is the sigmoid of the coefficient-weighted sum of the
 * prototypes, cut to its box.
 */
object SegDecoder {
    const val COEFFS = 32
    private const val MIN_SCORE = 0.25f
    private const val NMS_IOU = 0.6f
    private const val MASK_THRESHOLD = 0.5f
    private const val MAX_COVERED = 0.3f // a mask this covered by existing holds is one of them
    private const val MIN_SIDE = 0.04f

    /** How the orthophoto was placed in the model's square input: scaled by [scale], then offset by ([padX], [padY]). */
    class Letterbox(val size: Int, val scale: Float, val padX: Float, val padY: Float) {
        companion object {
            fun fit(width: Int, height: Int, size: Int): Letterbox {
                val scale = size.toFloat() / max(width, height)
                return Letterbox(size, scale, (size - width * scale) / 2f, (size - height * scale) / 2f)
            }
        }
    }

    class Detection(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float, val coeffs: FloatArray)

    /**
     * Detections above [MIN_SCORE] after non-maximum suppression, boxes in input pixels; [output] is
     * channel-major with [anchors] columns. TFLite exports give boxes normalised to 0..1 (an ONNX export gives
     * pixels): when no kept box reaches past 1, they are scaled by [inputSize].
     */
    fun detections(output: FloatArray, channels: Int, anchors: Int, inputSize: Int): List<Detection> {
        val classes = channels - 4 - COEFFS
        val found = ArrayList<Detection>()
        for (i in 0 until anchors) {
            var score = 0f
            for (c in 0 until classes) score = max(score, output[(4 + c) * anchors + i])
            if (score < MIN_SCORE) continue
            val cx = output[i]; val cy = output[anchors + i]; val w = output[2 * anchors + i]; val h = output[3 * anchors + i]
            val coeffs = FloatArray(COEFFS) { k -> output[(4 + classes + k) * anchors + i] }
            found.add(Detection(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score, coeffs))
        }
        if (found.isNotEmpty() && found.maxOf { max(it.x1, it.y1) } <= 1f) {
            for ((k, d) in found.withIndex()) found[k] = Detection(d.x0 * inputSize, d.y0 * inputSize, d.x1 * inputSize, d.y1 * inputSize, d.score, d.coeffs)
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in found) if (kept.none { iou(it, d) > NMS_IOU }) kept.add(d)
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val iw = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0)); val ih = max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
        val inter = iw * ih
        val union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Holds for the detections whose masks no existing hold covers: the mask is sampled at every orthophoto
     * pixel inside the detection's box, the pixels become a hold through [ImageHold]. [protos] is
     * `COEFFS × protoSize × protoSize` when [protosChannelsFirst], else `protoSize × protoSize × COEFFS`.
     */
    fun holds(
        detections: List<Detection>, protos: FloatArray, protoSize: Int, protosChannelsFirst: Boolean, box: Letterbox,
        existing: List<Hold>, photo: Orthophoto, frame: WallFrame,
    ): List<Hold> {
        val w = photo.image.width; val h = photo.image.height
        val owner = ImageHold.owners(existing, photo)
        val minSidePx = max(2, (MIN_SIDE / photo.px).toInt())
        val stride = box.size.toFloat() / protoSize
        var nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val out = ArrayList<Hold>()
        for (d in detections) {
            // The box in orthophoto pixels, clipped to the photo.
            val px0 = max(0, ((d.x0 - box.padX) / box.scale).toInt()); val py0 = max(0, ((d.y0 - box.padY) / box.scale).toInt())
            val px1 = min(w - 1, ((d.x1 - box.padX) / box.scale).roundToInt()); val py1 = min(h - 1, ((d.y1 - box.padY) / box.scale).roundToInt())
            if (px1 < px0 || py1 < py0) continue
            val pixels = ArrayList<Int>()
            var covered = 0
            for (y in py0..py1) for (x in px0..px1) {
                val mx = (((x + 0.5f) * box.scale + box.padX) / stride).toInt().coerceIn(0, protoSize - 1)
                val my = (((y + 0.5f) * box.scale + box.padY) / stride).toInt().coerceIn(0, protoSize - 1)
                var logit = 0f
                if (protosChannelsFirst) for (k in 0 until COEFFS) logit += d.coeffs[k] * protos[(k * protoSize + my) * protoSize + mx]
                else { val base = (my * protoSize + mx) * COEFFS; for (k in 0 until COEFFS) logit += d.coeffs[k] * protos[base + k] }
                if (1f / (1f + exp(-logit)) < MASK_THRESHOLD) continue
                val i = y * w + x
                pixels.add(i)
                if (owner[i] >= 0) covered++
            }
            if (pixels.size < minSidePx * minSidePx || covered >= MAX_COVERED * pixels.size) continue
            if (max(px1 - px0, py1 - py0) + 1 < minSidePx) continue
            out.add(ImageHold.fromPixels(nextId++, pixels.toIntArray(), 0, photo, frame))
        }
        return out
    }
}
