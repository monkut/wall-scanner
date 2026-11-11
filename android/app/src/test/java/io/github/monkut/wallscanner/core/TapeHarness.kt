package io.github.monkut.wallscanner.core

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Replays [HoldMerge.isTapeShaped] on every pulled capture's phone holds (`scene.json`) over its `wall.ppm` and,
 * where a `reference.json` exists, says whether each hold it would drop is one of the climber's holds (the two
 * outlines hold each other's centre) or not (tape, wall, a piece the climber dropped). It sees what the phone kept:
 * a scene written by a build with the filter has no tape-shaped holds left, so re-check those with
 * `RealCaptureHarness`, which rebuilds the scene.
 * `./gradlew :app:testDebugUnitTest --tests '*TapeHarness*' -i | grep TAPE`.
 */
class TapeHarness {
    @Test
    fun replayTapeShapes() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "captures") }.firstOrNull { it.isDirectory } ?: return
        var dropped = 0; var real = 0; var refHolds = 0
        for (dir in root.listFiles { f -> File(f, "scene.json").isFile && File(f, "wall.ppm").isFile }!!.sortedBy { it.name }) {
            val scene = JSONObject(File(dir, "scene.json").readText())
            val photo = StartHarness.photoOf(dir, scene, "TAPE") ?: continue
            val e = scene.getJSONObject("wall").getJSONArray("extent")
            val extent = FloatArray(4) { e.getDouble(it).toFloat() }
            val tape = StartHarness.holds(scene).filter { HoldMerge.isTapeShaped(it, photo, extent) }
            val ref = File(dir, "reference.json").takeIf { it.isFile }?.let { StartHarness.holds(JSONObject(it.readText())) }
            fun inRef(h: Hold) = ref?.any { inside(h.u, h.v, it.outline) || inside(it.u, it.v, h.outline) }
            val line = tape.joinToString { "#${it.id} (%.2f, %.2f) %.2f×%.2f%s".format(it.u, it.v, it.width, it.tall, when (inRef(it)) { true -> " REFERENCE HOLD"; false -> " not a reference hold"; null -> "" }) }
            println("TAPE ${dir.name}: ${tape.size} of ${scene.getJSONArray("holds").length()} holds shaped like tape${if (tape.isEmpty()) "" else ": $line"}")
            if (ref == null) continue
            dropped += tape.size; real += tape.count { inRef(it) == true }; refHolds += ref.size
        }
        println("TAPE all references: $dropped holds shaped like tape, $real of them reference holds (of $refHolds)")
    }

    /** Whether (u, v) lies inside the polygon [p] of (u, v) pairs (even-odd rule). */
    private fun inside(u: Float, v: Float, p: FloatArray): Boolean {
        var odd = false
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + n - 1) % n
            val ui = p[2 * i]; val vi = p[2 * i + 1]; val uj = p[2 * j]; val vj = p[2 * j + 1]
            if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) odd = !odd
        }
        return odd
    }
}
