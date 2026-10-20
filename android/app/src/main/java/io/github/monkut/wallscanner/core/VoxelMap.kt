package io.github.monkut.wallscanner.core

import kotlin.math.floor

/** Immutable point cloud: voxel centers (xyz interleaved), average colors (rgb bytes) and hit counts. */
class VoxelCloud(val count: Int, val xyz: FloatArray, val rgb: ByteArray, val hits: IntArray)

/** Thread-safe accumulation of colored points into a sparse voxel grid. */
class VoxelMap(
    private val voxelSize: Float = Tuning.VOXEL_SIZE,
    private val maxVoxels: Int = Tuning.MAX_VOXELS,
) {
    private class Voxel(var sx: Float, var sy: Float, var sz: Float, var r: Int, var g: Int, var b: Int, var n: Int)

    private val voxels = HashMap<Long, Voxel>()
    private val inv = 1f / voxelSize

    val size: Int get() = synchronized(voxels) { voxels.size }

    fun add(x: Float, y: Float, z: Float, r: Int, g: Int, b: Int) = synchronized(voxels) { addLocked(x, y, z, r, g, b) }

    /** Adds [count] points from interleaved xyz floats and rgb bytes. */
    fun addAll(xyz: FloatArray, rgb: ByteArray, count: Int) = synchronized(voxels) {
        for (i in 0 until count) {
            addLocked(
                xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2],
                rgb[i * 3].toInt() and 0xff, rgb[i * 3 + 1].toInt() and 0xff, rgb[i * 3 + 2].toInt() and 0xff,
            )
        }
    }

    fun clear() = synchronized(voxels) { voxels.clear() }

    fun snapshot(): VoxelCloud = synchronized(voxels) {
        val n = voxels.size
        val xyz = FloatArray(n * 3)
        val rgb = ByteArray(n * 3)
        val hits = IntArray(n)
        var i = 0
        for (v in voxels.values) {
            val inv = 1f / v.n
            xyz[i * 3] = v.sx * inv
            xyz[i * 3 + 1] = v.sy * inv
            xyz[i * 3 + 2] = v.sz * inv
            rgb[i * 3] = (v.r / v.n).toByte()
            rgb[i * 3 + 1] = (v.g / v.n).toByte()
            rgb[i * 3 + 2] = (v.b / v.n).toByte()
            hits[i] = v.n
            i++
        }
        VoxelCloud(n, xyz, rgb, hits)
    }

    private fun addLocked(x: Float, y: Float, z: Float, r: Int, g: Int, b: Int) {
        val key = VoxelKey.pack(floor(x * inv).toInt(), floor(y * inv).toInt(), floor(z * inv).toInt())
        val v = voxels[key]
        if (v != null) {
            v.sx += x; v.sy += y; v.sz += z
            v.r += r; v.g += g; v.b += b
            v.n++
        } else if (voxels.size < maxVoxels) {
            voxels[key] = Voxel(x, y, z, r, g, b, 1)
        }
    }
}
