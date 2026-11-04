package io.github.monkut.wallscanner.io

import io.github.monkut.wallscanner.core.Box
import io.github.monkut.wallscanner.core.FaceBox
import io.github.monkut.wallscanner.core.FaceFrame
import io.github.monkut.wallscanner.core.FaceRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * The faces seen while a climb video was filmed, beside its file as `<name>.faces.json`
 * (`{ version: 1, frames: [{ t, faces: [{ box: [x, y, w, h], role: climber|bystander }] }] }`, boxes normalised
 * to the upright frame). The video itself is never changed; masks are drawn from this file on playback and
 * burnt into shared copies. The sidecar moves and goes with its video ([RouteStore]).
 */
object FaceStore {
    const val SUFFIX = ".faces.json"

    /** The sidecar of a video file (`attempt-01.mp4` → `attempt-01.faces.json`). */
    fun sidecar(video: File): File = File(video.parentFile, video.name.substringBeforeLast('.') + SUFFIX)

    fun write(video: File, frames: List<FaceFrame>) {
        val arr = JSONArray()
        for (f in frames) {
            val faces = JSONArray()
            for (face in f.faces) faces.put(JSONObject().put("box", JSONArray(listOf(face.box.x, face.box.y, face.box.w, face.box.h).map { round(it) })).put("role", face.role.id))
            arr.put(JSONObject().put("t", f.tMs).put("faces", faces))
        }
        sidecar(video).writeText(JSONObject().put("version", 1).put("frames", arr).toString())
    }

    /** The frames of a video's sidecar, in time order; empty without one (filmed before face masks existed, or masks off). */
    fun read(video: File): List<FaceFrame> {
        val file = sidecar(video)
        if (!file.exists()) return emptyList()
        return runCatching {
            val frames = JSONObject(file.readText()).getJSONArray("frames")
            List(frames.length()) { i ->
                val f = frames.getJSONObject(i)
                val faces = f.getJSONArray("faces")
                FaceFrame(f.getLong("t"), List(faces.length()) { k ->
                    val o = faces.getJSONObject(k)
                    val b = o.getJSONArray("box")
                    FaceBox(Box(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()), FaceRole.fromId(o.optString("role")))
                })
            }.sortedBy { it.tMs }
        }.getOrDefault(emptyList())
    }

    private fun round(v: Float): Double = (v * 10_000).roundToInt() / 10_000.0
}
