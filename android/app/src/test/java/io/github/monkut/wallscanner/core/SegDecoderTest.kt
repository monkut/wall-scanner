package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** YOLO-seg outputs decode into boxes after NMS, and a mask becomes a hold with the disc's size, colour and position. */
class SegDecoderTest {
    private val px = 0.005f
    private val frame = WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))
    private val size = 256   // model input
    private val proto = 64   // prototype resolution (input / 4)

    /** 1 × 1 m tan wall (200 px) with a red disc of radius 0.08 m at (0.3, 0.6). */
    private fun photo(): Orthophoto {
        val n = (1f / px).toInt()
        val pixels = IntArray(n * n) { i ->
            val u = (i % n + 0.5f) * px; val v = 1f - (i / n + 0.5f) * px
            if (hypot(u - 0.3f, v - 0.6f) <= 0.08f) 0xffC23B3B.toInt() else 0xffC9B496.toInt()
        }
        return Orthophoto(Image(n, n, pixels), 0f, 0f, 1f, 1f, px, FloatArray(n * n))
    }

    /** Two anchors: a confident box on the disc, and a weaker overlapping duplicate that NMS must drop. */
    private fun output(box: SegDecoder.Letterbox): Pair<FloatArray, Int> {
        val anchors = 2
        val channels = 4 + 1 + SegDecoder.COEFFS
        val out = FloatArray(channels * anchors)
        // Disc centre and box side as a TFLite export gives them: normalised to the input size.
        val cx = (60 * box.scale + box.padX) / size; val cy = (80 * box.scale + box.padY) / size
        val side = 40 * box.scale / size
        for (i in 0 until anchors) {
            out[i] = cx + i * 2f / size; out[anchors + i] = cy; out[2 * anchors + i] = side; out[3 * anchors + i] = side
            out[4 * anchors + i] = if (i == 0) 0.9f else 0.6f
            out[(5 + 0) * anchors + i] = 1f // coefficient 0 selects prototype 0; the rest stay 0
        }
        return out to anchors
    }

    /** Prototype 0 is +6 inside the disc and −6 outside; the others are 0. */
    private fun protos(box: SegDecoder.Letterbox): FloatArray {
        val stride = size.toFloat() / proto
        return FloatArray(SegDecoder.COEFFS * proto * proto) { idx ->
            val k = idx / (proto * proto)
            if (k != 0) 0f else {
                val my = idx / proto % proto; val mx = idx % proto
                val x = ((mx + 0.5f) * stride - box.padX) / box.scale; val y = ((my + 0.5f) * stride - box.padY) / box.scale
                if (hypot(x - 60f, y - 80f) <= 16f) 6f else -6f
            }
        }
    }

    @Test
    fun decodesOneHoldFromTheMask() {
        val p = photo()
        val box = SegDecoder.Letterbox.fit(p.image.width, p.image.height, size)
        val (out, anchors) = output(box)
        val dets = SegDecoder.detections(out, 4 + 1 + SegDecoder.COEFFS, anchors, size)
        assertEquals("NMS keeps one of two overlapping boxes", 1, dets.size)
        assertEquals(0.9f, dets[0].score, 1e-6f)
        val holds = SegDecoder.holds(dets, protos(box), proto, true, box, emptyList(), p, frame)
        assertEquals(1, holds.size)
        val h = holds[0]
        assertTrue("centre (${h.u}, ${h.v})", abs(h.u - 0.3f) < 0.01f && abs(h.v - 0.6f) < 0.01f)
        assertTrue("width ${h.width}", abs(h.width - 0.16f) < 0.03f)
        assertEquals("Red", ColorUtil.hueName(h.hue))
        assertTrue("outline", h.outline.size / 2 >= 12)
    }

    @Test
    fun aMaskCoveredByAnExistingHoldIsNotAdded() {
        val p = photo()
        val box = SegDecoder.Letterbox.fit(p.image.width, p.image.height, size)
        val (out, anchors) = output(box)
        val existing = Hold(1, 0.3f, 0.6f, 0f, 0.3f, 0.6f, 0.2f, 0.2f, 0.05f, 194, 59, 59, 100, Raster.ellipse(0.3f, 0.6f, 0.2f, 0.2f))
        val holds = SegDecoder.holds(SegDecoder.detections(out, 4 + 1 + SegDecoder.COEFFS, anchors, size), protos(box), proto, true, box, listOf(existing), p, frame)
        assertEquals(0, holds.size)
    }
}
