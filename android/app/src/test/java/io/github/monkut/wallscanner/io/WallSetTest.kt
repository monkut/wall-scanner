package io.github.monkut.wallscanner.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set a new scan goes to is the newest open one covering the wall; listing puts open sets first, newest first;
 * a set with no walls listed covers the whole gym.
 */
class WallSetTest {
    private val september = WallSet("set-1", "September Set", "2026-09-02T10:00+09:00", retiredAt = "2026-10-01T09:00+09:00")
    private val october = WallSet("set-2", "October Set", "2026-10-03T10:00+09:00")
    private val kids = WallSet("set-3", "Kids wall", "2026-09-20T10:00+09:00")
    private val caveOnly = WallSet("set-4", "Cave reset", "2026-10-20T10:00+09:00", wallIds = listOf("wall-7"))

    @Test
    fun currentIsTheNewestOpenSet() {
        assertEquals("set-2", WallSet.current(listOf(september, kids, october))?.id)
        assertNull(WallSet.current(listOf(september)))
        assertNull(WallSet.current(emptyList()))
    }

    @Test
    fun orderedListsOpenSetsNewestFirstThenRetired() {
        assertEquals(listOf("set-2", "set-3", "set-1"), WallSet.ordered(listOf(september, kids, october)).map { it.id })
    }

    @Test
    fun aSetWithoutWallsCoversEveryWallAndNoWall() {
        assertTrue(october.covers("wall-7"))
        assertTrue(october.covers("wall-8"))
        assertTrue(october.covers(""))
    }

    @Test
    fun aSetWithWallsCoversOnlyThose() {
        assertTrue(caveOnly.covers("wall-7"))
        assertFalse(caveOnly.covers("wall-8"))
        assertFalse(caveOnly.covers(""))
    }

    @Test
    fun currentForAWallIsTheNewestOpenSetCoveringIt() {
        val sets = listOf(september, kids, october, caveOnly)
        assertEquals("set-4", WallSet.current(sets, "wall-7")?.id)
        assertEquals("set-2", WallSet.current(sets, "wall-8")?.id)
        assertEquals("set-2", WallSet.current(sets, "")?.id)
        assertNull(WallSet.current(listOf(caveOnly), "wall-8"))
    }
}
