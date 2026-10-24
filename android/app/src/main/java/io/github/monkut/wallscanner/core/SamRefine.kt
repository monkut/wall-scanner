package io.github.monkut.wallscanner.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hold outlines redrawn by MobileSAM (tools/holds_model/sam_export.py; run by io/HoldSam.kt). The photo is
 * encoded once, then each hold's outline box prompts one mask. SAM follows the hold's own edge, where the
 * outlines from relief and colour take in shadows, chalk and bare wall around it. The new outline is kept
 * when SAM is confident ([MIN_SCORE]) and its mask agrees with the old outline (IoU [MIN_OVERLAP], area at
 * least [MIN_AREA] of it); the mask is read only in the box grown by [MARGIN], which also bounds how far a
 * partial outline can grow into its whole hold. tools/holds_model/sam_refine.py runs the same steps on the
 * desktop.
 */
object SamRefine {
    const val SIZE = 1024
    const val MASK = 256
    private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    const val MIN_SCORE = 0.7f
    const val MIN_OVERLAP = 0.2f
    const val MIN_AREA = 0.25f
    const val MARGIN = 0.25f
    const val MAX_OTHERS = 0.2f
    const val MAX_COVERED = 0.3f // a model mask this covered by the outlines is one of those holds
    const val MERGE_INSIDE = 0.6f
    const val MIN_CONTRAST = 6f
    const val RING_IN = 2
    const val RING_OUT = 6

    /** Photo pixels to encoder input pixels: the longer side becomes [SIZE]. */
    fun scale(image: Image): Float = SIZE.toFloat() / max(image.width, image.height)

