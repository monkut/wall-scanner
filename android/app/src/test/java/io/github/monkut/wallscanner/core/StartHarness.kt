package io.github.monkut.wallscanner.core

import org.json.JSONObject
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Replays the start rule ([TapeMarks.markStarts]) on every pulled capture's phone `scene.json` holds and its
 * orthophoto (`wall.ppm`, written by `uv run tools/frames_to_ppm.py captures/<id>`), and scores the START holds
 * against `reference.json` where one exists: a reference start counts as found when a replayed start lies
 * within [MATCH_M] of it. `./gradlew :app:testDebugUnitTest --tests '*StartHarness*' -i | grep STARTS`.
 */
class StartHarness {
    private val MATCH_M = 0.06f

    @Test
    fun replayStarts() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "captures") }.firstOrNull { it.isDirectory } ?: return
        var found = 0; var refTotal = 0; var marked = 0; var correct = 0
        for (dir in root.listFiles { f -> File(f, "scene.json").isFile && File(f, "wall.ppm").isFile }!!.sortedBy { it.name }) {
            val scene = JSONObject(File(dir, "scene.json").readText())
            val wallJ = scene.getJSONObject("wall")
            val photoJ = wallJ.optJSONObject("photo") ?: run { println("STARTS ${dir.name}: skipped, no orthophoto in scene.json"); null } ?: continue
            val wall = wallColor(File(dir, "scan.log")) ?: run { println("STARTS ${dir.name}: skipped, no wallColor in scan.log"); null } ?: continue
            val b = photoJ.getJSONArray("bounds")
            val img = readPpm(File(dir, "wall.ppm"))
            // wall.ppm must be this scene's photo: a Reprocess that moved the bounds leaves a stale one behind
            val px = photoJ.getDouble("px")
            val expectW = Math.round((b.getDouble(2) - b.getDouble(0)) / px).toInt(); val expectH = Math.round((b.getDouble(3) - b.getDouble(1)) / px).toInt()
            if (kotlin.math.abs(img.width - expectW) > 1 || kotlin.math.abs(img.height - expectH) > 1) { println("STARTS ${dir.name}: skipped, wall.ppm ${img.width}×${img.height} is not this scene's ${expectW}×${expectH} photo"); continue }
            val photo = Orthophoto(img, b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), photoJ.getDouble("px").toFloat(), FloatArray(img.pixels.size))
            val e = wallJ.getJSONArray("extent"); val extent = FloatArray(4) { e.getDouble(it).toFloat() }
            val holds = holds(scene)
            TapeMarks.markStarts(holds, photo, wall, ColorPatches.forHolds(photo, wall), extent)
            val starts = holds.filter { it.role == HoldRole.START }
            val ref = File(dir, "reference.json").takeIf { it.isFile }?.let { holds(JSONObject(it.readText()), keepRoles = true).filter { h -> h.role == HoldRole.START } }
            fun near(a: Hold, bs: List<Hold>) = bs.any { kotlin.math.hypot(a.u - it.u, a.v - it.v) <= MATCH_M }
            val line = "STARTS ${dir.name}: ${starts.joinToString { "#${it.id} (%.2f, %.2f)".format(it.u, it.v) }}"
            if (ref == null) { println(line); continue }
            val hit = ref.count { near(it, starts) }; val ok = starts.count { near(it, ref) }
            found += hit; refTotal += ref.size; marked += starts.size; correct += ok
            println("$line | reference ${ref.joinToString { "#${it.id} (%.2f, %.2f)".format(it.u, it.v) }} → found $hit/${ref.size}, $ok/${starts.size} marked are reference starts")
        }
        println("STARTS all references: found $found/$refTotal reference starts, $correct/$marked marked are reference starts")
    }

    /** The holds of [scene]; START roles are kept when [keepRoles] (reference reads), else every hold starts as ANY. */
    private fun holds(scene: JSONObject, keepRoles: Boolean = false): List<Hold> {
        val arr = scene.getJSONArray("holds")
        return List(arr.length()) { i ->
            val h = arr.getJSONObject(i)
            val c = h.getJSONArray("center"); val uv = h.getJSONArray("uv"); val col = h.getJSONArray("color"); val size = h.getJSONArray("size")
            val o = h.optJSONArray("outline")
            val outline = if (o == null) FloatArray(0) else FloatArray(o.length() * 2) { k -> o.getJSONArray(k / 2).getDouble(k % 2).toFloat() }
            Hold(h.getInt("id"), c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), uv.getDouble(0).toFloat(), uv.getDouble(1).toFloat(),
                size.getDouble(0).toFloat(), size.getDouble(1).toFloat(), size.getDouble(2).toFloat(), col.getInt(0), col.getInt(1), col.getInt(2), h.optInt("voxels"), outline)
                .also { if (keepRoles && h.optString("role") == "start") it.role = HoldRole.START }
        }
    }

    /** The wall colour the phone logged at RESULT, or null. */
    private fun wallColor(log: File): WallColor? {
        if (!log.isFile) return null
        val m = Regex("""wallColor=rgb\((\d+), (\d+), (\d+)\) hue=(\d+)±(\d+) sat=([\d.]+)±([\d.]+) val=([\d.]+)\.\.([\d.]+)""").findAll(log.readText()).lastOrNull() ?: return null
        val g = m.groupValues
        return WallColor(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toFloat(), g[6].toFloat(), g[7].toFloat(), g[8].toFloat(), g[9].toFloat())
    }

    private fun readPpm(file: File): Image = DataInputStream(file.inputStream().buffered()).use { s ->
        fun token(): String { val sb = StringBuilder(); while (true) { val c = s.read(); if (c < 0) break; if (Character.isWhitespace(c)) { if (sb.isNotEmpty()) break else continue }; sb.append(c.toChar()) }; return sb.toString() }
        require(token() == "P6"); val w = token().toInt(); val h = token().toInt(); token()
        val buf = ByteArray(w * h * 3); s.readFully(buf)
        Image(w, h, IntArray(w * h) { (0xff shl 24) or ((buf[it * 3].toInt() and 0xff) shl 16) or ((buf[it * 3 + 1].toInt() and 0xff) shl 8) or (buf[it * 3 + 2].toInt() and 0xff) })
    }
}
