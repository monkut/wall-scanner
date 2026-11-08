package io.github.monkut.wallscanner.core

import kotlin.math.max
import kotlin.math.min

/**
 * Holds the depth did not find, found in the orthophoto instead: colour patches ([ColorPatches]) that no
 * detected hold covers. Depth misses flat footholds (under its 2 cm floor), holds mounted on volumes, and
 * anything in a corner the sweep skipped; the keyframes still photographed them. A patch becomes a hold
 * when it is at least [MIN_SIDE] across, not a strip of tape, not below the wall surface's bottom edge (crash
 * pads), and not already covered. Its height is the depth relief under it, zero when the scan has none, and
 * its voxel count is zero, which marks it as image-only.
 */
object HoldSearch {
    private const val MIN_SIDE = 0.04f
    const val SECOND_MIN_SIDE = 0.06f // the search after SAM's outlines reports holds at least this across both ways: what the final outlines uncover at the edges is mostly paint (a 4 × 14 cm strip on 1F)
    private const val MAX_SIDE = 1.0f
    private const val MAX_COVERED = 0.3f // patches already this covered by a hold are that hold …
    private const val MIN_REMNANT = 0.2f // … unless the uncovered remainder is this much of the patch, compact, and hold-sized: a
    private const val REMNANT_FILL = 0.5f // second hold of the same colour that the gap bridging joined to the first (#29 C) …
    private const val HALO_M = 0.02f     // … once the halo this close around the covering outlines is counted as covered too (on 1F the
    // lower red volume is 28% of a patch that also holds the upper volume and the trapezoid above it)
    private const val PAD_RELIEF = 0.12f // crash pads stand this far off the wall (median of the patch: a volume on the mat line touches it only along its bottom rows, #29 C) …
    private const val PAD_ZONE = 0.35f // … within this height of the wall's bottom edge

