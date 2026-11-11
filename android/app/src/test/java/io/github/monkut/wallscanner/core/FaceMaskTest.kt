package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Face boxes: merged across tiles, given roles by the climber's head, held and interpolated over time, mapped from the preview. */
class FaceMaskTest {
    private val face = Box(0.40f, 0.20f, 0.10f, 0.12f)
    private val other = Box(0.10f, 0.50f, 0.08f, 0.10f)

    @Test
    fun mergeKeepsTheLargerOfOverlappingDetections() {
        val tileHit = Box(0.41f, 0.21f, 0.08f, 0.10f) // the same face seen in a tile, slightly tighter
        val merged = FaceMask.merge(listOf(tileHit, face, other))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it === face })
        assertTrue(merged.any { it === other })
    }

    @Test
    fun theFaceOnTheHeadIsTheClimberAndOthersAreBystanders() {
        val head = Box(0.38f, 0.16f, 0.14f, 0.20f)
        val roles = FaceMask.assign(listOf(other, face), head)
        assertEquals(FaceRole.CLIMBER, roles.first { it.box === face }.role)
        assertEquals(FaceRole.BYSTANDER, roles.first { it.box === other }.role)
    }

    @Test
    fun aHeadWithoutAFaceIsStillMaskedAsTheClimber() {
        val head = Box(0.38f, 0.16f, 0.14f, 0.20f)
        val roles = FaceMask.assign(listOf(other), head)
        assertEquals(listOf(FaceRole.CLIMBER, FaceRole.BYSTANDER), roles.map { it.role })
        assertTrue(roles[0].box === head)
        assertEquals(1, FaceMask.assign(listOf(other), null).size)
        assertEquals(FaceRole.BYSTANDER, FaceMask.assign(listOf(other), null)[0].role)
    }

    @Test
    fun boxesAreInterpolatedBetweenSamplesAndHeldAfterTheLast() {
        val frames = listOf(
            FaceFrame(1000, listOf(FaceBox(Box(0.40f, 0.20f, 0.10f, 0.10f), FaceRole.CLIMBER))),
            FaceFrame(1100, listOf(FaceBox(Box(0.44f, 0.20f, 0.10f, 0.10f), FaceRole.CLIMBER))),
        )
        val mid = FaceMask.boxesAt(frames, 1050, climber = true, bystanders = true).single()
        assertEquals(0.42f - 0.10f * FaceMask.MARGIN, mid.box.x, 1e-4f) // halfway, with the margin
        val held = FaceMask.boxesAt(frames, 1100 + FaceMask.HOLD_MS, climber = true, bystanders = true).single()
        assertEquals(0.44f - 0.10f * FaceMask.MARGIN, held.box.x, 1e-4f)
        assertTrue(FaceMask.boxesAt(frames, 1101 + FaceMask.HOLD_MS, climber = true, bystanders = true).isEmpty())
        assertTrue(FaceMask.boxesAt(frames, 1050, climber = false, bystanders = true).isEmpty())
    }

    @Test
    fun aFaceOnlyInTheNextSampleComesInEarlyAndRolesFilter() {
        val frames = listOf(
            FaceFrame(1000, listOf(FaceBox(face, FaceRole.CLIMBER))),
            FaceFrame(1100, listOf(FaceBox(face, FaceRole.CLIMBER), FaceBox(other, FaceRole.BYSTANDER))),
        )
        val at = FaceMask.boxesAt(frames, 1050, climber = true, bystanders = true)
        assertEquals(setOf(FaceRole.CLIMBER, FaceRole.BYSTANDER), at.map { it.role }.toSet())
        assertEquals(listOf(FaceRole.BYSTANDER), FaceMask.boxesAt(frames, 1050, climber = false, bystanders = true).map { it.role })
        assertTrue(FaceMask.any(frames, 0, 2000, climber = false, bystanders = true))
        assertFalse(FaceMask.any(frames, 0, 2000, climber = false, bystanders = false))
        assertFalse(FaceMask.any(frames, 3000, 4000, climber = true, bystanders = true))
    }

    @Test
    fun previewBoxesMapIntoTheCroppedFrame() {
        // A 9:20 screen shows a centred 9:20 part of a 9:16 frame: the frame's width is cropped.
        val frame = FaceMask.previewToFrame(Box(0f, 0.5f, 1f, 0.1f), 9f / 20f, 9f / 16f)
        assertEquals(0.1f, frame.x, 1e-4f)
        assertEquals(0.8f, frame.w, 1e-4f)
        assertEquals(0.5f, frame.y, 1e-4f)
        // A wider preview than the frame crops the frame's height instead.
        val tall = FaceMask.previewToFrame(Box(0.2f, 0f, 0.1f, 1f), 1f, 0.5f)
        assertEquals(0.25f, tall.y, 1e-4f)
        assertEquals(0.5f, tall.h, 1e-4f)
        assertEquals(0.2f, tall.x, 1e-4f)
        val same = FaceMask.previewToFrame(face, 0.5f, 0.5f)
        assertTrue(same === face)
    }

    @Test
    fun expandAndClampStayInsideTheFrame() {
        val b = Box(0.95f, 0.0f, 0.1f, 0.1f).expand(0.5f).clamp()
        assertEquals(1f, b.right, 1e-6f)
        assertEquals(0f, b.y, 1e-6f)
    }
}
