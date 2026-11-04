package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Faces unfold about their seam: the seam is continuous, points on a face measure zero relief, the layout stays 2D. */
class WallSurfaceTest {
    private val rad20 = Math.toRadians(20.0)

    /** A vertical wall (z = 0, normal +z toward the camera) whose top, above y = 1, kicks back by 20°. */
    private fun kickedWall(noise: Float = 0f, random: Random = Random(1)): VoxelCloud {
        val pts = ArrayList<Float>()
        var u = -1.5f
        while (u <= 1.5f) {
            var y = -1f
            while (y <= 2.2f) {
                val n = if (noise > 0f) (random.nextFloat() - 0.5f) * 2 * noise else 0f
                if (y <= 1f) { pts += u; pts += y; pts += n }
                else { val t = y - 1f; pts += u; pts += (1 + t * cos(rad20)).toFloat(); pts += (-t * sin(rad20)).toFloat() + n }
                y += 0.01f
            }
            u += 0.01f
        }
        val count = pts.size / 3
        return VoxelCloud(count, pts.toFloatArray(), ByteArray(count * 3) { 120.toByte() }, IntArray(count) { 3 })
    }

    @Test
    fun aSingleFaceIsTheOldFrame() {
        val frame = WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))
        val s = WallSurface.single(frame)
        assertEquals(frame.u(0.3f, 0.7f, 0.1f), s.u(0.3f, 0.7f, 0.1f), 1e-6f)
        assertEquals(frame.v(0.3f, 0.7f, 0.1f), s.v(0.3f, 0.7f, 0.1f), 1e-6f)
        assertEquals(0.1f, s.d(0.3f, 0.7f, 0.1f), 1e-6f)
    }

    @Test
    fun unfoldingAKickedTopKeepsTheSeamAndFlattensTheRelief() {
        val lower = Plane(0f, 0f, 0f, 0f, 0f, 1f)
        val upper = Plane(0f, (1 + cos(rad20)).toFloat(), (-sin(rad20)).toFloat(), 0f, sin(rad20).toFloat(), cos(rad20).toFloat()) // normal tilted up, still toward the camera
        val s = WallSurface.unfold(lower, listOf(upper))
        assertEquals(2, s.faces.size)
        // A point 0.5 m up the upper face sits 0.5 m above the seam in the unfolded frame, at zero relief.
        val t = 0.5f
        val x = 0.3f; val y = (1 + t * cos(rad20)).toFloat(); val z = (-t * sin(rad20)).toFloat()
        assertEquals(0.3f, s.u(x, y, z), 1e-3f)
        assertEquals(1.5f, s.v(x, y, z), 1e-3f)
        assertEquals(0f, s.d(x, y, z), 1e-3f)
        // Below the seam the primary face owns the point; the old single-plane relief of the upper point would have been large.
        assertEquals(0.5f, s.v(0.3f, 0.5f, 0f), 1e-3f)
        assertTrue(lower.signedDistance(x, y, z) < -0.1f)
        // world() inverts project(): the unfolded point maps back onto the upper face.
        val w = s.world(0.3f, 1.5f, 0f)
        assertEquals(x, w[0], 1e-3f); assertEquals(y, w[1], 1e-3f); assertEquals(z, w[2], 1e-3f)
        // A hold 3 cm proud of the upper face reads 3 cm.
        val nx = 0f; val ny = sin(rad20).toFloat(); val nz = cos(rad20).toFloat()
        assertEquals(0.03f, s.d(x + 0.03f * nx, y + 0.03f * ny, z + 0.03f * nz), 1e-3f)
    }

    @Test
    fun aCornerUnfoldsSideBySide() {
        // Primary wall z = 0 facing +z; a side wall turning 90° at x = 1, facing -x (toward a camera in front of the corner).
        val front = Plane(0f, 0f, 0f, 0f, 0f, 1f)
        val side = Plane(1f, 0f, 1f, -1f, 0f, 0f)
        val s = WallSurface.unfold(front, listOf(side))
        // A point 0.4 m along the side wall from the corner (z = 0.4) unfolds to U = 1.4 at zero relief.
        assertEquals(1.4f, s.u(1f, 0.2f, 0.4f), 1e-3f)
        assertEquals(0.2f, s.v(1f, 0.2f, 0.4f), 1e-3f)
        assertEquals(0f, s.d(1f, 0.2f, 0.4f), 1e-3f)
        assertEquals(0.5f, s.u(0.5f, 0.2f, 0f), 1e-3f)
    }

    @Test
    fun facetsFindTheKickedTopAndLeaveAFlatWallAlone() {
        val cloud = kickedWall(noise = 0.005f)
        val frame = WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))
        val found = Facets.find(cloud, frame)
        assertEquals("faces: ${found.areas}", 2, found.surface.faces.size)
        val angle = found.surface.faces[1].angleTo(found.surface.faces[0])
        assertEquals(20f, angle, 2f)
        // The top of the upper face: zero relief and continuous height in the unfolded frame.
        val t = 1.0f
        val y = (1 + t * cos(rad20)).toFloat(); val z = (-t * sin(rad20)).toFloat()
        assertEquals(0f, found.surface.d(0f, y, z), 0.02f)
        assertEquals(2f, found.surface.v(0f, y, z), 0.03f)
        // A flat wall stays one face.
        val flatPts = FloatArray(cloud.count * 3)
        for (i in 0 until cloud.count) { flatPts[i * 3] = cloud.xyz[i * 3]; flatPts[i * 3 + 1] = cloud.xyz[i * 3 + 1]; flatPts[i * 3 + 2] = (Random(i).nextFloat() - 0.5f) * 0.01f }
        val flat = VoxelCloud(cloud.count, flatPts, cloud.rgb, cloud.hits)
        assertEquals(1, Facets.find(flat, frame).surface.faces.size)
        assertEquals(sqrt(2f), sqrt(2f), 0f)
    }
}
