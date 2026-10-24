package io.github.monkut.wallscanner.core

import kotlin.math.floor

/** Immutable point cloud: voxel centers (xyz interleaved), average colors (rgb bytes) and hit counts. */
class VoxelCloud(val count: Int, val xyz: FloatArray, val rgb: ByteArray, val hits: IntArray)

/**
 * Thread-safe accumulation of colored points into a sparse voxel grid.
 *
 * Storage is an open-addressed hash table over flat arrays (about 36 bytes per voxel; no per-voxel objects).
 * When the table holds [maxVoxels] and a new voxel arrives, the voxels with the fewest hits are evicted
 * instead of the new point being dropped: a voxel seen once is far more likely depth noise than wall.
 */
class VoxelMap(
    private val voxelSize: Float = Tuning.VOXEL_SIZE,
    private val maxVoxels: Int = Tuning.MAX_VOXELS,
) {
    private val inv = 1f / voxelSize
    private val lock = Any()

    private var capacity = INITIAL_CAPACITY
    private var keys = LongArray(capacity) { EMPTY }
    private var sx = FloatArray(capacity)
    private var sy = FloatArray(capacity)
    private var sz = FloatArray(capacity)
    private var sr = IntArray(capacity)
    private var sg = IntArray(capacity)
    private var sb = IntArray(capacity)
    private var hits = IntArray(capacity)
    private var count = 0

    val size: Int get() = synchronized(lock) { count }

    /** Hash-table slots currently allocated (power of two); each slot is about 36 bytes. */
    val tableSlots: Int get() = synchronized(lock) { capacity }

    /** Voxels removed so far to stay under [maxVoxels]; non-zero means the cap was reached at least once. */
    val evictions: Int get() = synchronized(lock) { evicted }
    private var evicted = 0

    fun add(x: Float, y: Float, z: Float, r: Int, g: Int, b: Int) = synchronized(lock) { addLocked(x, y, z, r, g, b) }

    /** Adds [n] points from interleaved xyz floats and rgb bytes. */
    fun addAll(xyz: FloatArray, rgb: ByteArray, n: Int) = synchronized(lock) {
        for (i in 0 until n) {
            addLocked(
                xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2],
                rgb[i * 3].toInt() and 0xff, rgb[i * 3 + 1].toInt() and 0xff, rgb[i * 3 + 2].toInt() and 0xff,
            )
        }
    }

    fun clear() = synchronized(lock) {
        capacity = INITIAL_CAPACITY
        allocate()
        count = 0
        evicted = 0
    }

    /** Voxels with at least [minHits] points; the mean position and colour of each. */
    fun snapshot(minHits: Int = 1): VoxelCloud = synchronized(lock) {
        var n = 0
        for (i in 0 until capacity) if (keys[i] != EMPTY && hits[i] >= minHits) n++
        val xyz = FloatArray(n * 3)
        val rgb = ByteArray(n * 3)
        val outHits = IntArray(n)
        var o = 0
        for (i in 0 until capacity) {
            if (keys[i] == EMPTY || hits[i] < minHits) continue
            val h = hits[i]
            val invH = 1f / h
            xyz[o * 3] = sx[i] * invH
            xyz[o * 3 + 1] = sy[i] * invH
            xyz[o * 3 + 2] = sz[i] * invH
            rgb[o * 3] = (sr[i] / h).toByte()
            rgb[o * 3 + 1] = (sg[i] / h).toByte()
            rgb[o * 3 + 2] = (sb[i] / h).toByte()
            outHits[o] = h
            o++
        }
        VoxelCloud(n, xyz, rgb, outHits)
    }

    /**
     * At most [maxPoints] voxels for the live preview: every k-th occupied slot when the map holds more.
     * Bounds the per-upload cost on the GL thread, which [snapshot] of a near-full map does not.
     */
    fun preview(maxPoints: Int): VoxelCloud = synchronized(lock) {
        val stride = maxOf(1, (count + maxPoints - 1) / maxPoints)
        val n = (count + stride - 1) / stride
        val xyz = FloatArray(n * 3)
        val rgb = ByteArray(n * 3)
        val outHits = IntArray(n)
        var seen = 0
        var o = 0
        for (i in 0 until capacity) {
            if (keys[i] == EMPTY) continue
            if (seen++ % stride != 0) continue
            val h = hits[i]
            val invH = 1f / h
            xyz[o * 3] = sx[i] * invH
            xyz[o * 3 + 1] = sy[i] * invH
            xyz[o * 3 + 2] = sz[i] * invH
            rgb[o * 3] = (sr[i] / h).toByte()
            rgb[o * 3 + 1] = (sg[i] / h).toByte()
            rgb[o * 3 + 2] = (sb[i] / h).toByte()
            outHits[o] = h
            o++
        }
        VoxelCloud(o, xyz, rgb, outHits)
    }

    private fun addLocked(x: Float, y: Float, z: Float, r: Int, g: Int, b: Int) {
        val key = VoxelKey.pack(floor(x * inv).toInt(), floor(y * inv).toInt(), floor(z * inv).toInt())
        var i = slot(key)
        if (keys[i] == EMPTY) {
            if (count >= maxVoxels) evictLeastHit()
            if (count + 1 > capacity * MAX_LOAD) grow()
            i = slot(key)
            keys[i] = key
            count++
        }
        sx[i] += x; sy[i] += y; sz[i] += z
        sr[i] += r; sg[i] += g; sb[i] += b
        hits[i]++
    }

    /** Index of [key], or of the empty slot where it would go. */
    private fun slot(key: Long): Int {
        var i = mix(key) and (capacity - 1)
        while (keys[i] != EMPTY && keys[i] != key) i = (i + 1) and (capacity - 1)
        return i
    }

    private fun grow() = rebuild(capacity * 2) { true }

    /**
     * Drops every voxel whose hit count equals the lowest present, raising that bar until at least
     * [EVICT_FRACTION] of the map is freed so eviction does not run on every subsequent point.
     */
    private fun evictLeastHit() {
        // Hit counts are clamped to the last bin both when counting and when keeping, so the bar always frees
        // exactly what it counted; if even the last bin is needed, everything goes and the cap still holds.
        val last = HISTOGRAM_BINS - 1
        val histogram = IntArray(HISTOGRAM_BINS)
        for (i in 0 until capacity) if (keys[i] != EMPTY) histogram[hits[i].coerceAtMost(last)]++
        val target = (count * EVICT_FRACTION).toInt().coerceAtLeast(1)
        var bar = 0
        var freed = 0
        while (freed < target && bar < last) {
            bar++
            freed += histogram[bar]
        }
        val oldHits = hits
        rebuild(capacity) { i -> oldHits[i].coerceAtMost(last) > bar }
        evicted += freed
    }

    /** Allocates a fresh table and re-inserts the kept voxels; both tables exist until this returns. */
    private inline fun rebuild(newCapacity: Int, keep: (Int) -> Boolean) {
        val oldCapacity = capacity
        val oKeys = keys; val oSx = sx; val oSy = sy; val oSz = sz
        val oSr = sr; val oSg = sg; val oSb = sb; val oHits = hits
        capacity = newCapacity
        allocate()
        count = 0
        for (i in 0 until oldCapacity) {
            if (oKeys[i] == EMPTY || !keep(i)) continue
            val j = slot(oKeys[i])
            keys[j] = oKeys[i]
            sx[j] = oSx[i]; sy[j] = oSy[i]; sz[j] = oSz[i]
            sr[j] = oSr[i]; sg[j] = oSg[i]; sb[j] = oSb[i]
            hits[j] = oHits[i]
            count++
        }
    }

    private fun allocate() {
        keys = LongArray(capacity) { EMPTY }
        sx = FloatArray(capacity); sy = FloatArray(capacity); sz = FloatArray(capacity)
        sr = IntArray(capacity); sg = IntArray(capacity); sb = IntArray(capacity)
        hits = IntArray(capacity)
    }

    private fun mix(key: Long): Int {
        var h = key * -0x61c8864680b583ebL // golden-ratio multiplier
        h = h xor (h ushr 32)
        return h.toInt()
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
        const val INITIAL_CAPACITY = 1 shl 12
        const val MAX_LOAD = 0.7f
        const val EVICT_FRACTION = 0.1f
        const val HISTOGRAM_BINS = 1024 // hit counts at or above the last bin share it
    }
}
