package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Hold shapes read from the orthophoto.
 *
 * A coloured hold is the colour patch ([ColorPatches]) its depth seed lies on: the whole patch, closed by
 * [CLOSE_M] and with its holes filled, so highlights and chalk inside the hold do not notch the outline. Two
 * depth seeds on one patch are one hold (a big wave the relief split in two) and are merged. A grey, white
 * or black hold grows from its seed over pixels whose Lab colour is closer to the hold than to the wall
 * around it, never into another hold and at most [BAND] beyond the seed. A hold whose colour cannot be told
 * from the wall ([MIN_SEPARATION]) keeps its depth outline. Lab values follow OpenCV's 8-bit scale with
 * lightness weighted by 0.6, matching the offline prototype.
 */
object HoldShapes {
    private const val CHROMA_MIN = 25f
    private const val L_WEIGHT = 0.6f
    private const val MIN_SEPARATION = 8f
    private const val CORE_INSET = 0.02f
    private const val NEAR = 0.03f
    private const val FAR = 0.15f
    private const val SEARCH = 0.2f
    private const val BAND = 0.10f
    private const val OTHER_GAP = 0.01f
    private const val CLOSE_M = 0.04f // holds are smooth: notches narrower than this are highlights, chalk or bolts
    private const val PATCH_CLAIM_SEED = 0.2f // a patch under at least this share of the seed belongs to the hold …
    private const val PATCH_CLAIM_PATCH = 0.3f // … or one at least this covered by the seed
    private const val MAX_POINTS = 96

    /**
     * Holds with image-based outlines; holds merged into another are dropped, so the result can be shorter
     * than [holds]. Roles and ids of surviving holds are kept.
     */
    fun refine(holds: List<Hold>, photo: Orthophoto, patches: ColorPatches, wall: WallColor?): List<Hold> {
        val w = photo.image.width; val h = photo.image.height
        val lab = toLab(photo.image)
        val owner = IntArray(w * h)
        val seeds = HashMap<Int, IntArray>()
        for (hold in holds) {
            val seed = rasterSeed(hold, photo)
            if (seed.isEmpty()) continue
            seeds[hold.id] = seed
            for (i in seed) owner[i] = hold.id
        }
        // Coloured holds claim patches; holds sharing a patch merge (union-find over hold indices).
        val parent = IntArray(holds.size) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val claims = HashMap<Int, IntArray>() // hold index -> patch ids
        val patchOwner = HashMap<Int, Int>()  // patch id -> hold index
        val chromatic = BooleanArray(holds.size)
        for ((hi, hold) in holds.withIndex()) {
            val seed = seeds[hold.id] ?: continue
            val core = coreOf(seed, photo)
            val holdLab = medianLab(lab, core)
            if (hypot(holdLab[1], holdLab[2]) < CHROMA_MIN) continue
            chromatic[hi] = true
            val counts = HashMap<Int, Int>()
            for (i in seed) { val p = patches.label[i]; if (p != 0) counts[p] = (counts[p] ?: 0) + 1 }
            val mine = counts.filter { (p, c) -> c >= PATCH_CLAIM_SEED * seed.size || c >= PATCH_CLAIM_PATCH * patches.size[p] }.keys.toIntArray()
            if (mine.isEmpty()) continue
            claims[hi] = mine
            for (p in mine) {
                val other = patchOwner[p]
                if (other == null) patchOwner[p] = hi else parent[find(hi)] = find(other)
            }
        }
        val out = ArrayList<Hold>()
        val done = BooleanArray(holds.size)
        for ((hi, hold) in holds.withIndex()) {
            if (done[hi]) continue
            val seed = seeds[hold.id]
            if (seed == null) { out.add(hold); done[hi] = true; continue }
            if (chromatic[hi] && claims.containsKey(hi)) {
                val group = holds.indices.filter { find(it) == find(hi) && claims.containsKey(it) }
                group.forEach { done[it] = true }
                val primary = group.maxByOrNull { holds[it].voxels } ?: hi
                val region = BooleanArray(w * h)
                val patchIds = group.flatMap { claims[it]!!.toList() }.toMutableSet()
                for (i in 0 until w * h) if (patches.label[i] in patchIds) region[i] = true
                for (g in group) for (i in seeds[holds[g].id] ?: IntArray(0)) region[i] = true
                // Twice: a grey part can bridge to a further coloured lobe, and vice versa (the purple wave's chalky wing).
                repeat(2) {
                    absorbNeighbours(region, patchIds, patches, patchOwner, w, h, photo)
                    if (wall != null) absorbGrey(region, patches, wall, owner, group.map { holds[it].id }.toSet(), w, h, photo)
                }
                val outline = outlineOf(region, w, h, photo, m(CLOSE_M, photo)) ?: continue
                out.add(withOutline(holds[primary], outline, photo))
            } else {
                done[hi] = true
                val outline = segmentAchromatic(hold, seed, lab, owner, photo)
                out.add(if (outline != null) withOutline(hold, outline, photo) else hold)
            }
        }
        return out
    }