    fun find(holds: List<Hold>, patches: ColorPatches, photo: Orthophoto, surface: WallSurface, extent: FloatArray, minSide: Float = MIN_SIDE): List<Hold> {
        val w = photo.image.width; val h = photo.image.height
        val covered = BooleanArray(w * h)
        for (hold in holds) for (i in Raster.fillPolygon(ImageHold.polygon(hold), photo)) covered[i] = true
        // A hold's halo (its tint and shadow just outside the outline) joins the remainder of its patch to the
        // next hold's; counted as covered, it leaves the second hold standing on its own.
        val near = SamRefine.dilateSquare(covered, w, h, max(1, (HALO_M / photo.px + 0.5f).toInt()))
        val minSidePx = max(2, (minSide / photo.px).toInt())
        val found = ArrayList<Hold>()
        var nextId = (holds.maxOfOrNull { it.id } ?: 0) + 1
        for (p in 1..patches.count) {
            val count = patches.size[p]
            if (count < minSidePx * minSidePx) continue
            val longM = max(patches.maxX[p] - patches.minX[p], patches.maxY[p] - patches.minY[p]).plus(1) * photo.px
            val shortM = min(patches.maxX[p] - patches.minX[p], patches.maxY[p] - patches.minY[p]).plus(1) * photo.px
            if (shortM < minSide || longM > MAX_SIDE) continue
            val members = patches.members(p)
            if (members.count { covered[it] } >= MAX_COVERED * count) {
                // The patch is a found hold and whatever its colour bridged to within HOLD_GAP_M: on 1F two red
                // volumes 2 cm apart, the lower one unseen by depth (#29 C). A hold's own halo outside its outline
                // is a thin ring; a second hold is a compact remainder of the patch's own size.
                for (rem in remnants(members, near, w)) {
                    if (rem.size < MIN_REMNANT * count || rem.size < minSidePx * minSidePx) continue
                    var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
                    for (i in rem) { val x = i % w; val y = i / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
                    val long = max(x1 - x0, y1 - y0).plus(1) * photo.px
                    val short = min(x1 - x0, y1 - y0).plus(1) * photo.px
                    if (short < minSide || long > MAX_SIDE || rem.size < REMNANT_FILL * (x1 - x0 + 1) * (y1 - y0 + 1)) continue
                    if (!plausible(rem, rem.size, photo, extent)) continue
                    found.add(ImageHold.fromPixels(nextId++, rem, 0, photo, surface))
                }
                continue
            }
            if (!plausible(members, count, photo, extent)) continue
            found.add(ImageHold.fromPixels(nextId++, members, 0, photo, surface))
        }
        return found
    }

    /** 4-connected groups of the [members] pixels that no hold covers, in a [w] pixel wide image. */
    private fun remnants(members: IntArray, covered: BooleanArray, w: Int): List<IntArray> {
        val free = HashSet<Int>()
        for (i in members) if (!covered[i]) free.add(i)
        val out = ArrayList<IntArray>()
        val queue = ArrayList<Int>()
        while (free.isNotEmpty()) {
            queue.clear()
            val seed = free.first(); free.remove(seed); queue.add(seed)
            var head = 0
            while (head < queue.size) {
                val i = queue[head++]
                val x = i % w
                if (x > 0 && free.remove(i - 1)) queue.add(i - 1)
                if (x < w - 1 && free.remove(i + 1)) queue.add(i + 1)
                if (free.remove(i - w)) queue.add(i - w)
                if (free.remove(i + w)) queue.add(i + w)
            }
            out.add(queue.toIntArray())
        }
        return out
    }

    /**
     * Whether [count] orthophoto pixels [members] can be a hold: not a strip of tape (told by its size along
     * its own axes: a slanted strip has a square bounding box), not below the wall surface's bottom edge
     * (crash pads lie there), and not the top of a crash pad leaning on the wall.
     */
    fun plausible(members: IntArray, count: Int, photo: Orthophoto, extent: FloatArray): Boolean {
        val w = photo.image.width
        val (_, my, length, width) = TapeMarks.shape(members, count, w, photo.px)
        if (TapeMarks.isStrip(length, width)) return false
        val cv = photo.v(my + 0.5f)
        if (cv < extent[1]) return false
        // The pad's top stands off the wall over its whole patch; a volume on the mat line only along its bottom rows.
        val rel = FloatArray(count) { photo.relief[members[it]] }
        rel.sort()
        return !(rel[count / 2] > PAD_RELIEF && cv < extent[1] + PAD_ZONE)
    }
}

/**
 * Tape marks starts: a rectangle of tape from a hold down to the route's white placard means that hold is a
 * start hold. The tape's colour is the grade, not the hold colour, so it may be grey or black; two strips
 * leaving one placard mark a two-hold start. Placard-anchored tape ([markFromPlacards]) is found first and
 * its colour is recorded on the hold ([Hold.tape]). On a wall with no placard at all, a thin strip of
 * coloured tape still counts (once one placard start is found, the wall marks starts that way and the strip
 * search, which mistakes shaded hold edges for tape on a busy wall, is skipped). A strip pixel is saturated or tinted with a hue unlike
 * the wall's; strips are what is left of such pixels after an opening removes anything wider than tape,
 * 4-connected, with a tape shape along their own axis ([isStrip]: [MIN_SHORT]..[MAX_SHORT] wide, [MIN_LONG]..
 * [MAX_LONG] long, hanging within [STRIP_MAX_TILT] of vertical, filling most of their length × width), whose
 * lower half hangs in clear wall (at most [MAX_CROWD] of the pixels within [CROWD_M] of it are coloured),
 * outside every hold and away from the scan's edges. The hold
 * whose outline comes within [REACH] of the strip's top end, centred above it, gets the START role unless a
 * role was already set.
 */
object TapeMarks {
    private const val MIN_LONG = 0.05f
    private const val MAX_LONG = 0.4f
    private const val MIN_SHORT = 0.006f
    private const val MAX_SHORT = 0.05f // tape plus the blur of a 5 mm orthophoto
    private const val ASPECT = 2f // a short strip blurred to 4–5 cm wide is still tape
    private const val MIN_FILL = 0.45f // a straight strip fills its length × width; a shadow's edge does not
    private const val STRIP_MAX_TILT = 50.0 // degrees from vertical
    private const val CROWD_M = 0.015f
    private const val MAX_CROWD = 0.3f
    private const val REACH = 0.06f // tape starts at the hold's edge
    private const val SIDE_SLACK = 0.03f
    private const val OPEN_M = 0.025f // tape up to ~4 cm wide survives the opening; holds (≥ 6 cm) do not
    private const val MIN_SAT = 0.45f
    private const val MIN_VAL = 0.15f
    private const val TINT_SAT = 0.12f
    private const val TINT_HUE = 25f
    private const val EDGE_MARGIN = 0.05f
    private const val PLACARD_SAT = 0.15f // a white card: unsaturated and bright
    private const val PLACARD_VAL = 0.7f
    private const val PLACARD_MIN_W = 0.05f
    private const val PLACARD_MAX_W = 0.25f
    private const val PLACARD_MIN_H = 0.025f
    private const val PLACARD_MAX_H = 0.1f
    private const val PLACARD_FILL = 0.5f // of the card's box, its enclosed holes (grade text, a coloured header) counted as card
    private const val PLACARD_GAP = 0.015f // tape is traced from this far above the card, clear of its blurred edge
    private const val MIN_TAPE = 0.02f
    private const val TAPE_COLOR_TOL = 48f // RGB distance from the tape's colour where it leaves the card

