package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ThumbnailTest {
    @Test
    fun plyRoundTrip() {
        val cloud = SyntheticWall.cloud(0f, 0.5f)
        val out = ByteArrayOutputStream()
        PlyWriter.write(cloud, out)
        val back = PlyReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(cloud.count, back.count)
        assertTrue(cloud.xyz.contentEquals(back.xyz))
        assertTrue(cloud.rgb.contentEquals(back.rgb))
    }

    @Test
    fun rendersWallWithHoldColorsInFront() {
        val cloud = SyntheticWall.cloud()
        val frame = WallSurface.single(WallFrame(Plane(1.5f, 1.2f, 0f, 0f, 0f, 1f)))
        val image = Thumbnail.render(cloud, frame, 320, 240)
        assertEquals(320 * 240, image.pixels.size)
        val red = image.pixels.count { (it shr 16 and 0xff) > 200 && (it shr 8 and 0xff) < 60 }
        val gray = image.pixels.count { (it and 0xff) == 150 && (it shr 8 and 0xff) == 150 }
        assertTrue("expected red hold pixels, got $red", red > 50)
        assertTrue("expected gray wall pixels, got $gray", gray > 10_000)
    }
}
