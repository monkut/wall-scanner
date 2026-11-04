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
    const val HOLD = 0
    const val TAPE = 1 // two-class models (tools/holds_model since G1) also find grade tape
    private const val MIN_SCORE = 0.25f
    private const val NMS_IOU = 0.6f
    private const val MASK_THRESHOLD = 0.5f
    private const val MIN_SIDE = 0.04f

    /**
     * How the orthophoto is placed in the model's square input, as ultralytics' LetterBox places training and
     * prediction images: resized by [scale] to [width] × [height] (rounded), centred at ([padX], [padY])
     * (rounded down from the half), the rest grey.
     */
    class Letterbox(val size: Int, val scale: Float, val padX: Float, val padY: Float, val width: Int = size, val height: Int = size) {
        /**
         * The letterboxed input as `size × size` RGB pixels. The photo is resized bilinearly as OpenCV's
         * INTER_LINEAR does (source = (x + 0.5) · source size / resized size − 0.5, edges clamped): the
         * model was trained on images resized that way, and nearest-pixel sampling halves what it finds.
         */
        fun pixels(image: Image): IntArray {
            val out = IntArray(size * size) { GREY }
            val w = image.width; val h = image.height; val src = image.pixels
            val kx = w.toFloat() / width; val ky = h.toFloat() / height
            val x0 = padX.toInt(); val y0 = padY.toInt()
            for (y in 0 until height) {
                val sy = ((y + 0.5f) * ky - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val ya = sy.toInt(); val yb = min(ya + 1, h - 1); val fy = sy - ya
                for (x in 0 until width) {
                    val sx = ((x + 0.5f) * kx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                    val xa = sx.toInt(); val xb = min(xa + 1, w - 1); val fx = sx - xa
                    val p00 = src[ya * w + xa]; val p01 = src[ya * w + xb]; val p10 = src[yb * w + xa]; val p11 = src[yb * w + xb]
                    var rgb = 0
                    for (shift in intArrayOf(16, 8, 0)) {
                        val top = (p00 shr shift and 0xff) * (1 - fx) + (p01 shr shift and 0xff) * fx
                        val bottom = (p10 shr shift and 0xff) * (1 - fx) + (p11 shr shift and 0xff) * fx
                        rgb = rgb or ((top * (1 - fy) + bottom * fy + 0.5f).toInt().coerceIn(0, 255) shl shift)
                    }
                    out[(y0 + y) * size + x0 + x] = rgb
                }
            }
            return out
        }

        companion object {
            private const val GREY = 0x727272 // ultralytics' letterbox fill (114)

            fun fit(width: Int, height: Int, size: Int): Letterbox {
                val scale = size.toFloat() / max(width, height)
                val w = (width * scale).roundToInt().coerceIn(1, size); val h = (height * scale).roundToInt().coerceIn(1, size)
                return Letterbox(size, scale, ((size - w) / 2f - 0.1f).roundToInt().toFloat(), ((size - h) / 2f - 0.1f).roundToInt().toFloat(), w, h)
            }
        }
    }

    /** A detection of class [cls] ([HOLD], or [TAPE] for two-class models). */
    class Detection(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float, val coeffs: FloatArray, val cls: Int = HOLD)

    /**
     * Detections above [MIN_SCORE] after non-maximum suppression, boxes in input pixels; [output] is
     * channel-major with [anchors] columns. TFLite exports give boxes normalised to 0..1 (an ONNX export gives
     * pixels): when no box centre lies past 1, they are scaled by [inputSize]. Centres, not edges: a box on a
     * hold cut off by the photo's edge reaches past 1.
     */
    fun detections(output: FloatArray, channels: Int, anchors: Int, inputSize: Int): List<Detection> {
        val classes = channels - 4 - COEFFS
        val found = ArrayList<Detection>()
        for (i in 0 until anchors) {
            var score = 0f
            var cls = 0
            for (c in 0 until classes) { val s = output[(4 + c) * anchors + i]; if (s > score) { score = s; cls = c } }
            if (score < MIN_SCORE) continue
            val cx = output[i]; val cy = output[anchors + i]; val w = output[2 * anchors + i]; val h = output[3 * anchors + i]
            val coeffs = FloatArray(COEFFS) { k -> output[(4 + classes + k) * anchors + i] }
            found.add(Detection(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score, coeffs, cls))
        }
        if (found.isNotEmpty() && found.maxOf { max(it.x0 + it.x1, it.y0 + it.y1) / 2 } <= 1f) {
            for ((k, d) in found.withIndex()) found[k] = Detection(d.x0 * inputSize, d.y0 * inputSize, d.x1 * inputSize, d.y1 * inputSize, d.score, d.coeffs, d.cls)
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in found) if (kept.none { it.cls == d.cls && iou(it, d) > NMS_IOU }) kept.add(d)
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val iw = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0)); val ih = max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
        val inter = iw * ih
        val union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Holds for the detections that [HoldSearch.plausible] accepts when [plausibleOnly] (the model also
     * outlines tape strips and crash pads), numbered from [firstId]: the mask is sampled at every orthophoto pixel inside the
     * detection's box, the pixels become a hold through [ImageHold]. [protos] is `COEFFS × protoSize ×
     * protoSize` when [protosChannelsFirst], else `protoSize × protoSize × COEFFS`. [HoldMerge] decides
     * which of them replace or join the other holds.
     */
    fun holds(
        detections: List<Detection>, protos: FloatArray, protoSize: Int, protosChannelsFirst: Boolean, box: Letterbox,
        photo: Orthophoto, surface: WallSurface, extent: FloatArray, firstId: Int, plausibleOnly: Boolean = true,
    ): List<Hold> {
        val w = photo.image.width; val h = photo.image.height
        val minSidePx = max(2, (MIN_SIDE / photo.px).toInt())
        val stride = box.size.toFloat() / protoSize
        var nextId = firstId
        val out = ArrayList<Hold>()
        for (d in detections) {
            // The box in orthophoto pixels, clipped to the photo.
            val px0 = max(0, ((d.x0 - box.padX) / box.scale).toInt()); val py0 = max(0, ((d.y0 - box.padY) / box.scale).toInt())
            val px1 = min(w - 1, ((d.x1 - box.padX) / box.scale).roundToInt()); val py1 = min(h - 1, ((d.y1 - box.padY) / box.scale).roundToInt())
            if (px1 < px0 || py1 < py0) continue
            val pixels = ArrayList<Int>()
            for (y in py0..py1) for (x in px0..px1) {
                val mx = (((x + 0.5f) * box.scale + box.padX) / stride).toInt().coerceIn(0, protoSize - 1)
                val my = (((y + 0.5f) * box.scale + box.padY) / stride).toInt().coerceIn(0, protoSize - 1)
                var logit = 0f
                if (protosChannelsFirst) for (k in 0 until COEFFS) logit += d.coeffs[k] * protos[(k * protoSize + my) * protoSize + mx]
                else { val base = (my * protoSize + mx) * COEFFS; for (k in 0 until COEFFS) logit += d.coeffs[k] * protos[base + k] }
                if (1f / (1f + exp(-logit)) < MASK_THRESHOLD) continue
                pixels.add(y * w + x)
            }
            if (pixels.size < minSidePx * minSidePx) continue
            if (max(px1 - px0, py1 - py0) + 1 < minSidePx) continue
            val members = pixels.toIntArray()
            if (plausibleOnly && !HoldSearch.plausible(members, members.size, photo, extent)) continue
            out.add(ImageHold.fromPixels(nextId++, members, 0, photo, surface))
        }
        return out
    }
}
