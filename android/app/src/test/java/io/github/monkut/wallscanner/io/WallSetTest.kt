package io.github.monkut.wallscanner.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The set a new scan goes to is the newest open one; listing puts open sets first, newest first. */
class WallSetTest {
    private val september = WallSet("set-1", "September Set", "2026-09-02T10:00+09:00", retiredAt = "2026-10-01T09:00+09:00")
    private val october = WallSet("set-2", "October Set", "2026-10-03T10:00+09:00")
    private val kids = WallSet("set-3", "Kids wall", "2026-09-20T10:00+09:00")

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
}
