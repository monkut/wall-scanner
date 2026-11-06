package io.github.monkut.wallscanner.core

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Registers the two Oct 29 noborock captures (`captures/20261029-172434` onto `captures/20261029-172214`, the Red
 * route runs from one into the other and the wall turns ~40° between them) and builds the joined scene without a
 * phone: prints the registration, writes `captures/joined-20261029/points.ply` and `scene-new.json`. Skips when the
 * captures are not pulled. `./gradlew :app:testDebugUnitTest --tests '*JoinHarness*' -i | grep JOIN`.
 */
class JoinHarness {
    @Test
    fun joinOct29Pair() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "captures") }.firstOrNull { it.isDirectory } ?: return
        val ref = File(root, "20261029-172214"); val mov = File(root, "20261029-172434")
        if (!File(ref, "points.ply").isFile || !File(mov, "points.ply").isFile) return
        val refCloud = File(ref, "points.ply").inputStream().use { PlyReader.read(it) }
        val movCloud = File(mov, "points.ply").inputStream().use { PlyReader.read(it) }
        val t0 = System.currentTimeMillis()
        val reg = CaptureRegistration.register(refCloud, holds(ref), movCloud, holds(mov))
        println("JOIN registration: ${reg ?: "none"} (${System.currentTimeMillis() - t0} ms)")
        if (reg == null) return
        val map = VoxelMap(maxVoxels = Int.MAX_VALUE)
        map.addAll(refCloud.xyz, refCloud.rgb, refCloud.count)
        val moved = reg.transform.apply(movCloud)
        map.addAll(moved.xyz, moved.rgb, moved.count)
        val joined = map.snapshot()
        val out = File(root, "joined-20261029").apply { mkdirs() }
        File(out, "points.ply").outputStream().buffered().use { PlyWriter.write(joined, it) }
        val result = SceneBuilder.build(joined, emptyList()) ?: run { println("JOIN no wall plane"); return }
        println("JOIN scene: voxels=${joined.count} faces=${SceneJson.facesSummary(result.surface)} holds=${result.holds.size} routes=${result.routes.joinToString { "${it.name}(${it.holdIds.size})" }} extent=${result.extent.joinToString { "%.2f".format(it) }}")
        File(out, "scene-new.json").writeText(SceneJson.write(result, joined.count, "harness", "harness"))
    }

    /**
     * Registers every ordered pair of pulled captures that name the same wall in `capture.json` (joined captures
     * skipped) and prints each outcome: which scans of a wall overlap enough to join, and in which order.
     * `./gradlew :app:testDebugUnitTest --tests '*JoinHarness.registerPairsOfEachWall*' -i | grep PAIR`.
     */
    @Test
    fun registerPairsOfEachWall() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "captures") }.firstOrNull { it.isDirectory } ?: return
        val byWall = root.listFiles { f -> f.isDirectory && File(f, "points.ply").isFile && File(f, "scene.json").isFile }.orEmpty()
            .mapNotNull { dir ->
                val meta = runCatching { JSONObject(File(dir, "capture.json").readText()) }.getOrNull() ?: return@mapNotNull null
                if (meta.has("joined")) return@mapNotNull null
                val wall = meta.optString("wall"); if (wall.isEmpty()) return@mapNotNull null
                "${meta.optString("location")}/$wall" to dir
            }.groupBy({ it.first }, { it.second }).filterValues { it.size >= 2 }
        val clouds = HashMap<File, VoxelCloud>()
        fun cloud(dir: File) = clouds.getOrPut(dir) { File(dir, "points.ply").inputStream().use { PlyReader.read(it) } }
        for ((wall, dirs) in byWall) {
            val sorted = dirs.sortedBy { it.name }
            println("PAIR $wall: ${sorted.joinToString { it.name }}")
            for (ref in sorted) for (mov in sorted) {
                if (ref === mov) continue
                val t0 = System.currentTimeMillis()
                val reg = CaptureRegistration.register(cloud(ref), holds(ref), cloud(mov), holds(mov))
                println("PAIR $wall ${mov.name} onto ${ref.name}: ${reg ?: "none"} (${System.currentTimeMillis() - t0} ms)")
            }
        }
    }

    private fun holds(dir: File): List<HoldPoint> {
        val arr = JSONObject(File(dir, "scene.json").readText()).getJSONArray("holds")
        return List(arr.length()) { i ->
            val h = arr.getJSONObject(i)
            val c = h.getJSONArray("center"); val col = h.getJSONArray("color"); val size = h.getJSONArray("size")
            val hsv = ColorUtil.rgbToHsv(col.getInt(0), col.getInt(1), col.getInt(2))
            HoldPoint(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), hsv[0], hsv[1], hsv[2], maxOf(size.getDouble(0), size.getDouble(1)).toFloat())
        }
    }
}
