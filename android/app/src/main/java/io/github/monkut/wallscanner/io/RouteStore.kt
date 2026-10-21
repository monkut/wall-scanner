package io.github.monkut.wallscanner.io

import android.content.Context
import io.github.monkut.wallscanner.core.RouteDefinition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A route as identified in a capture's scene.json; confirmed routes are the records sends are logged against. */
class RouteRecord(
    val captureId: String,
    val routeId: Int,
    val name: String,
    val grade: String,
    val color: IntArray,
    val holdCount: Int,
    val confirmed: Boolean,
    val type: RouteDefinition,
    val locationId: String,
)

enum class SendResult(val id: String, val label: String) {
    FLASH("flash", "Flash"), SEND("send", "Send"), ATTEMPT("attempt", "Attempt");

    companion object {
        fun fromId(id: String?): SendResult = entries.firstOrNull { it.id == id } ?: SEND
    }
}

/** "1 send", "2 sends": English count independent of the device locale (plurals resources follow the locale). */
fun count(n: Int, singular: String, plural: String = singular + "s"): String = "$n " + if (n == 1) singular else plural

class Send(val id: Int, val routeId: Int, val at: String, val result: SendResult, val attempts: Int, val note: String)

/**
 * Routes come from scene.json (identification, edited in the capture viewer); sends live in the capture's
 * sends.json so that re-editing the identification never touches the log.
 */
object RouteStore {
    const val SENDS_FILE = "sends.json"

    fun routes(dir: File): List<RouteRecord> {
        if (!CaptureStore.hasScene(dir)) return emptyList()
        return runCatching {
            val scene = JSONObject(CaptureStore.readScene(dir))
            val type = RouteDefinition.fromId(scene.optJSONObject("definition")?.optString("type"))
            val locationId = CaptureStore.readLocationId(dir)
            val routes = scene.getJSONArray("routes")
            (0 until routes.length()).map { i ->
                val r = routes.getJSONObject(i)
                val c = r.getJSONArray("color")
                RouteRecord(
                    dir.name, r.getInt("id"), r.optString("name"), r.optString("grade"),
                    intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2)),
                    r.getJSONArray("holdIds").length(), r.optBoolean("confirmed", false), type, locationId,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun route(dir: File, routeId: Int): RouteRecord? = routes(dir).firstOrNull { it.routeId == routeId }

    /** Confirmed routes of every capture, newest capture first. */
    fun confirmedRoutes(context: Context): List<RouteRecord> =
        CaptureStore.list(context).flatMap { dir -> routes(dir).filter { it.confirmed } }

    /** All sends of a capture; a scene.json written before sends.json existed is migrated on first read. */
    fun sends(dir: File): List<Send> {
        val file = File(dir, SENDS_FILE)
        if (!file.exists()) migrateLegacySends(dir)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("sends")
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun sendsFor(dir: File, routeId: Int): List<Send> = sends(dir).filter { it.routeId == routeId }

    fun addSend(dir: File, routeId: Int, result: SendResult, attempts: Int, note: String): Send {
        val existing = sends(dir)
        val send = Send((existing.maxOfOrNull { it.id } ?: 0) + 1, routeId, CaptureStore.nowIso(), result, attempts, note)
        writeSends(dir, existing + send)
        return send
    }

    fun deleteSend(dir: File, sendId: Int) = writeSends(dir, sends(dir).filter { it.id != sendId })

    private fun writeSends(dir: File, sends: List<Send>) {
        val arr = JSONArray()
        for (s in sends) {
            arr.put(JSONObject().put("id", s.id).put("routeId", s.routeId).put("at", s.at)
                .put("result", s.result.id).put("attempts", s.attempts).put("note", s.note))
        }
        File(dir, SENDS_FILE).writeText(JSONObject().put("version", 1).put("sends", arr).toString(2))
    }

    private fun fromJson(o: JSONObject) = Send(
        o.getInt("id"), o.getInt("routeId"), o.optString("at"), SendResult.fromId(o.optString("result")),
        o.optInt("attempts", 1), o.optString("note"),
    )

    /** scene.json v2 kept `sends: [{routeId, at}]`; move them out so the viewer can drop the field. */
    private fun migrateLegacySends(dir: File) {
        if (!CaptureStore.hasScene(dir)) return
        runCatching {
            val legacy = JSONObject(CaptureStore.readScene(dir)).optJSONArray("sends") ?: return
            if (legacy.length() == 0) return
            val sends = (0 until legacy.length()).map { i ->
                val s = legacy.getJSONObject(i)
                Send(i + 1, s.getInt("routeId"), s.optString("at"), SendResult.SEND, 1, "")
            }
            writeSends(dir, sends)
        }
    }
}
