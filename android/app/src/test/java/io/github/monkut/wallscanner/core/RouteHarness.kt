package io.github.monkut.wallscanner.core

import io.github.monkut.wallscanner.io.CaptureStore
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Regroups the holds of every pulled capture's `scene.json` (the phone's holds, with the colours the phone
 * gave them) with the current [RouteGrouper] and prints the stored routes beside the new ones; for a capture
 * with a `reference.json` (the climber's route labels on the same holds) it also scores the new grouping:
 * precision and recall of hold pairs that share a route, and each reference route's dominant new route.
 * `./gradlew :app:testDebugUnitTest --tests '*RouteHarness*' -i | grep ROUTES`.
 */
class RouteHarness {
    @Test
    fun regroupPulledCaptures() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "captures") }.firstOrNull { it.isDirectory } ?: return
        for (dir in root.listFiles { f -> File(f, "scene.json").isFile }!!.sortedBy { it.name }) {
            val scene = JSONObject(File(dir, "scene.json").readText())
            val wall = wallColor(File(dir, "scan.log"))
            val holds = holds(scene)
            // The wall's extent decides whether its grey holds are the down-climb group and where they may stand; a taller
            // pulled scan of the same wall (capture.json `wall`) counts for the height, as on the phone when the wall has no map.
            val extent = scene.optJSONObject("wall")?.optJSONArray("extent")
            val wallHeight = extent?.let { e -> SceneBuilder.wallHeight(FloatArray(4) { e.getDouble(it).toFloat() }, CaptureStore.tallestOtherScan(dir)) }; val wallBottom = extent?.let { it.getDouble(1).toFloat() }
            val routes = RouteGrouper.group(holds, wall = wall, wallHeight = wallHeight, wallBottom = wallBottom)
            println("ROUTES ${dir.name} stored: ${summary(scene)} | new: ${routes.joinToString { "${it.name}(${it.holdIds.size})" }}")
            val storedNames = scene.optJSONArray("routes")?.let { ra -> (0 until ra.length()).map { ra.getJSONObject(it).getString("name") } } ?: emptyList()
            for (r in routes.filter { it.name !in storedNames }) println("ROUTES ${dir.name} new route ${r.name}: holds ${r.holdIds.sorted()}")
            val refFile = File(dir, "reference.json")
            if (!refFile.isFile) continue
            val ref = JSONObject(refFile.readText())
            val refHolds = holds(ref)
            val refRoute = HashMap<Int, Int>()
            val arr = ref.getJSONArray("holds")
            for (i in 0 until arr.length()) refRoute[arr.getJSONObject(i).getInt("id")] = arr.getJSONObject(i).optInt("routeId", -1)
            val newRoutes = RouteGrouper.group(refHolds, wall = wall, wallHeight = wallHeight, wallBottom = wallBottom)
            val newRoute = refHolds.associate { it.id to it.routeId }
            var sameRef = 0; var sameNew = 0; var both = 0
            val ids = refHolds.map { it.id }
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val a = ids[i]; val b = ids[j]
                val r = refRoute[a] != -1 && refRoute[a] == refRoute[b]
                val n = newRoute[a] != -1 && newRoute[a] == newRoute[b]
                if (r) sameRef++; if (n) sameNew++; if (r && n) both++
            }
            val refNames = ref.getJSONArray("routes").let { ra -> (0 until ra.length()).associate { ra.getJSONObject(it).getInt("id") to ra.getJSONObject(it).getString("name") } }
            val newNames = newRoutes.associate { it.id to it.name }
            val per = refNames.entries.mapNotNull { (rid, name) ->
                val members = ids.filter { refRoute[it] == rid }
                if (members.isEmpty()) null else {
                    val counts = members.groupingBy { newNames[newRoute[it]] ?: "-" }.eachCount()
                    val top = counts.maxByOrNull { it.value }!!
                    "$name:${top.key} ${top.value}/${members.size}"
                }
            }
            println("ROUTES ${dir.name} vs reference: pair precision %.2f recall %.2f | %s".format(if (sameNew == 0) 0f else both.toFloat() / sameNew, if (sameRef == 0) 0f else both.toFloat() / sameRef, per.joinToString()))
        }
    }

    private fun summary(scene: JSONObject): String {
        val routes = scene.optJSONArray("routes") ?: return "-"
        val counts = HashMap<Int, Int>()
        val holds = scene.getJSONArray("holds")
        for (i in 0 until holds.length()) counts.merge(holds.getJSONObject(i).optInt("routeId", -1), 1, Int::plus)
        return (0 until routes.length()).joinToString { val r = routes.getJSONObject(it); "${r.getString("name")}(${counts[r.getInt("id")] ?: 0})" }
    }

    private fun holds(scene: JSONObject): List<Hold> {
        val arr = scene.getJSONArray("holds")
        return List(arr.length()) { i ->
            val h = arr.getJSONObject(i)
            val c = h.getJSONArray("center"); val uv = h.getJSONArray("uv"); val col = h.getJSONArray("color"); val size = h.getJSONArray("size")
            Hold(h.getInt("id"), c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), uv.getDouble(0).toFloat(), uv.getDouble(1).toFloat(),
                size.getDouble(0).toFloat(), size.getDouble(1).toFloat(), size.getDouble(2).toFloat(), col.getInt(0), col.getInt(1), col.getInt(2), h.optInt("voxels"))
                .also { it.role = HoldRole.fromId(h.optString("role", "any")) }
        }
    }

    /** The wall colour the phone logged at RESULT (`wallColor=rgb(r, g, b) hue=h±t sat=s±t val=lo..hi`), or null. */
    private fun wallColor(log: File): WallColor? {
        if (!log.isFile) return null
        val m = Regex("""wallColor=rgb\((\d+), (\d+), (\d+)\) hue=(\d+)±(\d+) sat=([\d.]+)±([\d.]+) val=([\d.]+)\.\.([\d.]+)""").findAll(log.readText()).lastOrNull() ?: return null
        val g = m.groupValues
        return WallColor(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toFloat(), g[6].toFloat(), g[7].toFloat(), g[8].toFloat(), g[9].toFloat())
    }
}
