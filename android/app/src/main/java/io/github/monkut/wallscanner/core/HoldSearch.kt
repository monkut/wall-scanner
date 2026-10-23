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
    private const val MAX_SIDE = 1.0f
    private const val MAX_COVERED = 0.3f // patches already this covered by a hold are that hold
    private const val PAD_RELIEF = 0.12f // crash pads stand this far off the wall …
    private const val PAD_ZONE = 0.35f // … within this height of the wall's bottom edge
    private const val MAX_POINTS = 96

    fun find(holds: List<Hold>, patches: ColorPatches, photo: Orthophoto, frame: WallFrame, extent: FloatArray): List<Hold> {
        val w = photo.image.width; val h = photo.image.height
        val n = w * h
        val pixels = photo.image.pixels
        val covered = BooleanArray(n)
        for (hold in holds) for (i in Raster.fillPolygon(hold.outline.takeIf { it.size >= 6 } ?: Raster.ellipse(hold.u, hold.v, hold.width, hold.tall), photo)) covered[i] = true
        val minSidePx = max(2, (MIN_SIDE / photo.px).toInt())
        val found = ArrayList<Hold>()
        var nextId = (holds.maxOfOrNull { it.id } ?: 0) + 1
        for (p in 1..patches.count) {
            val count = patches.size[p]
            if (count < minSidePx * minSidePx) continue
            val (long, short) = patches.sides(p)
            val longM = long * photo.px; val shortM = short * photo.px
            if (longM < MIN_SIDE || longM > MAX_SIDE) continue
            if (TapeMarks.isStrip(longM, shortM)) continue
            val members = IntArray(count)
            var k = 0
            for (y in patches.minY[p]..patches.maxY[p]) for (x in patches.minX[p]..patches.maxX[p]) {
                val i = y * w + x
                if (patches.label[i] == p) members[k++] = i
            }
            var coveredCount = 0; var sr = 0L; var sg = 0L; var sb = 0L
            var su = 0.0; var sv = 0.0
            for (i in members) {
                if (covered[i]) coveredCount++
                val px = pixels[i]
                sr += px shr 16 and 0xff; sg += px shr 8 and 0xff; sb += px and 0xff
                su += photo.u(i % w + 0.5f); sv += photo.v(i / w + 0.5f)
            }
            if (coveredCount >= MAX_COVERED * count) continue
            val cu = (su / count).toFloat(); val cv = (sv / count).toFloat()
            if (cv < extent[1]) continue // the wall surface's lowest edge; crash pads lie below it
            val rel = FloatArray(count) { photo.relief[members[it]] }
            rel.sort()
            val height = max(0f, rel[(count * 9) / 10])
            if (height > PAD_RELIEF && cv < extent[1] + PAD_ZONE) continue // the top of a crash pad leaning on the wall
            val cells = IntArray(count) { it }; val cxs = IntArray(count) { members[it] % w }; val cys = IntArray(count) { (h - 1) - members[it] / w }
            val outline = HoldOutline.trace(cells, cxs, cys, photo.px, MAX_POINTS, 3)
            for (i in 0 until outline.size / 2) { outline[i * 2] += photo.minU; outline[i * 2 + 1] += photo.maxV - h * photo.px }
            val pl = frame.plane
            found.add(
                Hold(
                    id = nextId++,
                    cx = pl.ox + cu * frame.rx + cv * frame.ux + height * pl.nx,
                    cy = pl.oy + cu * frame.ry + cv * frame.uy + height * pl.ny,
                    cz = pl.oz + cu * frame.rz + cv * frame.uz + height * pl.nz,
                    u = cu, v = cv, width = (patches.maxX[p] - patches.minX[p] + 1) * photo.px, tall = (patches.maxY[p] - patches.minY[p] + 1) * photo.px, height = height,
                    r = (sr / count).toInt(), g = (sg / count).toInt(), b = (sb / count).toInt(),
                    voxels = 0, outline = outline,
                )
            )
        }
        return found
    }
}

/**
 * Tape marks starts: a rectangle of tape from a hold down to the route's white placard means that hold is a
 * start hold. The tape's colour is the grade, not the hold colour, so it may be grey or black; two strips
 * leaving one placard mark a two-hold start. Placard-anchored tape ([markFromPlacards]) is found first and
 * its colour is recorded on the hold ([Hold.tape]). Without a placard, a thin strip of coloured tape still
 * counts. A strip pixel is saturated or tinted with a hue unlike
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
    private const val PLACARD_FILL = 0.5f // the grade text leaves holes in the card
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
        holds.forEachIndexed { k, hold -> for (i in Raster.fillPolygon(polygon(hold), photo)) holdAt[i] = k }
        val inHold = BooleanArray(n) { holdAt[it] >= 0 }
        if (patches != null) {
            val holdSized = BooleanArray(patches.count + 1) { p -> p > 0 && patches.sides(p).second * photo.px > MAX_SHORT }
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
            val offHue = wall != null && wall.sat >= Tuning.WALL_GREY_SAT && c[1] >= TINT_SAT &&
                kotlin.math.abs(((c[0] - wall.hue) % 360f + 540f) % 360f - 180f) > TINT_HUE
            tinted[i] = c[2] >= MIN_VAL && (offHue || c[1] >= MIN_SAT) // near-black shadows carry noise hues
            eligible[i] = tinted[i] && !inHold[i]
        }
        // Without a wall colour every pixel is off the wall, and tape cannot be told apart from it.
        var marked = if (wall != null) markFromPlacards(holds, photo, holdAt, inHold, white, offWall) else 0
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
     * pixels (holds and hold-sized patches) end the growth; [holdAt] says which hold was reached.
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
            if (tail < PLACARD_FILL * (x1 - x0 + 1) * (y1 - y0 + 1)) continue
            val top = y0 - gapPx
            if (top < 0) continue
            val minY = max(0, top - longPx)
            for (sx in x0..x1) {
                val s = top * w + sx
                if (tapeSeen[s] || !offWall[s] || white[s] || stop[s]) continue
                // Tape is one even colour: the run of off-wall pixels where it leaves the card sets it, which keeps
                // the growth out of the hold's shadow beside it.
                var th = 0; var tt = 0
                var ex = sx
                while (ex <= x1 && offWall[top * w + ex] && !white[top * w + ex] && !stop[top * w + ex]) { tape[tt++] = top * w + ex; tapeSeen[top * w + ex] = true; ex++ }
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
                        if (tapeSeen[j] || !offWall[j] || white[j] || stop[j] || colorDistance(pixels[j], ref) > TAPE_COLOR_TOL) continue
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
    private fun shape(idx: IntArray, count: Int, w: Int, px: Float): FloatArray {
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

    private fun polygon(hold: Hold): FloatArray = hold.outline.takeIf { it.size >= 6 } ?: Raster.ellipse(hold.u, hold.v, hold.width, hold.tall)

    private fun distanceToOutline(hold: Hold, u: Float, v: Float): Float {
        val poly = polygon(hold)
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
