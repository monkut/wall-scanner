package io.github.monkut.wallscanner.core

import io.github.monkut.wallscanner.io.ClipOutcome

/**
 * One analysed frame of a climbing session: image-space heights (0 = top, 1 = bottom of the frame) of the lower
 * foot ([foot], the lowest visible heel, toe or ankle of either side), the hip centre ([hip]) and the lower wrist
 * ([wrist]); [leg] is the hip–ankle length, the scale. Null where the pose model did not see the part (or nobody).
 */
class PoseSample(val tMs: Long, val foot: Float?, val hip: Float?, val wrist: Float?, val leg: Float?)

/** An attempt found in a session recording: [startMs]..[endMs] with pre- and post-roll, and the outcome it looks like. */
class Attempt(val startMs: Long, val endMs: Long, val outcome: ClipOutcome?)

/**
 * Cuts the attempts out of a session recording from the climber's pose: an attempt starts when both feet leave
 * the floor and stays off it, and ends when they are back on it. The floor line is where the feet are lowest
 * (the climber stands there before, between and after attempts); heights are measured in leg lengths so the
 * camera's distance does not matter. Losing the climber does not end an attempt (they leave the top of the frame
 * on a tall wall); the next sight of them on the floor does. Outcome hint: TOP when both wrists stayed at their
 * highest for [TOP_HOLD_MS] before the way down (matched the finish), FALL when the hips dropped faster than
 * [FALL_SPEED] leg lengths per second, else none. A climber stuck at the crux with both hands still, then off, also
 * looks like a top: the outcome is a suggestion the climber confirms. No published method does this from pose alone
 * (CLIMB-VIDEO.md); the thresholds are a first guess to be tuned on filmed sessions.
 */
object AttemptDetector {
    const val LIFT = 0.15f          // feet this many leg lengths above the floor line are off the floor
    const val MIN_OFF_MS = 700L     // … for this long to start an attempt
    const val MIN_ON_MS = 700L      // back on the floor this long ends it
    const val MIN_ATTEMPT_MS = 1_500L // shorter: a hop or a step onto the start hold and back
    const val PRE_ROLL_MS = 2_000L
    const val POST_ROLL_MS = 2_000L
    const val FLOOR_PERCENTILE = 0.9f // the floor line: the median of the lowest tenth of foot heights
    const val TOP_HOLD_MS = 1_000L
    const val TOP_BAND = 0.1f       // wrists within this many leg lengths of their highest count as held there
    const val FALL_SPEED = 2f       // leg lengths per second of hip drop (a 1 m fall averages ~2.5; down-climbing ~0.5–1)

    fun detect(samples: List<PoseSample>, durationMs: Long): List<Attempt> {
        val seen = samples.filter { it.foot != null }.sortedBy { it.tMs }
        if (seen.size < 3) return emptyList()
        val feet = seen.map { it.foot!! }.sorted()
        val floor = feet.subList((feet.size * FLOOR_PERCENTILE).toInt().coerceAtMost(feet.size - 1), feet.size).let { it[it.size / 2] }
        val leg = samples.mapNotNull { it.leg }.sorted().let { if (it.isEmpty()) return emptyList() else it[it.size / 2] }
        if (leg <= 0f) return emptyList()
        fun off(s: PoseSample) = (floor - s.foot!!) / leg >= LIFT
        fun on(s: PoseSample) = (floor - s.foot!!) / leg < LIFT / 2 // hysteresis: halfway back counts as down

        val attempts = ArrayList<Attempt>()
        var i = 0
        while (i < seen.size) {
            // Start: a run of off-the-floor samples lasting MIN_OFF_MS.
            if (!off(seen[i])) { i++; continue }
            var j = i
            while (j + 1 < seen.size && off(seen[j + 1])) j++
            if (seen[j].tMs - seen[i].tMs < MIN_OFF_MS && j + 1 < seen.size) { i = j + 1; continue }
            val lift = seen[i].tMs
            // End: the first run of on-the-floor samples lasting MIN_ON_MS after the lift.
            var k = j + 1
            var down = -1L
            while (k < seen.size) {
                if (on(seen[k])) {
                    var m = k
                    while (m + 1 < seen.size && on(seen[m + 1])) m++
                    if (seen[m].tMs - seen[k].tMs >= MIN_ON_MS || m == seen.size - 1) { down = seen[k].tMs; break }
                    k = m + 1
                } else k++
            }
            val touch = if (down >= 0) down else durationMs
            if (touch - lift >= MIN_ATTEMPT_MS) {
                attempts.add(Attempt(maxOf(0, lift - PRE_ROLL_MS), minOf(durationMs, touch + POST_ROLL_MS), outcome(samples, lift, touch, leg)))
            }
            if (down < 0) break
            i = k
        }
        // Pre-/post-roll of neighbours may overlap; split the gap between them.
        for (a in 1 until attempts.size) {
            val prev = attempts[a - 1]; val cur = attempts[a]
            if (prev.endMs > cur.startMs) {
                val mid = (prev.endMs + cur.startMs) / 2
                attempts[a - 1] = Attempt(prev.startMs, mid, prev.outcome)
                attempts[a] = Attempt(mid, cur.endMs, cur.outcome)
            }
        }
        return attempts
    }

    /** TOP, FALL or null for the samples between the lift and the touch-down (see the class comment). */
    private fun outcome(samples: List<PoseSample>, lift: Long, touch: Long, leg: Float): ClipOutcome? {
        val climb = samples.filter { it.tMs in lift..touch }
        val wrists = climb.filter { it.wrist != null }
        if (wrists.isNotEmpty()) {
            val top = wrists.minOf { it.wrist!! }
            var heldFrom = -1L
            for (s in wrists) {
                if ((s.wrist!! - top) / leg <= TOP_BAND) {
                    if (heldFrom < 0) heldFrom = s.tMs
                    if (s.tMs - heldFrom >= TOP_HOLD_MS) return ClipOutcome.TOP
                } else heldFrom = -1L
            }
        }
        val hips = climb.filter { it.hip != null }
        for (a in hips.indices) for (b in a + 1 until hips.size) {
            val dt = hips[b].tMs - hips[a].tMs
            if (dt > 500) break
            if (dt > 0 && (hips[b].hip!! - hips[a].hip!!) / leg / (dt / 1000f) >= FALL_SPEED) return ClipOutcome.FALL
        }
        return null
    }
}
