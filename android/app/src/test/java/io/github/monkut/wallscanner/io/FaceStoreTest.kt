package io.github.monkut.wallscanner.io

import io.github.monkut.wallscanner.core.Box
import io.github.monkut.wallscanner.core.FaceBox
import io.github.monkut.wallscanner.core.FaceFrame
import io.github.monkut.wallscanner.core.FaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FaceStoreTest {
    @Test
    fun sidecarSitsBesideTheVideoAndRoundTrips() {
        val dir = Files.createTempDirectory("faces").toFile()
        val video = File(dir, "session-1.mp4")
        assertEquals("session-1.faces.json", FaceStore.sidecar(video).name)
        assertTrue(FaceStore.read(video).isEmpty())
        FaceStore.write(video, listOf(
            FaceFrame(1200, listOf(FaceBox(Box(0.4f, 0.2f, 0.1f, 0.12f), FaceRole.CLIMBER), FaceBox(Box(0.1f, 0.5f, 0.08f, 0.1f), FaceRole.BYSTANDER))),
            FaceFrame(300, emptyList()),
        ))
        val read = FaceStore.read(video)
        assertEquals(listOf(300L, 1200L), read.map { it.tMs }) // time order
        assertEquals(2, read[1].faces.size)
        assertEquals(FaceRole.CLIMBER, read[1].faces[0].role)
        assertEquals(0.12f, read[1].faces[0].box.h, 1e-4f)
        assertEquals(FaceRole.BYSTANDER, read[1].faces[1].role)
        dir.deleteRecursively()
    }
}
