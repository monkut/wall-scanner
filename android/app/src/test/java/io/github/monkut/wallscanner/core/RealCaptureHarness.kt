package io.github.monkut.wallscanner.core

import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Rebuilds the scene of every capture pulled into the repo's git-ignored `captures/` directory with the
 * current pipeline (plane, holds, routes, orthophoto from the keyframes, image-based hold shapes) and writes
 * `scene-new.json` and `wall-new.ppm` next to each (the viewer and `tools/` read the originals). Compare hold
 * and route counts, or overlay the outlines with the scratchpad scripts, before and after a detector change
 * without a phone. The orthophoto stage needs the keyframes decoded first (`uv run tools/frames_to_ppm.py
 * captures/<id>`), because unit tests compile against android.jar, which has no JPEG decoder. A run with
 * no pulled captures passes without doing anything. Prints one `HARNESS` line per stage with `./gradlew … -i`.
 */
class RealCaptureHarness {
    @Test
    fun rebuildPulledCaptures() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "captures") }.firstOrNull { it.isDirectory } ?: return
        val only = System.getenv("HARNESS_ONLY") // one capture id, to rebuild just that one
        val captures = root.listFiles { f -> File(f, "points.ply").isFile && File(f, "frames.json").isFile && (only == null || f.name == only) }?.sortedBy { it.name } ?: return
        for (dir in captures) {
            val cloud = File(dir, "points.ply").inputStream().use { PlyReader.read(it) }
            val framesJson = File(dir, "frames.json").readText()
            val cameras = Regex(""""worldFromCamera":\{"r":\[[^\]]*\],"t":\[([^\]]*)\]""")
                .findAll(framesJson)
                .map { m -> m.groupValues[1].split(',').map { it.trim().toFloat() }.toFloatArray() }
                .toList()
            val type = Regex(""""type":\s*"([a-z_]+)"""").find(File(dir, "capture.json").takeIf { it.isFile }?.readText() ?: "")
                ?.groupValues?.get(1)
            val t0 = System.currentTimeMillis()
            var result = SceneBuilder.build(cloud, cameras, definition = Definition(RouteDefinition.fromId(type)))
            val ms = System.currentTimeMillis() - t0
            if (result == null) { println("HARNESS ${dir.name} voxels=${cloud.count} no wall plane"); continue }
            println("HARNESS ${dir.name} faces=${SceneJson.facesSummary(result.surface)} regions=${Facets.find(cloud, result.frame).summary}")
            result.thinCoverage = Coverage.thinCells(cloud, result.surface, result.extent)
            if (result.thinCoverage.isNotEmpty()) println("HARNESS ${dir.name} thin coverage: ${result.thinCoverage.joinToString { "(row ${it.row}, col ${it.col})" }}")
            println(
                "HARNESS ${dir.name} voxels=${cloud.count} inliers=${result.planeInliers} wallColor=${result.wallColor} " +
                    "holds=${result.holds.size} unassigned=${result.holds.count { it.routeId == -1 }} " +
                    "routes=${result.routes.joinToString { "${it.name}(${it.holdIds.size})" }} ($ms ms)"
            )
            val keyframes = keyframes(framesJson)
            val ppm = File(dir, "frames-ppm")
            val t1 = System.currentTimeMillis()
            val photo = if (ppm.isDirectory) OrthophotoBuilder.build(cloud, result.surface, result.extent, keyframes, decode = { k -> readPpm(File(ppm, File(k.file).nameWithoutExtension + ".ppm")) }) else null
            if (photo != null) {
                println("HARNESS ${dir.name} orthophoto ${photo.image.width}x${photo.image.height} from ${keyframes.size} keyframes (${System.currentTimeMillis() - t1} ms)")
                val t2 = System.currentTimeMillis()
                val patches = ColorPatches.forHolds(photo, result.wallColor)
                val refined = HoldShapes.refine(result.holds, photo, patches, result.wallColor)
                println("HARNESS ${dir.name} hold shapes: ${result.holds.size} → ${refined.size} refined (${System.currentTimeMillis() - t2} ms)")
                val t3 = System.currentTimeMillis()
                val extra = HoldSearch.find(refined, patches, photo, result.surface, result.extent)
                val split = HoldSplit.byColor(refined + extra, photo, patches, result.surface, result.wallColor)
                if (split.size != refined.size + extra.size) println("HARNESS ${dir.name} hold split: ${refined.size + extra.size} → ${split.size} by photo colour")
                // The JVM cannot run the hold model: holds are judged by colour alone here.
                val pruned = HoldMerge.prune(split, emptyList(), emptyList(), photo, patches, result.wallColor, result.extent)
                println("HARNESS ${dir.name} hold prune: ${split.size - pruned.size} dropped: ${(split - pruned.toSet()).joinToString { "#${it.id} (%.2f, %.2f) %.2f×%.2f vox=${it.voxels} rgb=(${it.r},${it.g},${it.b})".format(it.u, it.v, it.width, it.tall) }}")
                // The app searches again on SAM's final outlines; here the outlines are the shapes', so this mostly finds nothing.
                val uncovered = HoldMerge.prune(HoldSearch.find(pruned, patches, photo, result.surface, result.extent, minSide = HoldSearch.SECOND_MIN_SIDE), emptyList(), emptyList(), photo, patches, result.wallColor, result.extent)
                if (uncovered.isNotEmpty()) println("HARNESS ${dir.name} hold search (after outlines): +${uncovered.size}: ${uncovered.joinToString { "#${it.id} (%.2f, %.2f) %.2f×%.2f".format(it.u, it.v, it.width, it.tall) }}")
                val candidates = pruned + uncovered
                val unnested = HoldMerge.nested(candidates, photo)
                if (unnested.size != candidates.size) println("HARNESS ${dir.name} hold nested: ${candidates.size - unnested.size} merged: ${(candidates.map { it.id } - unnested.map { it.id }.toSet()).joinToString { "#$it" }}")
                val holds = HoldMerge.dropOnSteps(unnested, result.steps, photo)
                if (holds.size != unnested.size) println("HARNESS ${dir.name} hold steps: ${unnested.size - holds.size} image-only holds on a step dropped: ${(unnested - holds.toSet()).joinToString { "#${it.id} (%.2f, %.2f) %.2f×%.2f".format(it.u, it.v, it.width, it.tall) }}")
                val starts = TapeMarks.markStarts(holds, photo, result.wallColor, patches, result.extent)
                result.photo = photo
                result = SceneBuilder.withHolds(result, holds)
                println(
                    "HARNESS ${dir.name} hold search: +${extra.size} image-only holds, $starts tape starts (${System.currentTimeMillis() - t3} ms) → holds=${result.holds.size} " +
                        "starts=${result.holds.filter { it.role == HoldRole.START }.map { it.id }} routes=${result.routes.joinToString { "${it.name}(${it.holdIds.size})" }}"
                )
                writePpm(File(dir, "wall-new.ppm"), photo.image)
                // Which keyframe each pixel came from, one colour per keyframe: the seams of the stitching.
                photo.source?.let { src -> writePpm(File(dir, "wall-source.ppm"), Image(photo.image.width, photo.image.height, IntArray(src.size) { if (src[it] < 0) 0 else (src[it] * 2654435761L).toInt() or 0x404040 })) }
            } else {
                println("HARNESS ${dir.name} orthophoto: skipped (${if (ppm.isDirectory) "no usable keyframes" else "no frames-ppm/; run tools/frames_to_ppm.py"})")
            }
            File(dir, "scene-new.json").writeText(SceneJson.write(result, cloud.count, "harness", "harness"))
        }
    }

    private fun keyframes(framesJson: String): List<Keyframe> =
        Regex(""""file":\s*"([^"]+)".*?"intrinsics":\{"fx":([-\d.eE]+),"fy":([-\d.eE]+),"cx":([-\d.eE]+),"cy":([-\d.eE]+),"width":(\d+),"height":(\d+)\}.*?"worldFromCamera":\{"r":\[([^\]]*)\],"t":\[([^\]]*)\]""")
            .findAll(framesJson).map { m ->
                val g = m.groupValues
                Keyframe(
                    g[1].replace("\\/", "/"), Rigid(g[8].split(',').map { it.trim().toDouble() }.toDoubleArray(), g[9].split(',').map { it.trim().toDouble() }.toDoubleArray()),
                    g[2].toFloat(), g[3].toFloat(), g[4].toFloat(), g[5].toFloat(), g[6].toInt(), g[7].toInt(),
                )
            }.toList()

    /** Binary PPM (P6, maxval 255) as written by tools/frames_to_ppm.py. */
    private fun readPpm(file: File): Image? {
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { s ->
            fun token(): String {
                val sb = StringBuilder()
                while (true) {
                    val c = s.read()
                    if (c < 0) break
                    if (c == '#'.code) { while (s.read().let { it >= 0 && it != '\n'.code }) Unit; continue }
                    if (Character.isWhitespace(c)) { if (sb.isNotEmpty()) break else continue }
                    sb.append(c.toChar())
                }
                return sb.toString()
            }
            if (token() != "P6") return null
            val w = token().toInt(); val h = token().toInt(); token() // maxval
            val bytes = ByteArray(w * h * 3)
            s.readFully(bytes)
            return Image(w, h, IntArray(w * h) { i ->
                (0xff shl 24) or ((bytes[i * 3].toInt() and 0xff) shl 16) or ((bytes[i * 3 + 1].toInt() and 0xff) shl 8) or (bytes[i * 3 + 2].toInt() and 0xff)
            })
        }
    }

    private fun writePpm(file: File, image: Image) {
        file.outputStream().buffered().use { out ->
            out.write("P6\n${image.width} ${image.height}\n255\n".toByteArray())
            val bytes = ByteArray(image.width * image.height * 3)
            for (i in 0 until image.width * image.height) {
                val p = image.pixels[i]
                bytes[i * 3] = (p shr 16).toByte(); bytes[i * 3 + 1] = (p shr 8).toByte(); bytes[i * 3 + 2] = p.toByte()
            }
            out.write(bytes)
        }
    }
}
