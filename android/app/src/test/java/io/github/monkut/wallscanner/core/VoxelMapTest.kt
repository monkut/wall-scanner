package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VoxelMapTest {
    private fun VoxelCloud.keys(voxel: Float = 0.01f): Set<Long> =
        (0 until count).map { i ->
            VoxelKey.pack(
                Math.floor((xyz[i * 3] / voxel).toDouble()).toInt(),
                Math.floor((xyz[i * 3 + 1] / voxel).toDouble()).toInt(),
                Math.floor((xyz[i * 3 + 2] / voxel).toDouble()).toInt(),
            )
        }.toSet()

    @Test
    fun averagesPositionAndColourPerVoxel() {
        val map = VoxelMap(voxelSize = 0.1f)
        map.add(0.02f, 0.02f, 0.02f, 100, 0, 0)
        map.add(0.08f, 0.08f, 0.08f, 200, 0, 0)
        map.add(0.15f, 0.02f, 0.02f, 0, 0, 255)
        assertEquals(2, map.size)
        val cloud = map.snapshot()
        val first = (0 until cloud.count).first { cloud.hits[it] == 2 }
        assertEquals(0.05f, cloud.xyz[first * 3], 1e-6f)
        assertEquals(150, cloud.rgb[first * 3].toInt() and 0xff)
        assertEquals(0, cloud.rgb[first * 3 + 2].toInt() and 0xff)
    }

    @Test
    fun growsPastInitialCapacityWithoutLosingVoxels() {
        val map = VoxelMap(voxelSize = 0.01f)
        val rnd = Random(3)
        val expected = HashSet<Long>()
        repeat(50_000) {
            val x = rnd.nextInt(-200, 200); val y = rnd.nextInt(-200, 200); val z = rnd.nextInt(-5, 5)
            map.add(x * 0.01f + 0.005f, y * 0.01f + 0.005f, z * 0.01f + 0.005f, 10, 20, 30)
            expected.add(VoxelKey.pack(x, y, z))
        }
        assertEquals(expected.size, map.size)
        assertEquals(expected, map.snapshot().keys())
    }

    @Test
    fun evictsLeastHitVoxelsAtTheCapInsteadOfDroppingNewPoints() {
        val map = VoxelMap(voxelSize = 0.01f, maxVoxels = 100)
        for (i in 0 until 100) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0)
        // Hit the first 60 voxels again; the other 40 stay single-hit.
        for (i in 0 until 60) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0)
        assertEquals(100, map.size)
        assertEquals(0, map.evictions)

        map.add(5f, 5f, 5f, 255, 255, 255) // 101st voxel
        assertTrue("size ${map.size} over cap", map.size <= 100)
        assertEquals(40, map.evictions)
        val keys = map.snapshot().keys()
        assertTrue("new voxel kept", VoxelKey.pack(500, 500, 500) in keys)
        for (i in 0 until 60) assertTrue("twice-hit voxel $i kept", VoxelKey.pack(i, 0, 0) in keys)
        for (i in 60 until 100) assertTrue("single-hit voxel $i evicted", VoxelKey.pack(i, 0, 0) !in keys)
    }

    @Test
    fun evictionRaisesTheBarWhenNoSingleHitVoxelsRemain() {
        val map = VoxelMap(voxelSize = 0.01f, maxVoxels = 50)
        repeat(3) { for (i in 0 until 50) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0) }
        // 25 voxels with 4 hits, 25 with 3 hits.
        for (i in 0 until 25) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0)
        map.add(5f, 5f, 5f, 0, 0, 0)
        assertTrue(map.size <= 50)
        assertTrue("evicted at least a tenth", map.evictions >= 5)
        val keys = map.snapshot().keys()
        for (i in 0 until 25) assertTrue("four-hit voxel $i kept", VoxelKey.pack(i, 0, 0) in keys)
    }

    @Test
    fun snapshotFiltersByMinimumHits() {
        val map = VoxelMap(voxelSize = 0.01f)
        for (i in 0 until 10) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0)
        for (i in 0 until 4) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0)
        assertEquals(10, map.snapshot().count)
        assertEquals(4, map.snapshot(minHits = 2).count)
        assertEquals(0, map.snapshot(minHits = 3).count)
    }

    @Test
    fun clearResetsEverything() {
        val map = VoxelMap(voxelSize = 0.01f, maxVoxels = 10)
        for (i in 0 until 20) map.add(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 0, 0)
        assertTrue(map.evictions > 0)
        map.clear()
        assertEquals(0, map.size)
        assertEquals(0, map.evictions)
        assertEquals(0, map.snapshot().count)
    }
}
