package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Motion blur of one camera image from the gyro (#59). [rate] is the mean angular rate over the exposure in device
 * axes (rad/s; the back camera looks down device −Z): turning about X or Y sweeps the whole image by rate × focal
 * length, rolling about Z sweeps its edge by rate × half the width. Returns pixels, or NaN when a value is missing.
 */
object Blur {
    fun px(rate: FloatArray?, exposureNs: Long, fx: Float, width: Int): Float {
        if (rate == null || exposureNs <= 0L) return Float.NaN
        val s = exposureNs / 1e9f
        return s * (hypot(rate[0], rate[1]) * fx + abs(rate[2]) * width / 2f)
    }
}

/**
 * The last [capacity] gyro samples (sensor clock, ns), for the mean rate over one exposure. Thread-safe: the sensor
 * thread adds, the depth thread reads.
 */
class GyroBuffer(private val capacity: Int = 1024) {
    private val t = LongArray(capacity)
    private val xyz = FloatArray(capacity * 3)
    private var next = 0
    private var size = 0

    @Synchronized
    fun add(timestampNs: Long, x: Float, y: Float, z: Float) {
        t[next] = timestampNs
        xyz[next * 3] = x; xyz[next * 3 + 1] = y; xyz[next * 3 + 2] = z
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Mean rate of the samples in [fromNs, toNs]; with none inside (an exposure shorter than the sample period), the
     * nearest sample within [NEAREST_NS] of the window's middle. Null when the buffer has nothing that close.
     */
    @Synchronized
    fun mean(fromNs: Long, toNs: Long): FloatArray? {
        var n = 0
        val sum = FloatArray(3)
        var nearest = -1
        var nearestGap = Long.MAX_VALUE
        val mid = fromNs + (toNs - fromNs) / 2
        for (k in 0 until size) {
            val i = (next - 1 - k + capacity) % capacity
            val ti = t[i]
            if (ti in fromNs..toNs) { n++; for (a in 0 until 3) sum[a] += xyz[i * 3 + a] }
            val gap = abs(ti - mid)
            if (gap < nearestGap) { nearestGap = gap; nearest = i }
            if (ti < fromNs - NEAREST_NS) break // samples are in time order, newest first from here
        }
        if (n > 0) return FloatArray(3) { sum[it] / n }
        if (nearest < 0 || nearestGap > NEAREST_NS) return null
        return FloatArray(3) { xyz[nearest * 3 + it] }
    }

    companion object {
        const val NEAREST_NS = 20_000_000L
    }
}

/**
 * Decides which frame becomes a keyframe once one is due (#59): a sharp frame (blur ≤ [maxBlurPx], or no blur estimate)
 * is taken at once; otherwise the sharpest frame seen within [maxWaitMs] of the first due one is taken, so a fast
 * sweep still leaves a keyframe. Call [offer] for every candidate frame, in order, from one thread.
 */
class SharpPicker<T : Any>(private val maxBlurPx: Float, private val maxWaitMs: Long) {
    private var held: T? = null
    private var heldBlur = Float.MAX_VALUE
    private var dueAtMs = 0L

    /** Keyframes taken sharp at once, after waiting for a sharper frame, and blurred at the end of the wait. */
    var takenSharp = 0; private set
    var takenAfterWait = 0; private set
    var takenBlurred = 0; private set
    var takenUnknown = 0; private set

    /** True while a due keyframe waits for a sharper frame: every offered frame is then a candidate. */
    val waiting: Boolean get() = held != null

    /** [due]: enough travel or turn since the last keyframe. Returns the frame to write now (with its blur), or null. */
    fun offer(frame: T, blurPx: Float, due: Boolean, nowMs: Long): Pair<T, Float>? {
        if (held == null) {
            if (!due) return null
            if (blurPx.isNaN()) { takenUnknown++; return frame to blurPx }
            if (blurPx <= maxBlurPx) { takenSharp++; return frame to blurPx }
            held = frame; heldBlur = blurPx; dueAtMs = nowMs
            return null
        }
        if (blurPx.isNaN() || blurPx <= maxBlurPx) {
            held = null
            if (blurPx.isNaN()) takenUnknown++ else takenAfterWait++
            return frame to blurPx
        }
        if (blurPx < heldBlur) { held = frame; heldBlur = blurPx }
        if (nowMs - dueAtMs < maxWaitMs) return null
        return flush()
    }

    /** The frame still waiting for a sharper one, taken as it is (the wait ended, or the scan finished); null when none waits. */
    fun flush(): Pair<T, Float>? {
        val h = held ?: return null
        held = null
        takenBlurred++
        return h to heldBlur
    }
}