    /**
     * The encoder input, `[1, 3, SIZE, SIZE]` channel-major: the photo resized by [scale] (bilinear, as OpenCV's
     * INTER_LINEAR) at the top left, each channel as (value − mean) / std; the padding is 0.
     */
    fun input(image: Image): FloatArray {
        val s = scale(image)
        val w = (image.width * s).roundToInt().coerceIn(1, SIZE); val h = (image.height * s).roundToInt().coerceIn(1, SIZE)
        val pixels = SegDecoder.Letterbox(SIZE, s, 0f, 0f, w, h).pixels(image)
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * SIZE + x
            val p = pixels[i]
            out[i] = ((p shr 16 and 0xff) - MEAN[0]) / STD[0]
            out[plane + i] = ((p shr 8 and 0xff) - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 0xff) - MEAN[2]) / STD[2]
        }
        return out
    }

    /** The hold's outline box in photo pixels (x0, y0, x1, y1); the prompt is this times [scale]. */
    fun box(hold: Hold, photo: Orthophoto): FloatArray {
        val poly = hold.outline
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until poly.size / 2) {
            val x = photo.x(poly[i * 2]); val y = photo.y(poly[i * 2 + 1])
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /**
     * Photo pixels where the mask [logits] (`MASK × MASK` over the encoder input) are above 0, read in [box]
     * (photo pixels) grown by [MARGIN]: each pixel centre is mapped into the logits and sampled bilinearly,
     * edges clamped.
     */
    fun maskPixels(logits: FloatArray, box: FloatArray, scale: Float, width: Int, height: Int): IntArray {
        val mx = (box[2] - box[0]) * MARGIN; val my = (box[3] - box[1]) * MARGIN
        val bx0 = max(0, (box[0] - mx).toInt()); val by0 = max(0, (box[1] - my).toInt())
        val bx1 = min(width - 1, (box[2] + mx).toInt()); val by1 = min(height - 1, (box[3] + my).toInt())
        val k = scale * MASK / SIZE
        val out = ArrayList<Int>()
        for (y in by0..by1) {
            val sy = ((y + 0.5f) * k - 0.5f).coerceIn(0f, (MASK - 1).toFloat())
            val ya = sy.toInt(); val yb = min(ya + 1, MASK - 1); val fy = sy - ya
            for (x in bx0..bx1) {
                val sx = ((x + 0.5f) * k - 0.5f).coerceIn(0f, (MASK - 1).toFloat())
                val xa = sx.toInt(); val xb = min(xa + 1, MASK - 1); val fx = sx - xa
                val top = logits[ya * MASK + xa] * (1 - fx) + logits[ya * MASK + xb] * fx
                val bottom = logits[yb * MASK + xa] * (1 - fx) + logits[yb * MASK + xb] * fx
                if (top * (1 - fy) + bottom * fy > 0f) out.add(y * width + x)
            }
        }
        return out.toIntArray()
    }

    /**
     * The new pixels of a hold whose old outline covers [old], or null to keep the old outline: of [mask]'s
     * 4-connected parts the one overlapping [old] most (else the largest), holes filled, checked against
     * [score] and the old pixels as the class comment says.
     */
    fun accept(mask: IntArray, old: IntArray, score: Float, width: Int): IntArray? {
        if (score < MIN_SCORE || mask.isEmpty() || old.isEmpty()) return null
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (i in mask) { val x = i % width; val y = i / width; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
        // A local grid with one pixel of border, so the background around the mask is connected for hole filling.
        x0--; y0--; x1++; y1++
        val lw = x1 - x0 + 1; val lh = y1 - y0 + 1
        fun local(i: Int) = (i / width - y0) * lw + (i % width - x0)
        fun global(j: Int) = (y0 + j / lw) * width + (x0 + j % lw)
        val on = BooleanArray(lw * lh)
        for (i in mask) on[local(i)] = true
        val wasOld = BooleanArray(lw * lh)
        for (i in old) { val x = i % width - x0; val y = i / width - y0; if (x in 0 until lw && y in 0 until lh) wasOld[y * lw + x] = true }
        val label = IntArray(lw * lh)
        val queue = IntArray(lw * lh)
        var best = 0; var bestOverlap = -1; var bestSize = 0; var next = 0
        for (s in on.indices) {
            if (!on[s] || label[s] != 0) continue
            val id = ++next
            var head = 0; var tail = 0
            queue[tail++] = s; label[s] = id
            var overlap = 0
            while (head < tail) {
                val j = queue[head++]
                if (wasOld[j]) overlap++
                val x = j % lw
                for (n in intArrayOf(if (x > 0) j - 1 else -1, if (x < lw - 1) j + 1 else -1, j - lw, j + lw)) {
                    if (n >= 0 && n < on.size && on[n] && label[n] == 0) { label[n] = id; queue[tail++] = n }
                }
            }
            if (overlap > bestOverlap || (overlap == bestOverlap && tail > bestSize)) { best = id; bestOverlap = overlap; bestSize = tail }
        }
        // Holes: background not reachable from the grid's border.
        val outside = BooleanArray(lw * lh)
        var head = 0; var tail = 0
        for (j in on.indices) {
            val x = j % lw; val y = j / lw
            if ((x == 0 || y == 0 || x == lw - 1 || y == lh - 1) && label[j] != best) { outside[j] = true; queue[tail++] = j }
        }
        while (head < tail) {
            val j = queue[head++]
            val x = j % lw
            for (n in intArrayOf(if (x > 0) j - 1 else -1, if (x < lw - 1) j + 1 else -1, j - lw, j + lw)) {
                if (n >= 0 && n < on.size && !outside[n] && label[n] != best) { outside[n] = true; queue[tail++] = n }
            }
        }
        val kept = (0 until lw * lh).filter { !outside[it] }
        val oldCount = old.size
        val both = kept.count { wasOld[it] }
        val union = kept.size + oldCount - both
        if (both.toFloat() / union < MIN_OVERLAP || kept.size < MIN_AREA * oldCount) return null
        return IntArray(kept.size) { global(kept[it]) }
    }

    /**
     * Pieces of one hold: for each hold's accepted mask (null for none), the index of the largest other mask
     * holding at least [MERGE_INSIDE] of it, else -1. The detector cuts a hold into pieces at a shading or
     * colour change, or where the relief splits it; SAM, prompted with each piece's box, draws the whole hold
     * from the largest piece and the piece itself from a smaller one (Oct 23 2:04 PM: all six pairs the
     * climber named, nothing else). Hosts are themselves never merged.
     */
    fun mergeInto(masks: List<IntArray?>): IntArray {
        val into = IntArray(masks.size) { -1 }
        val hosts = ArrayList<Pair<Int, HashSet<Int>>>()
        for (j in masks.indices.filter { masks[it] != null }.sortedByDescending { masks[it]!!.size }) {
            val m = masks[j]!!
            if (m.isEmpty()) continue
            val host = hosts.firstOrNull { (_, set) -> m.count { it in set } >= MERGE_INSIDE * m.size }
            if (host != null) into[j] = host.first else hosts.add(j to m.toHashSet())
        }
        return into
    }

    /**
     * Whether [pixels] (the new outline of hold index [self]) take more than [MAX_OTHERS] of themselves from
     * other holds outside its [old] pixels: [owner] is [ImageHold.owners], [group] maps a hold index to its
     * merge group, whose members do not count. Each half of a hold split upstream prompts SAM with its own
     * box, and SAM draws the whole hold for both; a bolt-on already inside the old outline does not count.
     */
    fun takesFromOthers(pixels: IntArray, old: IntArray, owner: IntArray, group: IntArray, self: Int): Boolean {
        val wasOld = old.toHashSet()
        return pixels.count { !wasOld.contains(it) && owner[it] >= 0 && group[owner[it]] != group[self] } > MAX_OTHERS * pixels.size
    }

    /**
     * CIE ΔE between the median Lab colour of [pixels] and of the wall around them: the ring from [RING_IN] to
     * [RING_OUT] pixels out (square steps), without pixels of any hold ([covered]) unless fewer than 10 remain.
     * A hold differs from the wall around it (≥ 18 on the Oct 23 walls); an image-only "hold" made of bolt
     * holes, grain or an exposure seam does not (1.3, 1.6).
     */
    fun contrast(pixels: IntArray, image: Image, covered: BooleanArray): Float {
        if (pixels.isEmpty()) return 0f
        val w = image.width; val h = image.height
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in pixels) { val x = i % w; val y = i / w; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
        x0 = max(0, x0 - RING_OUT); y0 = max(0, y0 - RING_OUT); x1 = min(w - 1, x1 + RING_OUT); y1 = min(h - 1, y1 + RING_OUT)
        val lw = x1 - x0 + 1; val lh = y1 - y0 + 1
        val mask = BooleanArray(lw * lh)
        for (i in pixels) mask[(i / w - y0) * lw + (i % w - x0)] = true
        val near = dilateSquare(mask, lw, lh, RING_IN); val far = dilateSquare(mask, lw, lh, RING_OUT)
        val ring = (0 until lw * lh).filter { far[it] && !near[it] }.map { (y0 + it / lw) * w + x0 + it % lw }
        val wall = ring.filter { !covered[it] }.takeIf { it.size >= 10 } ?: ring
        if (wall.isEmpty()) return Float.MAX_VALUE
        val a = medianLab(pixels.asList(), image); val b = medianLab(wall, image)
        return kotlin.math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    }

    private fun medianLab(pixels: List<Int>, image: Image): FloatArray {
        val lab = pixels.map { val p = image.pixels[it]; ColorUtil.rgbToLab(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) }
        return FloatArray(3) { c -> lab.map { it[c] }.sorted()[lab.size / 2] }
    }

    /** [src] grown by [r] pixels in square steps (a (2r+1)² max filter), rows then columns. */
    private fun dilateSquare(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val rows = BooleanArray(src.size)
        for (y in 0 until h) for (x in 0 until w) if (src[y * w + x]) for (dx in max(0, x - r)..min(w - 1, x + r)) rows[y * w + dx] = true
        val out = BooleanArray(src.size)
        for (y in 0 until h) for (x in 0 until w) if (rows[y * w + x]) for (dy in max(0, y - r)..min(h - 1, y + r)) out[dy * w + x] = true
        return out
    }
}
