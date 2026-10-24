package io.github.monkut.wallscanner.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** sends.json v2: climbs with their filmed attempts, route comments, and v1 files read unchanged. */
class RouteStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun clip(id: Int, file: String, start: Long = 0, end: Long = 10_000, auto: Boolean = false) =
        Clip(id, id, file, 60_000, start, end, null, auto, "2026-10-24T18:00:00+09:00")

    @Test
    fun climbsWithClipsAndRouteCommentsRoundTrip() {
        val dir = tmp.newFolder()
        RouteStore.setRating(dir, 3, 4)
        RouteStore.setComment(dir, 3, "  start is the left tape  ")
        val send = RouteStore.addSend(dir, 3, SendResult.SEND, 3, "crux twice", listOf(clip(1, "climbs/1/a.mp4").with(outcome = ClipOutcome.FALL), clip(2, "climbs/1/a.mp4", 20_000, 35_000, auto = true)))
        val back = RouteStore.sendsFor(dir, 3).single()
        assertEquals(send.id, back.id)
        assertEquals("crux twice", back.note)
        assertEquals(listOf(1, 2), back.clips.map { it.id })
        assertEquals(ClipOutcome.FALL, back.clips[0].outcome)
        assertNull(back.clips[1].outcome)
        assertTrue(back.clips[1].auto)
        assertEquals(20_000L, back.clips[1].trimStartMs)
        assertEquals("start is the left tape", RouteStore.comment(dir, 3))
        assertEquals(4, RouteStore.rating(dir, 3))
        RouteStore.setComment(dir, 3, " ")
        assertNull(RouteStore.comment(dir, 3))
    }

    @Test
    fun aVersionOneFileReadsUnchanged() {
        val dir = tmp.newFolder()
        File(dir, RouteStore.SENDS_FILE).writeText("""{"version": 1, "sends": [{"id": 1, "routeId": 2, "at": "2026-10-20T10:00+09:00", "result": "flash", "attempts": 1, "note": "n"}], "ratings": {"2": 5}}""")
        val s = RouteStore.sends(dir).single()
        assertEquals(SendResult.FLASH, s.result)
        assertTrue(s.clips.isEmpty())
        assertEquals(5, RouteStore.rating(dir, 2))
        assertNull(RouteStore.comment(dir, 2))
    }

    @Test
    fun trimPointsStayInsideTheFile() {
        val c = clip(1, "x.mp4").with(trimStartMs = -5, trimEndMs = 90_000)
        assertEquals(0L, c.trimStartMs)
        assertEquals(60_000L, c.trimEndMs)
        val crossed = clip(1, "x.mp4").with(trimStartMs = 30_000, trimEndMs = 30_100)
        assertEquals(30_100L - Clip.MIN_CLIP_MS, crossed.trimStartMs)
    }

    @Test
    fun aSessionFileGoesWithItsLastClip() {
        val dir = tmp.newFolder()
        File(dir, RouteStore.PENDING_DIR).mkdirs()
        File(dir, "${RouteStore.PENDING_DIR}/session.mp4").writeText("video")
        val send = RouteStore.addSend(dir, 1, SendResult.ATTEMPT, 2, "", listOf(clip(1, "${RouteStore.PENDING_DIR}/session.mp4", auto = true), clip(2, "${RouteStore.PENDING_DIR}/session.mp4", 20_000, 30_000, auto = true)), RouteStore.PENDING_DIR)
        val file = File(dir, send.clips[0].file)
        assertEquals("${RouteStore.clipDir(send.id)}/session.mp4", send.clips[0].file)
        assertTrue(file.exists())
        assertFalse(File(dir, RouteStore.PENDING_DIR).exists())
        RouteStore.deleteClip(dir, send.id, 1)
        assertTrue("the other attempt still plays from it", file.exists())
        RouteStore.deleteClip(dir, send.id, 2)
        assertFalse(file.exists())
        RouteStore.deleteSend(dir, send.id)
        assertFalse(File(dir, RouteStore.clipDir(send.id)).exists())
    }
}