    private fun m(metres: Float, photo: Orthophoto) = max(1, (metres / photo.px + 0.5f).toInt())

    /**
     * Adds to [region] every unclaimed patch of a similar hue that either lies mostly within [ABSORB_M] of it
     * or shares at least [ABSORB_CONTACT] of its border with it: a highlight, a shaded underside or a chalky
     * part of the same hold that the saturation floor or a hue shift cut off from its main patch. Two holds
     * of one colour that merely touch share little border and stay apart.
     */
    private fun absorbNeighbours(region: BooleanArray, mine: MutableSet<Int>, patches: ColorPatches, patchOwner: Map<Int, Int>, w: Int, h: Int, photo: Orthophoto) {
        val near = dilate(region, w, h, m(ABSORB_M, photo))
        var sinSum = 0.0; var cosSum = 0.0
        for (i in 0 until w * h) if (region[i] && patches.label[i] in mine) { val r = Math.toRadians(patches.hue[i].toDouble()); sinSum += Math.sin(r); cosSum += Math.cos(r) }
        val hue = Math.toDegrees(atan2(sinSum, cosSum)).toFloat()
        val inside = IntArray(patches.count + 1)
        val border = IntArray(patches.count + 1); val contact = IntArray(patches.count + 1)
        for (i in 0 until w * h) {
            val p = patches.label[i]
            if (p == 0 || p in mine) continue
            if (near[i]) inside[p]++
            val x = i % w; val y = i / w
            val onBorder = (x == 0 || patches.label[i - 1] != p) || (x == w - 1 || patches.label[i + 1] != p) || (y == 0 || patches.label[i - w] != p) || (y == h - 1 || patches.label[i + w] != p)
            if (onBorder) { border[p]++; if (near[i]) contact[p]++ }
        }
        for (p in 1..patches.count) {
            if (p in mine || patchOwner.containsKey(p)) continue
            if (inside[p] < ABSORB_FRACTION * patches.size[p] && contact[p] < ABSORB_CONTACT * border[p]) continue
            var sinP = 0.0; var cosP = 0.0
            for (y in patches.minY[p]..patches.maxY[p]) for (x in patches.minX[p]..patches.maxX[p]) {
                val i = y * w + x
                if (patches.label[i] != p) continue
                val r = Math.toRadians(patches.hue[i].toDouble()); sinP += Math.sin(r); cosP += Math.cos(r)
            }
            val hueP = Math.toDegrees(atan2(sinP, cosP)).toFloat()
            if (abs(((hueP - hue) % 360f + 540f) % 360f - 180f) > ABSORB_HUE_TOL) continue
            mine.add(p)
            for (y in patches.minY[p]..patches.maxY[p]) for (x in patches.minX[p]..patches.maxX[p]) if (patches.label[y * w + x] == p) region[y * w + x] = true
        }
    }

    private const val ABSORB_M = 0.02f
    private const val ABSORB_FRACTION = 0.6f
    private const val ABSORB_CONTACT = 0.3f
    private const val ABSORB_HUE_TOL = 30f