    fun isStrip(longM: Float, shortM: Float): Boolean =
        longM in MIN_LONG..MAX_LONG && shortM in MIN_SHORT..MAX_SHORT && longM / shortM >= ASPECT

    /**
     * Marks start holds from tape strips on the orthophoto; returns how many holds were marked. Pixels inside
     * a hold outline or inside a hold-sized colour patch of [patches] (a hold the shapes did not fully cover)
     * are never tape.
     */
    fun markStarts(holds: List<Hold>, photo: Orthophoto, wall: WallColor?, patches: ColorPatches? = null, extent: FloatArray? = null): Int {
        val w = photo.image.width; val h = photo.image.height
        val n = w * h
        val pixels = photo.image.pixels
        val holdAt = IntArray(n) { -1 }
        holds.forEachIndexed { k, hold -> for (i in Raster.fillPolygon(ImageHold.polygon(hold), photo)) holdAt[i] = k }
        val inHold = BooleanArray(n) { holdAt[it] >= 0 }
        if (patches != null) {
            // Hold-sized along the patch's own axes: a slanted strip of dark tape has a square bounding box.
            val holdSized = BooleanArray(patches.count + 1) { p -> p > 0 && shape(patches.members(p), patches.size[p], w, photo.px)[3] > MAX_SHORT }
            for (i in 0 until n) if (holdSized[patches.label[i]]) inHold[i] = true
        }
        // Tape is coloured (gyms tape in the route's colour) and 1–2 cm wide, so at 5 mm per pixel it blends with
        // the wall and loses saturation: a pixel counts when it is saturated, or tinted with a hue unlike the wall's.
        // Dark and grey pixels do not count: a hold's shadow or chalky underside would otherwise swallow the strip.
        val tinted = BooleanArray(n)
        val eligible = BooleanArray(n)
        val white = BooleanArray(n)
        val offWall = BooleanArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val c = ColorUtil.rgbToHsv(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
            if (wall != null && wall.matches(c)) continue
            offWall[i] = true
            white[i] = c[1] <= PLACARD_SAT && c[2] >= PLACARD_VAL
            val offHue = wall != null && wall.sat >= Tuning.WALL_GREY_SAT && c[1] >= TINT_SAT && ColorUtil.hueDistance(c[0], wall.hue) > TINT_HUE
            tinted[i] = c[2] >= MIN_VAL && (offHue || c[1] >= MIN_SAT) // near-black shadows carry noise hues
            eligible[i] = tinted[i] && !inHold[i]
        }
        // Without a wall colour every pixel is off the wall, and tape cannot be told apart from it.
        var marked = if (wall != null) markFromPlacards(holds, photo, holdAt, inHold, white, offWall) else 0
        // A wall with placards marks every start that way; the strip search below is for walls without them,
        // and on a busy wall it mistakes a hold's shaded edge for tape.
        if (marked > 0) return marked
        // Tape is thin: what survives an opening of OPEN_M is a hold's shadow or an uncovered hold part, not tape.
        val openPx = max(1, (OPEN_M / photo.px + 0.5f).toInt())
        val thick = open(eligible, w, h, openPx)
        for (i in 0 until n) if (thick[i]) eligible[i] = false
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        for (seed in 0 until n) {
            if (!eligible[seed] || seen[seed]) continue
            var head = 0; var tail = 0
            queue[tail++] = seed; seen[seed] = true
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (k in 0 until 4) {
                    val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0); val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (seen[j] || !eligible[j]) continue
                    seen[j] = true; queue[tail++] = j
                }
            }
            // Shape along the strip's own axis (tape often hangs at an angle); the axis must point within
            // STRIP_MAX_TILT of vertical.
            val (mx, my, length, width, tilt) = shape(queue, tail, w, photo.px)
            if (!isStrip(length, width)) continue
            if (tilt > STRIP_MAX_TILT) continue
            if (tail < MIN_FILL * (length / photo.px) * (width / photo.px)) continue
            // The lower half of real tape hangs in clear wall; a hold's tinted edge or shadow runs along colour.
            val rows = IntArray(tail) { queue[it] / w }; rows.sort()
            val midRow = rows[tail / 2]
            val band = HashSet<Int>()
            val crowdR = max(1, (CROWD_M / photo.px + 0.5f).toInt())
            for (q in 0 until tail) {
                val i = queue[q]
                if (i / w < midRow) continue
                val x = i % w; val y = i / w
                for (dy in -crowdR..crowdR) for (dx in -crowdR..crowdR) {
                    if (dx * dx + dy * dy > crowdR * crowdR) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) band.add(ny * w + nx)
                }
            }
            for (q in 0 until tail) band.remove(queue[q])
            if (band.isEmpty() || band.count { tinted[it] } > MAX_CROWD * band.size) continue
            if (extent != null) {
                // Strips at the scan's edge are the side of the wall or a mat seam, not tape.
                val cu = photo.u(mx + 0.5f); val cv = photo.v(my + 0.5f)
                if (cu < extent[0] + EDGE_MARGIN || cu > extent[2] - EDGE_MARGIN || cv < extent[1] + EDGE_MARGIN || cv > extent[3] - EDGE_MARGIN) continue
            }
            // Top end of the strip (its highest pixels) and the nearest hold whose outline comes within REACH, centred above it.
            var sx = 0; var c = 0
            for (q in 0 until tail) if (queue[q] / w <= y0 + 1) { sx += queue[q] % w; c++ }
            val u = photo.u(sx.toFloat() / c + 0.5f); val v = photo.v(y0.toFloat())
            // The hold the tape hangs from: centred above the strip's top, the strip within the hold's width, outline within REACH.
            val hold = holds.filter { it.v > v && u >= it.u - it.width / 2 - SIDE_SLACK && u <= it.u + it.width / 2 + SIDE_SLACK }
                .minByOrNull { distanceToOutline(it, u, v) }?.takeIf { distanceToOutline(it, u, v) <= REACH } ?: continue
            if (hold.role == HoldRole.ANY) { hold.role = HoldRole.START; marked++ }
        }
        return marked
    }

    /**
     * Placards are 4-connected [white] components of card size. From [PLACARD_GAP] above each card's top edge,
     * tape is grown upward through off-wall pixels, any colour, up to [MAX_LONG]; each growth that stays
     * tape-wide and ends on a hold marks that hold START and records the tape's median colour on it. [stop]
     * pixels (holds and hold-sized patches) bound the card; only hold outlines ([holdAt]) end the tape, since
     * tape joins the card's coloured header into a hold-sized patch (Oct 29 5:24 PM: 35 × 13 cm). The tape's
     * colour, width and [MAX_LONG] bound the growth instead.
     */
    private fun markFromPlacards(holds: List<Hold>, photo: Orthophoto, holdAt: IntArray, stop: BooleanArray, white: BooleanArray, offWall: BooleanArray): Int {
        val w = photo.image.width; val h = photo.image.height
        val n = w * h
        val pixels = photo.image.pixels
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        val tapeSeen = BooleanArray(n)
        val tape = IntArray(n)
        val gapPx = max(1, (PLACARD_GAP / photo.px + 0.5f).toInt())
        val longPx = (MAX_LONG / photo.px).toInt()
        val maxTapePx = (2 * MAX_LONG * MAX_SHORT / (photo.px * photo.px)).toInt() // larger growths leaked into something that is not tape
        var marked = 0
        for (seed in 0 until n) {
            if (!white[seed] || seen[seed] || stop[seed]) continue
            var head = 0; var tail = 0
            queue[tail++] = seed; seen[seed] = true
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (k in 0 until 4) {
                    val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0); val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (seen[j] || !white[j] || stop[j]) continue
                    seen[j] = true; queue[tail++] = j
                }
            }
            val cardW = (x1 - x0 + 1) * photo.px; val cardH = (y1 - y0 + 1) * photo.px
            if (cardW !in PLACARD_MIN_W..PLACARD_MAX_W || cardH !in PLACARD_MIN_H..PLACARD_MAX_H || cardW < cardH) continue
            if (tail + enclosed(queue, tail, x0, y0, x1, y1, w) < PLACARD_FILL * (x1 - x0 + 1) * (y1 - y0 + 1)) continue
            val top = y0 - gapPx
            if (top < 0) continue
            val minY = max(0, top - longPx)
            for (sx in x0..x1) {
                val s = top * w + sx
                if (tapeSeen[s] || !offWall[s] || white[s] || holdAt[s] >= 0) continue
                // Tape is one even colour: the run of off-wall pixels where it leaves the card sets it, which keeps
                // the growth out of the hold's shadow beside it.
                var th = 0; var tt = 0
                var ex = sx
                while (ex <= x1 && offWall[top * w + ex] && !white[top * w + ex] && holdAt[top * w + ex] < 0) { tape[tt++] = top * w + ex; tapeSeen[top * w + ex] = true; ex++ }
                val ref = medianColor(pixels, tape, tt)
                val contacts = HashMap<Int, Int>()
                while (th < tt && tt < maxTapePx) {
                    val i = tape[th++]
                    val x = i % w; val y = i / w
                    for (k in 0 until 4) {
                        val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0); val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                        if (nx !in 0 until w || ny !in minY..top) continue
                        val j = ny * w + nx
                        if (holdAt[j] >= 0) { contacts.merge(holdAt[j], 1, Int::plus); continue }
                        if (tapeSeen[j] || !offWall[j] || white[j] || colorDistance(pixels[j], ref) > TAPE_COLOR_TOL) continue
                        tapeSeen[j] = true; tape[tt++] = j
                    }
                }
                if (tt >= maxTapePx) continue
                val (_, _, length, width) = shape(tape, tt, w, photo.px)
                if (length < MIN_TAPE || width > MAX_SHORT) continue
                // The hold the tape runs into; a smoothed outline may stop just short of the tape's top end.
                val hold = contacts.maxByOrNull { it.value }?.let { holds[it.key] } ?: run {
                    var ty = h; for (q in 0 until tt) ty = minOf(ty, tape[q] / w)
                    var sxSum = 0; var c = 0
                    for (q in 0 until tt) if (tape[q] / w <= ty + 1) { sxSum += tape[q] % w; c++ }
                    val u = photo.u(sxSum.toFloat() / c + 0.5f); val v = photo.v(ty.toFloat())
                    holds.filter { it.v > v }.minByOrNull { distanceToOutline(it, u, v) }?.takeIf { distanceToOutline(it, u, v) <= REACH }
                } ?: continue
                hold.tape = medianColor(pixels, tape, tt)
                if (hold.role == HoldRole.ANY) { hold.role = HoldRole.START; marked++ }
            }
        }
        return marked
    }

    /** Centre (x, y), length, width (metres, from second moments) and tilt from vertical (degrees) of [count] pixel indices. */
    internal fun shape(idx: IntArray, count: Int, w: Int, px: Float): FloatArray {
        var mx = 0.0; var my = 0.0
        for (q in 0 until count) { mx += idx[q] % w; my += idx[q] / w }
        mx /= count; my /= count
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in 0 until count) { val dx = idx[q] % w - mx; val dy = idx[q] / w - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        sxx /= count; syy /= count; sxy /= count
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val disc = Math.sqrt(Math.max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = Math.max(0.0, tr / 2 - disc)
        val axisX = if (Math.abs(sxy) > 1e-9) l1 - syy else (if (sxx >= syy) 1.0 else 0.0)
        val axisY = if (Math.abs(sxy) > 1e-9) sxy else (if (sxx >= syy) 0.0 else 1.0)
        val tilt = Math.toDegrees(Math.atan2(Math.abs(axisX), Math.abs(axisY)))
        return floatArrayOf(mx.toFloat(), my.toFloat(), (Math.sqrt(12 * l1) * px).toFloat(), (Math.sqrt(12 * l2) * px).toFloat(), tilt.toFloat())
    }

    /** Pixels of the box [x0]..[x1] × [y0]..[y1] that the component ([count] indices of [idx]) encloses: not reachable from the box's border without crossing it. */
    private fun enclosed(idx: IntArray, count: Int, x0: Int, y0: Int, x1: Int, y1: Int, w: Int): Int {
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val card = BooleanArray(bw * bh)
        for (q in 0 until count) card[(idx[q] / w - y0) * bw + idx[q] % w - x0] = true
        val outside = BooleanArray(bw * bh)
        val stack = ArrayDeque<Int>()
        for (i in 0 until bw * bh) {
            val x = i % bw; val y = i / bw
            if ((x == 0 || y == 0 || x == bw - 1 || y == bh - 1) && !card[i]) { outside[i] = true; stack.add(i) }
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); val x = i % bw; val y = i / bw
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < bw - 1) i + 1 else -1, if (y > 0) i - bw else -1, if (y < bh - 1) i + bw else -1)) {
                if (j >= 0 && !card[j] && !outside[j]) { outside[j] = true; stack.add(j) }
            }
        }
        return (0 until bw * bh).count { !card[it] && !outside[it] }
    }

    /** Per-channel median of [count] pixels, as 0xRRGGBB. */
    private fun medianColor(pixels: IntArray, idx: IntArray, count: Int): Int {
        fun channel(shift: Int): Int { val a = IntArray(count) { pixels[idx[it]] shr shift and 0xff }; a.sort(); return a[count / 2] }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Euclidean RGB distance (0..441) between two 0xRRGGBB colours. */
    private fun colorDistance(a: Int, b: Int): Float {
        val dr = (a shr 16 and 0xff) - (b shr 16 and 0xff); val dg = (a shr 8 and 0xff) - (b shr 8 and 0xff); val db = (a and 0xff) - (b and 0xff)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    /** Morphological opening with a disc of radius [r] pixels. */
    private fun open(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val r2 = r * r
        val eroded = BooleanArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!src[y * w + x]) continue
            var all = true
            loop@ for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r2) continue
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h || !src[ny * w + nx]) { all = false; break@loop }
            }
            eroded[y * w + x] = all
        }
        val out = BooleanArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!eroded[y * w + x]) continue
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r2) continue
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) out[ny * w + nx] = true
            }
        }
        return out
    }


    private fun distanceToOutline(hold: Hold, u: Float, v: Float): Float {
        val poly = ImageHold.polygon(hold)
        var best = Float.MAX_VALUE
        for (i in 0 until poly.size / 2) {
            val du = poly[i * 2] - u; val dv = poly[i * 2 + 1] - v
            val d = du * du + dv * dv
            if (d < best) best = d
        }
        return kotlin.math.sqrt(best)
    }
}

