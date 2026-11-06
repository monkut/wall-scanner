package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bar advances monotonically through the stages that run and ignores a stage that will not. */
class ProcessingTest {
    @Test
    fun earlierStagesCountAsDone() {
        val p = Processing(listOf(ProcessingStage.MERGE, ProcessingStage.CLOUD, ProcessingStage.WRITE)) // weights 1 + 11 + 1
        assertEquals(0f, p.fraction(ProcessingStage.MERGE, 0f), 1e-6f)
        assertEquals(1f / 13f, p.fraction(ProcessingStage.CLOUD, 0f), 1e-6f)
        assertEquals(6.5f / 13f, p.fraction(ProcessingStage.CLOUD, 0.5f), 1e-6f)
        assertEquals(1f, p.fraction(ProcessingStage.WRITE, 1f), 1e-6f)
    }

    @Test
    fun aStageThatDoesNotRunAddsNothing() {
        val p = Processing(Processing.atFinish(maskFaces = false))
        assertEquals(p.fraction(ProcessingStage.CLOUD, 0f), p.fraction(ProcessingStage.FACES, 1f), 1e-6f)
        assertTrue(Processing.atFinish(maskFaces = true).contains(ProcessingStage.FACES))
        assertTrue(ProcessingStage.MERGE !in Processing.REPROCESS)
    }

    @Test
    fun neverLeavesZeroToOne() {
        val p = Processing(ProcessingStage.entries)
        assertEquals(0f, p.fraction(ProcessingStage.MERGE, -1f), 1e-6f)
        assertEquals(1f, p.fraction(ProcessingStage.WRITE, 2f), 1e-6f)
        var last = -1f
        for (s in ProcessingStage.entries) for (f in listOf(0f, 0.5f, 1f)) {
            val v = p.fraction(s, f); assertTrue("$s $f: $v < $last", v >= last); last = v
        }
    }
}