    /**
     * Two-tone and shaded holds: adds to [region] the grey, white or dark pixels (not wall-coloured, not part
     * of any colour patch, not inside another hold) that touch it within [GREY_REACH_M], after an opening of
     * [GREY_OPEN_M] that drops thin things like tape strips and shadow edges. The purple wave's chalky
     * underside and a white highlight band are the cases.
     */
    private fun absorbGrey(region: BooleanArray, patches: ColorPatches, wall: WallColor, owner: IntArray, mine: Set<Int>, w: Int, h: Int, photo: Orthophoto) {
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (i in 0 until w * h) if (region[i]) { val x = i % w; val y = i / w; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
        if (maxX < 0) return
        val reach = m(GREY_REACH_M, photo); val open = m(GREY_OPEN_M, photo)
        val x0 = max(0, minX - reach - open); val y0 = max(0, minY - reach - open)
        val x1 = min(w - 1, maxX + reach + open); val y1 = min(h - 1, maxY + reach + open)
        val ww = x1 - x0 + 1; val wh = y1 - y0 + 1
        fun global(j: Int) = (y0 + j / ww) * w + (x0 + j % ww)
        val local = BooleanArray(ww * wh) { region[global(it)] }
        val cand = BooleanArray(ww * wh) { j ->
            val i = global(j)
            !region[i] && patches.label[i] == 0 && (owner[i] == 0 || owner[i] in mine) &&
                patches.sat[i] < ColorPatches.HOLD_SAT && !wall.matches(patches.hue[i], patches.sat[i], patches.value[i])
        }
        val thick = dilate(erode(cand, ww, wh, open), ww, wh, open)
        val near = dilate(local, ww, wh, reach)
        for (j in thick.indices) thick[j] = thick[j] && near[j]
        // Each grey piece joins only if enough of its border lies against the coloured region (within 1 cm: a
        // thin band of in-between colour often separates them): the underside of a two-tone hold is wrapped by
        // its colour, a neighbouring dark hold only touches it at one point.
        val touch = dilate(local, ww, wh, m(0.01f, photo))
        val label = IntArray(ww * wh)
        val queue = IntArray(ww * wh)
        var next = 0
        for (s in thick.indices) {
            if (!thick[s] || label[s] != 0) continue
            val id = ++next
            var head = 0; var tail = 0
            queue[tail++] = s; label[s] = id
            var border = 0; var contact = 0
            while (head < tail) {
                val j = queue[head++]
                val x = j % ww; val y = j / ww
                var edge = false; var touches = false
                for (k in 0 until 4) {
                    val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0); val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                    if (nx !in 0 until ww || ny !in 0 until wh) { edge = true; continue }
                    val q = ny * ww + nx
                    if (thick[q]) { if (label[q] == 0) { label[q] = id; queue[tail++] = q } } else { edge = true; if (touch[q]) touches = true }
                }
                if (edge) { border++; if (touches) contact++ }
            }
            if (contact >= GREY_CONTACT * border) for (q in 0 until tail) region[global(queue[q])] = true
        }
    }

    private const val GREY_REACH_M = 0.08f
    private const val GREY_OPEN_M = 0.015f
    private const val GREY_CONTACT = 0.3f