/** Polygon rasterisation on an orthophoto's pixel grid. */
object Raster {
    /** Pixel indices inside the closed (u, v) polygon [poly] (interleaved), even-odd rule. */
    fun fillPolygon(poly: FloatArray, photo: Orthophoto): IntArray {
        val w = photo.image.width; val h = photo.image.height
        val n = poly.size / 2
        if (n < 3) return IntArray(0)
        val xs = FloatArray(n) { photo.x(poly[it * 2]) }; val ys = FloatArray(n) { photo.y(poly[it * 2 + 1]) }
        val minY = max(0, ys.min().toInt()); val maxY = min(h - 1, ys.max().toInt() + 1)
        val out = ArrayList<Int>()
        val crossings = FloatArray(n)
        for (y in minY..maxY) {
            val yc = y + 0.5f
            var c = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                if ((ys[i] <= yc) == (ys[j] <= yc)) continue
                crossings[c++] = xs[i] + (yc - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])
            }
            java.util.Arrays.sort(crossings, 0, c)
            var i = 0
            while (i + 1 < c) {
                val xa = max(0, (crossings[i] + 0.5f).toInt()); val xb = min(w - 1, (crossings[i + 1] - 0.5f).toInt())
                for (x in xa..xb) out.add(y * w + x)
                i += 2
            }
        }
        return out.toIntArray()
    }

    /** 32-point ellipse centred on (u, v) with the given extents, as an interleaved (u, v) polygon. */
    fun ellipse(u: Float, v: Float, width: Float, tall: Float): FloatArray {
        val pts = FloatArray(64)
        for (i in 0 until 32) {
            val a = i / 32.0 * 2 * Math.PI
            pts[i * 2] = u + (width / 2 * Math.cos(a)).toFloat(); pts[i * 2 + 1] = v + (tall / 2 * Math.sin(a)).toFloat()
        }
        return pts
    }
}
