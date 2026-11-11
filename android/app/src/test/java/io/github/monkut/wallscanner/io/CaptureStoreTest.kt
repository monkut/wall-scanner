package io.github.monkut.wallscanner.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The wall height other scans of the same wall measured, for the down-climb rule (#27). */
class CaptureStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun capture(root: File, name: String, wallId: String, bottom: Double, top: Double, drifted: Boolean = false): File =
        File(root, name).apply {
            mkdirs()
            File(this, CaptureStore.META_FILE).writeText("""{"version": 2, "location": "loc-4", "set": "set-6", "wall": "$wallId"}""")
            File(this, CaptureStore.SCENE_FILE).writeText(
                """{"version": 4, "wall": {"extent": [-1.8, $bottom, 2.4, $top]}, "quality": {"drifted": $drifted}, "holds": [], "routes": []}""",
            )
        }

    @Test
    fun theTallestOtherScanOfTheSameWallCounts() {
        val root = tmp.newFolder("captures")
        val short = capture(root, "20261110-174938", "wall-8", -1.4863, 1.8366) // 3.32 m: the top was missed
        capture(root, "20261105-181751", "wall-8", -1.6104, 1.9330) // 3.54 m
        capture(root, "20261110-164317", "wall-8", -2.0, 2.0, drifted = true) // ghosts stretch a drifted scan: left out
        capture(root, "20261105-171814", "wall-7", -1.8573, 1.8543) // another wall
        File(capture(root, "20261023-140029", "wall-8", -2.1, 2.1), CaptureStore.SCENE_FILE) // no quality verdict (before #35): unjudged, left out
            .writeText("""{"version": 3, "wall": {"extent": [-1.8, -2.1, 2.4, 2.1]}, "holds": [], "routes": []}""")
        assertEquals(3.5434f, CaptureStore.tallestOtherScan(short)!!, 1e-4f)
    }

    @Test
    fun aCaptureWithoutAWallOrWithoutOtherScansKnowsNothing() {
        val root = tmp.newFolder("captures")
        assertNull(CaptureStore.tallestOtherScan(capture(root, "a", "", -1.5, 1.8)))
        assertNull(CaptureStore.tallestOtherScan(capture(root, "b", "wall-9", -1.5, 1.8)))
    }
}