    /** [hold] with [outline] and extents measured from it. */
    private fun withOutline(hold: Hold, outline: FloatArray, photo: Orthophoto): Hold {
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in 0 until outline.size / 2) {
            minU = min(minU, outline[i * 2]); maxU = max(maxU, outline[i * 2]); minV = min(minV, outline[i * 2 + 1]); maxV = max(maxV, outline[i * 2 + 1])
        }
        return Hold(hold.id, hold.cx, hold.cy, hold.cz, hold.u, hold.v, maxU - minU, maxV - minV, hold.height, hold.r, hold.g, hold.b, hold.voxels, outline)
            .also { it.role = hold.role }
    }

    /** Closes [region] by [closePx], fills its holes, keeps its largest component and traces a smoothed outline in metres. */
    private fun outlineOf(region: BooleanArray, w: Int, h: Int, photo: Orthophoto, closePx: Int): FloatArray? {
        var r = erode(dilate(region, w, h, closePx), w, h, closePx)
        fillHoles(r, w, h)
        r = largestComponent(r, w, h)
        val count = r.count { it }
        if (count == 0) return null
        val cells = IntArray(count); val cu = IntArray(count); val cv = IntArray(count)
        var k = 0
        for (j in 0 until w * h) {
            if (!r[j]) continue
            cells[k] = k; cu[k] = j % w; cv[k] = (h - 1) - j / w; k++
        }
        val pts = HoldOutline.trace(cells, cu, cv, photo.px, MAX_POINTS, SMOOTH_PASSES)
        for (i in 0 until pts.size / 2) { pts[i * 2] += photo.minU; pts[i * 2 + 1] += photo.maxV - h * photo.px }
        return pts
    }

    private const val SMOOTH_PASSES = 3 // photo outlines are 5 mm pixel steps; real hold edges are smooth

    private fun coreOf(seed: IntArray, photo: Orthophoto): IntArray {
        val w = photo.image.width
        val inset = m(CORE_INSET, photo)
        val set = HashSet<Int>(seed.size * 2)
        for (i in seed) set.add(i)
        val core = seed.filter { i ->
            val x = i % w; val y = i / w
            var inside = true
            loop@ for (dy in -inset..inset) for (dx in -inset..inset) {
                if (dx * dx + dy * dy > inset * inset) continue
                if (!set.contains((y + dy) * w + (x + dx))) { inside = false; break@loop }
            }
            inside
        }
        return if (core.size >= 20) core.toIntArray() else seed
    }

    private fun medianLab(lab: FloatArray, pixels: IntArray): FloatArray {
        if (pixels.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        val ls = FloatArray(pixels.size) { lab[pixels[it] * 3] }; val `as` = FloatArray(pixels.size) { lab[pixels[it] * 3 + 1] }; val bs = FloatArray(pixels.size) { lab[pixels[it] * 3 + 2] }
        ls.sort(); `as`.sort(); bs.sort()
        return floatArrayOf(ls[pixels.size / 2], `as`[pixels.size / 2], bs[pixels.size / 2])
    }

    private fun segmentAchromatic(hold: Hold, seed: IntArray, lab: FloatArray, owner: IntArray, photo: Orthophoto): FloatArray? {
        val w = photo.image.width; val h = photo.image.height
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (i in seed) {
            val x = i % w; val y = i / w
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val search = m(SEARCH, photo)
        val x0 = max(0, minX - search); val y0 = max(0, minY - search)
        val x1 = min(w - 1, maxX + search); val y1 = min(h - 1, maxY + search)
        val ww = x1 - x0 + 1; val wh = y1 - y0 + 1
        val n = ww * wh
        fun local(i: Int) = (i / w - y0) * ww + (i % w - x0)
        val seedMask = BooleanArray(n)
        for (i in seed) seedMask[local(i)] = true
        val distOut = distanceToSet(seedMask, ww, wh, inside = false)
        val distIn = distanceToSet(seedMask, ww, wh, inside = true)
        val core = BooleanArray(n) { seedMask[it] && distIn[it] >= m(CORE_INSET, photo) }
        if (core.count { it } < 20) for (i in 0 until n) core[i] = seedMask[i]
        val others = BooleanArray(n)
        for (j in 0 until n) {
            val gi = (y0 + j / ww) * w + (x0 + j % ww)
            others[j] = owner[gi] != 0 && owner[gi] != hold.id
        }
        val othersNear = dilate(others, ww, wh, m(OTHER_GAP, photo))
        fun global(j: Int) = (y0 + j / ww) * w + (x0 + j % ww)
        val holdLab = medianLab(lab, (0 until n).filter { core[it] }.map { global(it) }.toIntArray())
        var ringIdx = (0 until n).filter { j -> !seedMask[j] && distOut[j] > m(NEAR, photo) && distOut[j] <= m(FAR, photo) && !othersNear[j] }
        if (ringIdx.size < 50) ringIdx = (0 until n).filter { j -> !seedMask[j] && distOut[j] > m(NEAR, photo) && distOut[j] <= m(FAR, photo) }
        val wallLab = medianLab(lab, ringIdx.map { global(it) }.toIntArray())
        val sep = dist(holdLab, wallLab)
        if (sep < MIN_SEPARATION) return null
        val tol = (0.45f * sep).coerceIn(6f, 22f)
        val cand = BooleanArray(n)
        for (j in 0 until n) {
            val gi = global(j) * 3
            val px = floatArrayOf(lab[gi], lab[gi + 1], lab[gi + 2])
            val dHold = dist(px, holdLab); val dWall = dist(px, wallLab)
            cand[j] = dHold < tol && dHold < dWall && !othersNear[j]
        }
        var mask = erode(dilate(cand, ww, wh, 2), ww, wh, 2)
        mask = dilate(erode(mask, ww, wh, 1), ww, wh, 1)
        for (j in 0 until n) if (core[j]) mask[j] = true
        val region = componentsTouching(mask, core, ww, wh)
        fillHoles(region, ww, wh)
        var opened = dilate(erode(region, ww, wh, 2), ww, wh, 2)
        val band = m(BAND, photo)
        for (j in 0 until n) opened[j] = (opened[j] && (seedMask[j] || distOut[j] <= band)) || core[j]
        val full = BooleanArray(w * h)
        for (j in 0 until n) if (opened[j]) full[global(j)] = true
        return outlineOf(full, w, h, photo, 1)
    }

    /** Pixels of the orthophoto inside the hold's depth outline (or its size ellipse); empty when off the photo. */
    private fun rasterSeed(hold: Hold, photo: Orthophoto): IntArray =
        Raster.fillPolygon(if (hold.outline.size >= 6) hold.outline else Raster.ellipse(hold.u, hold.v, hold.width, hold.tall), photo)

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }

    /** sRGB → CIE Lab (D65) on OpenCV's 8-bit scale, L pre-weighted by [L_WEIGHT]; 3 floats per pixel. */
    private fun toLab(image: Image): FloatArray {
        val out = FloatArray(image.width * image.height * 3)
        val lin = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
        fun f(t: Float): Float = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
        for (i in 0 until image.width * image.height) {
            val p = image.pixels[i]
            val r = lin[p shr 16 and 0xff]; val g = lin[p shr 8 and 0xff]; val b = lin[p and 0xff]
            val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
            val fy = f(y)
            out[i * 3] = (116f * fy - 16f) * 2.55f * L_WEIGHT
            out[i * 3 + 1] = 500f * (f(x) - fy)
            out[i * 3 + 2] = 200f * (fy - f(z))
        }
        return out
    }

    /** Chamfer (3-4) distance in pixels: for outside pixels to the set, or for inside pixels to its edge. */
    private fun distanceToSet(set: BooleanArray, w: Int, h: Int, inside: Boolean): FloatArray {
        val big = 1e6f
        val d = FloatArray(w * h) { if (set[it] != inside) 0f else big }
        if (inside) for (i in d.indices) { val x = i % w; val y = i / w; if (set[i] && (x == 0 || y == 0 || x == w - 1 || y == h - 1)) d[i] = 3f }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var best = d[i]
            if (x > 0) best = min(best, d[i - 1] + 3f)
            if (y > 0) { best = min(best, d[i - w] + 3f); if (x > 0) best = min(best, d[i - w - 1] + 4f); if (x < w - 1) best = min(best, d[i - w + 1] + 4f) }
            d[i] = best
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var best = d[i]
            if (x < w - 1) best = min(best, d[i + 1] + 3f)
            if (y < h - 1) { best = min(best, d[i + w] + 3f); if (x < w - 1) best = min(best, d[i + w + 1] + 4f); if (x > 0) best = min(best, d[i + w - 1] + 4f) }
            d[i] = best
        }
        for (i in d.indices) d[i] /= 3f
        return d
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val out = BooleanArray(src.size)
        val r2 = r * r
        for (y in 0 until h) for (x in 0 until w) {
            if (!src[y * w + x]) continue
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r2) continue
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) out[ny * w + nx] = true
            }
        }
        return out
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(src.size) { !src[it] }
        val grown = dilate(inv, w, h, r)
        return BooleanArray(src.size) { !grown[it] }
    }

    private fun componentsTouching(mask: BooleanArray, core: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var tail = 0
        for (j in mask.indices) if (core[j] && mask[j]) { out[j] = true; queue[tail++] = j }
        var head = 0
        while (head < tail) {
            val j = queue[head++]
            val x = j % w; val y = j / w
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val k = ny * w + nx
                if (mask[k] && !out[k]) { out[k] = true; queue[tail++] = k }
            }
        }
        return out
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val label = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var best = 0; var bestSize = 0; var next = 0
        for (s in mask.indices) {
            if (!mask[s] || label[s] != 0) continue
            val id = ++next
            var head = 0; var tail = 0
            queue[tail++] = s; label[s] = id
            while (head < tail) {
                val j = queue[head++]
                val x = j % w; val y = j / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val k = ny * w + nx
                    if (mask[k] && label[k] == 0) { label[k] = id; queue[tail++] = k }
                }
            }
            if (tail > bestSize) { bestSize = tail; best = id }
        }
        return BooleanArray(mask.size) { label[it] == best }
    }

    /** Background reachable from the border stays background; enclosed holes become region. */
    private fun fillHoles(region: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(region.size)
        val queue = IntArray(region.size)
        var tail = 0
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) { val j = y * w + x; if (!region[j] && !outside[j]) { outside[j] = true; queue[tail++] = j } }
        for (y in 0 until h) for (x in intArrayOf(0, w - 1)) { val j = y * w + x; if (!region[j] && !outside[j]) { outside[j] = true; queue[tail++] = j } }
        var head = 0
        while (head < tail) {
            val j = queue[head++]
            val x = j % w; val y = j / w
            if (x > 0 && !region[j - 1] && !outside[j - 1]) { outside[j - 1] = true; queue[tail++] = j - 1 }
            if (x < w - 1 && !region[j + 1] && !outside[j + 1]) { outside[j + 1] = true; queue[tail++] = j + 1 }
            if (y > 0 && !region[j - w] && !outside[j - w]) { outside[j - w] = true; queue[tail++] = j - w }
            if (y < h - 1 && !region[j + w] && !outside[j + w]) { outside[j + w] = true; queue[tail++] = j + w }
        }
        for (j in region.indices) if (!region[j] && !outside[j]) region[j] = true
    }
}
