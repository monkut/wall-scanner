package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** YOLO-seg outputs decode into boxes after NMS, and a mask becomes a hold with the disc's size, colour and position. */
class SegDecoderTest {
    private val px = 0.005f
    private val frame = WallSurface.single(WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f)))
    private val size = 256   // model input
    private val proto = 64   // prototype resolution (input / 4)
    private val extent = floatArrayOf(0f, 0f, 1f, 1f)

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

    /** Prototype 0 is +6 inside the disc (or [inside]) and −6 outside; the others are 0. */
    private fun protos(box: SegDecoder.Letterbox, inside: (Float, Float) -> Boolean = { x, y -> hypot(x - 60f, y - 80f) <= 16f }): FloatArray {
        val stride = size.toFloat() / proto
        return FloatArray(SegDecoder.COEFFS * proto * proto) { idx ->
            val k = idx / (proto * proto)
            if (k != 0) 0f else {
                val my = idx / proto % proto; val mx = idx % proto
                val x = ((mx + 0.5f) * stride - box.padX) / box.scale; val y = ((my + 0.5f) * stride - box.padY) / box.scale
                if (inside(x, y)) 6f else -6f
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
        val holds = SegDecoder.holds(dets, protos(box), proto, true, box, p, frame, extent, 1)
        assertEquals(1, holds.size)
        val h = holds[0]
        assertTrue("centre (${h.u}, ${h.v})", abs(h.u - 0.3f) < 0.01f && abs(h.v - 0.6f) < 0.01f)
        assertTrue("width ${h.width}", abs(h.width - 0.16f) < 0.03f)
        assertEquals("Red", ColorUtil.hueName(h.hue))
        assertTrue("outline", h.outline.size / 2 >= 12)
    }

    @Test
    fun aTapeShapedMaskIsNotAdded() {
        val p = photo()
        val box = SegDecoder.Letterbox.fit(p.image.width, p.image.height, size)
        val (out, anchors) = output(box)
        val strip = protos(box) { x, y -> abs(x - 60f) <= 2f && abs(y - 80f) <= 20f } // 2 × 20 cm, hanging
        val holds = SegDecoder.holds(SegDecoder.detections(out, 4 + 1 + SegDecoder.COEFFS, anchors, size), strip, proto, true, box, p, frame, extent, 1)
        assertEquals(0, holds.size)
    }

    @Test
    fun normalisedBoxesReachingPastTheEdgeAreStillScaled() {
        // A hold cut off by the photo's right edge: its normalised box ends at 1.02.
        val channels = 4 + 1 + SegDecoder.COEFFS
        val out = FloatArray(channels).also { it[0] = 0.95f; it[1] = 0.5f; it[2] = 0.14f; it[3] = 0.1f; it[4] = 0.9f }
        val d = SegDecoder.detections(out, channels, 1, size).single()
        assertEquals(0.88f * size, d.x0, 1e-3f)
        assertEquals(1.02f * size, d.x1, 1e-3f)
    }

    @Test
    fun overlappingBoxesOfTwoClassesBothStay() {
        // A two-class output: a hold box and a tape box on the same spot; duplicates are removed per class.
        val channels = 4 + 2 + SegDecoder.COEFFS
        val anchors = 2
        val out = FloatArray(channels * anchors)
        for (i in 0 until anchors) { out[i] = 0.5f; out[anchors + i] = 0.5f; out[2 * anchors + i] = 0.2f; out[3 * anchors + i] = 0.2f }
        out[4 * anchors + 0] = 0.9f // anchor 0: hold
        out[5 * anchors + 1] = 0.8f // anchor 1: tape
        val dets = SegDecoder.detections(out, channels, anchors, size)
        assertEquals(listOf(SegDecoder.HOLD, SegDecoder.TAPE), dets.map { it.cls })
    }

    @Test
    fun letterboxMatchesOpenCv() {
        // Expected: cv2.resize(INTER_LINEAR) into ultralytics' letterbox (fill 114), the model's training input.
        val w = 5; val h = 3
        val image = Image(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (((x * 50 + y * 20) % 256) shl 16) or (((x * 13 + y * 90) % 256) shl 8) or ((x * x * 9 + y * 40) % 256)
        })
        val box = SegDecoder.Letterbox.fit(w, h, 8)
        assertEquals(listOf(8, 5, 0, 1), listOf(box.width, box.height, box.padX.toInt(), box.padY.toInt()))
        val expected = intArrayOf(
            7500402, 7500402, 7500402, 7500402, 7500402, 7500402, 7500402, 7500402,
            0, 1443332, 3477003, 5510684, 7544370, 9643598, 11677300, 13120656,
            533520, 1976852, 4010523, 6044204, 8077890, 10177118, 12210820, 13654176,
            1333800, 2777132, 4810803, 6844484, 8943706, 10977398, 13011100, 14454456,
            2134080, 3577412, 5611083, 7644764, 9678450, 11777678, 13811380, 15254736,
            2667600, 4110932, 6144603, 8178284, 10211970, 12311198, 14344900, 15788256,
            7500402, 7500402, 7500402, 7500402, 7500402, 7500402, 7500402, 7500402,
            7500402, 7500402, 7500402, 7500402, 7500402, 7500402, 7500402, 7500402,
        )
        val got = box.pixels(image)
        // OpenCV interpolates in fixed point: allow one level per channel.
        for (i in expected.indices) for (shift in intArrayOf(16, 8, 0)) {
            val d = abs((got[i] shr shift and 0xff) - (expected[i] shr shift and 0xff))
            assertTrue("pixel $i channel $shift off by $d", d <= 1)
        }
    }
}
