package io.github.monkut.wallscanner.io

import android.content.Context
import io.github.monkut.wallscanner.core.GradeSystem
import io.github.monkut.wallscanner.core.HoldRole
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
    val gradeSystem: GradeSystem?,
    val color: IntArray,
    val holdCount: Int,
    val confirmed: Boolean,
    val type: RouteDefinition,
    val locationId: String,
)

enum class SendResult(val id: String) {
    FLASH("flash"), SEND("send"), ATTEMPT("attempt");

    companion object {
        fun fromId(id: String?): SendResult = entries.firstOrNull { it.id == id } ?: SEND
    }
}

class Send(val id: Int, val routeId: Int, val at: String, val result: SendResult, val attempts: Int, val note: String)

/** One capture's log: every send plus the climber's star rating per route id (absent = not rated). */
class SendLog(val sends: List<Send>, val ratings: Map<Int, Int>)

/**
 * Routes come from scene.json (identification, edited in the capture viewer); sends and the climber's star
 * ratings live in the capture's sends.json (`{ version, sends: [...], ratings: { "<routeId>": 0..5 } }`) so
 * that re-editing the identification never touches the log.
 */
object RouteStore {
    const val SENDS_FILE = "sends.json"
    const val MAX_RATING = 5

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
                    GradeSystem.fromId(r.optString("gradeSystem")),
                    intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2)),
                    r.getJSONArray("holdIds").length(), r.optBoolean("confirmed", false), type, locationId,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun route(dir: File, routeId: Int): RouteRecord? = routes(dir).firstOrNull { it.routeId == routeId }

    /** Sets a hold's role (start, finish, …) in scene.json; the viewer shows and can change the same roles. */
    fun setHoldRole(dir: File, holdId: Int, role: HoldRole) {
        val scene = JSONObject(CaptureStore.readScene(dir))
        val holds = scene.getJSONArray("holds")
        for (i in 0 until holds.length()) {
            val h = holds.getJSONObject(i)
            if (h.getInt("id") == holdId) h.put("role", role.id)
        }
        CaptureStore.writeScene(dir, scene.toString(1))
    }

    /** Confirmed routes of every capture, newest capture first. */
    fun confirmedRoutes(context: Context): List<RouteRecord> =
        CaptureStore.list(context).flatMap { dir -> routes(dir).filter { it.confirmed } }

    private val EMPTY = SendLog(emptyList(), emptyMap())

    /**
     * A capture's log in one read; a scene.json written before sends.json existed is migrated first. A rating
     * that is not an integer in 0..[MAX_RATING] is skipped on its own so it can never take the sends with it.
     */
    fun log(dir: File): SendLog {
        val file = File(dir, SENDS_FILE)
        if (!file.exists()) migrateLegacySends(dir)
        if (!file.exists()) return EMPTY
        return runCatching {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("sends")
            val ratings = HashMap<Int, Int>()
            json.optJSONObject("ratings")?.let { r ->
                for (key in r.keys()) {
                    val routeId = key.toIntOrNull() ?: continue
                    val stars = r.optInt(key, -1)
                    if (stars in 0..MAX_RATING) ratings[routeId] = stars
                }
            }
            SendLog((0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }, ratings)
        }.getOrDefault(EMPTY)
    }

    /** All sends of a capture. */
    fun sends(dir: File): List<Send> = log(dir).sends

    /** The climber's 0–5 star rating of a route, or null when not rated. */
    fun rating(dir: File, routeId: Int): Int? = log(dir).ratings[routeId]

    /** Sets or, with null, clears the star rating of a route. */
    fun setRating(dir: File, routeId: Int, stars: Int?) {
        val log = log(dir)
        val ratings = log.ratings.toMutableMap()
        if (stars == null) ratings.remove(routeId) else ratings[routeId] = stars.coerceIn(0, MAX_RATING)
        write(dir, log.sends, ratings)
    }

    fun sendsFor(dir: File, routeId: Int): List<Send> = sends(dir).filter { it.routeId == routeId }

    fun addSend(dir: File, routeId: Int, result: SendResult, attempts: Int, note: String): Send {
        val log = log(dir)
        val send = Send((log.sends.maxOfOrNull { it.id } ?: 0) + 1, routeId, CaptureStore.nowIso(), result, attempts, note)
        write(dir, log.sends + send, log.ratings)
        return send
    }

    fun deleteSend(dir: File, sendId: Int) {
        val log = log(dir)
        write(dir, log.sends.filter { it.id != sendId }, log.ratings)
    }

    private fun write(dir: File, sends: List<Send>, ratings: Map<Int, Int>) {
        val arr = JSONArray()
        for (s in sends) {
            arr.put(JSONObject().put("id", s.id).put("routeId", s.routeId).put("at", s.at)
                .put("result", s.result.id).put("attempts", s.attempts).put("note", s.note))
        }
        val rated = JSONObject()
        for ((routeId, stars) in ratings.toSortedMap()) rated.put(routeId.toString(), stars)
        File(dir, SENDS_FILE).writeText(JSONObject().put("version", 1).put("sends", arr).put("ratings", rated).toString(2))
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
            write(dir, sends, emptyMap())
        }
    }
}
