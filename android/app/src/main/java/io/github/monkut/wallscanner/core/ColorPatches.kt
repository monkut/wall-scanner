package io.github.monkut.wallscanner.core

import kotlin.math.max

/**
 * Connected patches of one hue on the orthophoto: pixels saturated at least [minSat] and brighter than
 * [minVal], not the wall's colour, connected through neighbours within [HUE_LINK] of hue. A gap of up to
 * [gapPx] non-matching pixels in a straight line is bridged, so a highlight, a chalk streak or a bolt does
 * not cut one hold into two patches (the bridged pixels themselves are not part of the patch; shapes are
 * closed and hole-filled later). Holds are such patches, and so are tape strips. A dark or dull hold (a
 * dark green foothold reads as saturation 0.4, brightness 0.2 at 5 mm) also counts when its hue lies at
 * least [DARK_HUE] from the wall's and it is at least [DARK_SAT] saturated and [DARK_VAL] bright; the hue
 * test keeps the wall's own shadows out, so it needs a coloured wall. [label] is 0 for no patch, else the
 * patch id (1-based); [hue], [sat], [value] are per pixel.
 */
class ColorPatches private constructor(
    val width: Int, val height: Int,
    val label: IntArray, val count: Int,
    val hue: FloatArray, val sat: FloatArray, val value: FloatArray,
    val size: IntArray, val minX: IntArray, val maxX: IntArray, val minY: IntArray, val maxY: IntArray,
) {
    /** Pixel indices of patch [p]. */
    fun members(p: Int): IntArray {
        val out = IntArray(size[p])
        var k = 0
        for (y in minY[p]..maxY[p]) for (x in minX[p]..maxX[p]) { val i = y * width + x; if (label[i] == p) out[k++] = i }
        return out
    }

    companion object {
        private const val HUE_LINK = 20f
        const val HOLD_SAT = 0.45f
        const val HOLD_VAL = 0.2f
        const val HOLD_GAP_M = 0.03f
        private const val DARK_SAT = 0.30f
        private const val DARK_VAL = 0.15f // below this the hue is quantisation noise (TapeMarks.MIN_VAL says the same)
        private const val DARK_HUE = 30f

        /** Patches for hold detection: hold saturation floor, dark off-wall-hue holds, 3 cm gap bridging. */
        fun forHolds(photo: Orthophoto, wall: WallColor?): ColorPatches =
            find(photo, wall, HOLD_SAT, HOLD_VAL, max(1, (HOLD_GAP_M / photo.px + 0.5f).toInt()), dark = true)

        fun find(photo: Orthophoto, wall: WallColor?, minSat: Float, minVal: Float, gapPx: Int = 0, dark: Boolean = false): ColorPatches {
            val w = photo.image.width; val h = photo.image.height
            val n = w * h
            val pixels = photo.image.pixels
            val hue = FloatArray(n); val sat = FloatArray(n); val value = FloatArray(n)
            val eligible = BooleanArray(n)
            val darkRule = dark && wall != null && wall.sat >= Tuning.WALL_GREY_SAT
            for (i in 0 until n) {
                val p = pixels[i]
                val c = ColorUtil.rgbToHsv(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
                hue[i] = c[0]; sat[i] = c[1]; value[i] = c[2]
                if (wall != null && wall.matches(c)) continue
                val offHue = darkRule && c[1] >= DARK_SAT && c[2] >= DARK_VAL && ColorUtil.hueDistance(c[0], wall!!.hue) >= DARK_HUE
                eligible[i] = (c[1] >= minSat && c[2] >= minVal) || offHue
            }
            val label = IntArray(n)
            val queue = IntArray(n)
            val size = ArrayList<Int>(); val minX = ArrayList<Int>(); val maxX = ArrayList<Int>(); val minY = ArrayList<Int>(); val maxY = ArrayList<Int>()
            size.add(0); minX.add(0); maxX.add(0); minY.add(0); maxY.add(0) // index 0 = no patch
            var count = 0
            for (seed in 0 until n) {
                if (!eligible[seed] || label[seed] != 0) continue
                val id = ++count
                var head = 0; var tail = 0
                queue[tail++] = seed; label[seed] = id
                var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % w; val y = i / w
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                    for (k in 0 until 4) {
                        val dx = if (k == 0) 1 else if (k == 1) -1 else 0; val dy = if (k == 2) 1 else if (k == 3) -1 else 0
                        // Step 1 is the neighbour; steps 2..gapPx+1 look across a gap of non-matching pixels.
                        for (step in 1..gapPx + 1) {
                            val nx = x + dx * step; val ny = y + dy * step
                            if (nx !in 0 until w || ny !in 0 until h) break
                            val j = ny * w + nx
                            if (label[j] == id) break
                            if (!eligible[j] || label[j] != 0) continue
                            if (ColorUtil.hueDistance(hue[j], hue[i]) > HUE_LINK) continue
                            label[j] = id; queue[tail++] = j
                            break
                        }
                    }
                }
                size.add(tail); minX.add(x0); maxX.add(x1); minY.add(y0); maxY.add(y1)
            }
            return ColorPatches(w, h, label, count, hue, sat, value, size.toIntArray(), minX.toIntArray(), maxX.toIntArray(), minY.toIntArray(), maxY.toIntArray())
        }
    }
}
