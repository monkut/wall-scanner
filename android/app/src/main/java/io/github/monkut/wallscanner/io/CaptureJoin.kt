package io.github.monkut.wallscanner.io

import android.content.Context
import io.github.monkut.wallscanner.core.CaptureRegistration
import io.github.monkut.wallscanner.core.ColorUtil
import io.github.monkut.wallscanner.core.HoldPoint
import io.github.monkut.wallscanner.core.PlyReader
import io.github.monkut.wallscanner.core.Registration
import io.github.monkut.wallscanner.core.Rigid
import io.github.monkut.wallscanner.core.VoxelMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Joins two captures of the same wall into a new one (#7, #8 phase 3): the second is registered onto the first
 * ([CaptureRegistration]), their clouds and keyframes are merged into a new capture directory in the first's
 * world, and the normal pipeline runs on it ([CaptureStore.reprocess]), so a route that runs past the edge of one
 * scan into the next is one route, and a wall that bends between the two becomes two faces. The originals stay
 * as they are; the joined capture's `capture.json` names them in `joined` with the registration.
 */
object CaptureJoin {
    class Outcome(val dir: File?, val registration: Registration?, val summary: String?)

    fun join(context: Context, refDir: File, movDir: File, log: (String) -> Unit = {}): Outcome {
        val refCloud = File(refDir, CaptureStore.PLY_FILE).inputStream().use { PlyReader.read(it) }
        val movCloud = File(movDir, CaptureStore.PLY_FILE).inputStream().use { PlyReader.read(it) }
        val registration = CaptureRegistration.register(refCloud, holds(refDir), movCloud, holds(movDir))
        log("JOIN ${movDir.name} onto ${refDir.name}: ${registration ?: "no matching holds"}")
        if (registration == null || !registration.accepted) return Outcome(null, registration, null)
        val dir = CaptureStore.create(context)
        // Cloud: the reference as is, the moving one carried over.
        val map = VoxelMap(maxVoxels = Int.MAX_VALUE)
        map.addAll(refCloud.xyz, refCloud.rgb, refCloud.count)
        val moved = registration.transform.apply(movCloud)
        map.addAll(moved.xyz, moved.rgb, moved.count)
        CaptureStore.writePly(dir, map.snapshot())
        // Keyframes: both sets of JPEGs, the moving capture's poses carried over.
        val frames = JSONArray()
        File(dir, CaptureStore.FRAMES_DIR).mkdirs()
        copyFrames(refDir, dir, "", Rigid.IDENTITY, frames)
        copyFrames(movDir, dir, "b-", registration.transform, frames)
        File(dir, CaptureStore.FRAMES_RESOLVED).writeText(JSONObject().put("frames", frames).toString())
        // capture.json: the reference's place and definition, plus where it came from.
        val meta = runCatching { JSONObject(File(refDir, CaptureStore.META_FILE).readText()) }.getOrDefault(JSONObject().put("version", 2))
        meta.put("createdAt", CaptureStore.nowIso())
            .put("joined", JSONArray(listOf(refDir.name, movDir.name)))
            .put("registration", JSONObject()
                .put("yawDeg", registration.yawDeg).put("rmse", registration.rmse)
                .put("holdMatches", registration.holdMatches).put("icpPairs", registration.icpPairs)
                .put("transform", JSONObject().put("r", JSONArray(registration.transform.r.toList())).put("t", JSONArray(registration.transform.t.toList()))))
        File(dir, CaptureStore.META_FILE).writeText(meta.toString(2))
        ScanLog(File(dir, ScanLog.FILE)).apply {
            log("JOIN ${refDir.name} + ${movDir.name}: $registration · voxels ${refCloud.count} + ${movCloud.count} → ${map.snapshot().count} · keyframes ${frames.length()}")
            close()
        }
        val summary = CaptureStore.reprocess(context, dir)
        return Outcome(dir, registration, summary)
    }

    /** The holds of a capture's scene as registration input; empty without a scene. */
    fun holds(dir: File): List<HoldPoint> {
        if (!CaptureStore.hasScene(dir)) return emptyList()
        return runCatching {
            val arr = JSONObject(CaptureStore.readScene(dir)).getJSONArray("holds")
            List(arr.length()) { i ->
                val h = arr.getJSONObject(i)
                val c = h.getJSONArray("center"); val col = h.getJSONArray("color"); val size = h.getJSONArray("size")
                val hsv = ColorUtil.rgbToHsv(col.getInt(0), col.getInt(1), col.getInt(2))
                HoldPoint(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), hsv[0], hsv[1], hsv[2], maxOf(size.getDouble(0), size.getDouble(1)).toFloat())
            }
        }.getOrDefault(emptyList())
    }

    /** The capture ids a joined capture was made from; empty for an ordinary capture. */
    fun joinedFrom(dir: File): List<String> = runCatching {
        val arr = JSONObject(File(dir, CaptureStore.META_FILE).readText()).optJSONArray("joined") ?: return emptyList()
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** Copies [src]'s keyframe JPEGs into [dst] under [prefix] and appends their entries, poses composed with [carry]. */
    private fun copyFrames(src: File, dst: File, prefix: String, carry: Rigid, out: JSONArray) {
        val file = File(src, CaptureStore.FRAMES_RESOLVED)
        if (!file.exists()) return
        val frames = runCatching { JSONObject(file.readText()).getJSONArray("frames") }.getOrNull() ?: return
        for (i in 0 until frames.length()) {
            val f = JSONObject(frames.getJSONObject(i).toString())
            val name = File(f.getString("file")).name
            val from = File(src, f.getString("file"))
            if (!from.exists()) continue
            val to = File(File(dst, CaptureStore.FRAMES_DIR), prefix + name)
            from.copyTo(to, overwrite = true)
            f.put("file", "${CaptureStore.FRAMES_DIR}/${prefix + name}")
            val pose = f.getJSONObject("worldFromCamera")
            val r = pose.getJSONArray("r"); val t = pose.getJSONArray("t")
            val world = carry.compose(Rigid(DoubleArray(9) { r.getDouble(it) }, DoubleArray(3) { t.getDouble(it) }))
            f.put("worldFromCamera", JSONObject().put("r", JSONArray(world.r.toList())).put("t", JSONArray(world.t.toList())))
            out.put(f)
        }
    }
}
