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
    /** The gym set the capture belongs to; "" when none. */
    val setId: String = "",
)

enum class SendResult(val id: String) {
    FLASH("flash"), SEND("send"), ATTEMPT("attempt");

    companion object {
        fun fromId(id: String?): SendResult = entries.firstOrNull { it.id == id } ?: SEND
    }
}

/** How a filmed attempt ended, when the climber says (or the detector suggests and the climber keeps it). */
enum class ClipOutcome(val id: String) {
    TOP("top"), FALL("fall");

    companion object {
        fun fromId(id: String?): ClipOutcome? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One filmed attempt: [file] (relative to the capture directory) played from [trimStartMs] to [trimEndMs]. A
 * session recording holds several attempts, so several clips can share one file; [auto] marks clips the attempt
 * detector cut out of one. [attempt] is the attempt number when filmed and is not renumbered after a delete.
 */
class Clip(
    val id: Int, val attempt: Int, val file: String, val durationMs: Long,
    val trimStartMs: Long, val trimEndMs: Long, val outcome: ClipOutcome?, val auto: Boolean, val at: String,
) {
    fun with(trimStartMs: Long = this.trimStartMs, trimEndMs: Long = this.trimEndMs, outcome: ClipOutcome? = this.outcome): Clip =
        Clip(id, attempt, file, durationMs, trimStartMs, trimEndMs, outcome, auto, at).clamped()

    /** Trim points inside the file, the in-point before the out-point (at least [MIN_CLIP_MS] apart when the file allows). */
    fun clamped(): Clip {
        val end = trimEndMs.coerceIn(0, durationMs)
        val start = trimStartMs.coerceIn(0, maxOf(0, end - MIN_CLIP_MS))
        return if (start == trimStartMs && end == trimEndMs) this else Clip(id, attempt, file, durationMs, start, end, outcome, auto, at)
    }

    companion object {
        const val MIN_CLIP_MS = 500L
    }
}

/** A climb: one session on one route (result, tries, note) and the attempts filmed in it. */
class Send(val id: Int, val routeId: Int, val at: String, val result: SendResult, val attempts: Int, val note: String, val clips: List<Clip> = emptyList())

/** One capture's log: every send, the climber's star rating per route id (absent = not rated) and route comments. */
class SendLog(val sends: List<Send>, val ratings: Map<Int, Int>, val comments: Map<Int, String> = emptyMap())

/**
 * Routes come from scene.json (identification, edited in the capture viewer); sends, the climber's star ratings
 * and route comments live in the capture's sends.json (`{ version: 2, sends: [{…, clips: […]}], ratings:
 * { "<routeId>": 0..5 }, comments: { "<routeId>": text } }`) so that re-editing the identification never touches
 * the log. Version-1 files (no clips, no comments) read unchanged. Clip files live under `climbs/<sendId>/`.
 */
object RouteStore {
    const val SENDS_FILE = "sends.json"
    const val MAX_RATING = 5
    const val CLIMBS_DIR = "climbs"

    fun routes(dir: File): List<RouteRecord> {
        if (!CaptureStore.hasScene(dir)) return emptyList()
        return runCatching {
            val scene = JSONObject(CaptureStore.readScene(dir))
            val type = RouteDefinition.fromId(scene.optJSONObject("definition")?.optString("type"))
            val place = CaptureStore.readPlace(dir)
            val routes = scene.getJSONArray("routes")
            (0 until routes.length()).map { i ->
                val r = routes.getJSONObject(i)
                val c = r.getJSONArray("color")
                RouteRecord(
                    dir.name, r.getInt("id"), r.optString("name"), r.optString("grade"),
                    GradeSystem.fromId(r.optString("gradeSystem")),
                    intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2)),
                    r.getJSONArray("holdIds").length(), r.optBoolean("confirmed", false), type, place.locationId, place.setId,
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

    private val EMPTY = SendLog(emptyList(), emptyMap(), emptyMap())

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
            val comments = HashMap<Int, String>()
            json.optJSONObject("comments")?.let { c ->
                for (key in c.keys()) {
                    val routeId = key.toIntOrNull() ?: continue
                    c.optString(key).takeIf { it.isNotBlank() }?.let { comments[routeId] = it }
                }
            }
            SendLog((0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }, ratings, comments)
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
        write(dir, log.copy(ratings = ratings))
    }

    /** The climber's comment on a route (beta, conditions), or null when there is none. */
    fun comment(dir: File, routeId: Int): String? = log(dir).comments[routeId]

    /** Sets or, with a blank text, clears the comment on a route. */
    fun setComment(dir: File, routeId: Int, text: String) {
        val log = log(dir)
        val comments = log.comments.toMutableMap()
        if (text.isBlank()) comments.remove(routeId) else comments[routeId] = text.trim()
        write(dir, log.copy(comments = comments))
    }

    fun sendsFor(dir: File, routeId: Int): List<Send> = sends(dir).filter { it.routeId == routeId }

    /**
     * Logs a climb. Clip files recorded before the climb had an id ([pendingDir], relative to the capture) move to
     * `climbs/<sendId>/` and the clips are rewritten to point there.
     */
    fun addSend(dir: File, routeId: Int, result: SendResult, attempts: Int, note: String, clips: List<Clip> = emptyList(), pendingDir: String? = null): Send {
        val log = log(dir)
        val id = (log.sends.maxOfOrNull { it.id } ?: 0) + 1
        val moved = if (pendingDir == null) clips else {
            val target = File(dir, clipDir(id)).apply { mkdirs() }
            val renamed = HashMap<String, String>()
            for (file in clips.map { it.file }.distinct()) {
                val src = File(dir, file)
                val dst = File(target, src.name)
                renamed[file] = if (src.exists() && src.renameTo(dst)) dst.relativeTo(dir).path else file
                FaceStore.sidecar(src).takeIf { it.exists() }?.renameTo(FaceStore.sidecar(dst)) // the faces seen in it travel with the video
            }
            File(dir, pendingDir).delete() // only when empty
            clips.map { c -> Clip(c.id, c.attempt, renamed[c.file] ?: c.file, c.durationMs, c.trimStartMs, c.trimEndMs, c.outcome, c.auto, c.at) }
        }
        val send = Send(id, routeId, CaptureStore.nowIso(), result, attempts, note, moved.map { it.clamped() })
        write(dir, log.copy(sends = log.sends + send))
        return send
    }

    /** Deletes a climb and its clip files. */
    fun deleteSend(dir: File, sendId: Int) {
        val log = log(dir)
        write(dir, log.copy(sends = log.sends.filter { it.id != sendId }))
        File(dir, clipDir(sendId)).deleteRecursively()
    }

    /** Replaces a clip of a climb (trim points clamped to its file); no-op when either is gone. */
    fun updateClip(dir: File, sendId: Int, clip: Clip) {
        val log = log(dir)
        write(dir, log.copy(sends = log.sends.map { s -> if (s.id != sendId) s else s.withClips(s.clips.map { if (it.id == clip.id) clip.clamped() else it }) }))
    }

    /** Removes a clip; its file goes too unless another clip of the climb still plays from it (a session recording). */
    fun deleteClip(dir: File, sendId: Int, clipId: Int) {
        val log = log(dir)
        val send = log.sends.firstOrNull { it.id == sendId } ?: return
        val clip = send.clips.firstOrNull { it.id == clipId } ?: return
        val rest = send.clips.filter { it.id != clipId }
        write(dir, log.copy(sends = log.sends.map { if (it.id == sendId) it.withClips(rest) else it }))
        if (rest.none { it.file == clip.file }) { File(dir, clip.file).delete(); FaceStore.sidecar(File(dir, clip.file)).delete() }
    }

    /** Where a climb's clip files live, relative to the capture directory. */
    fun clipDir(sendId: Int): String = "$CLIMBS_DIR/$sendId"

    /** Where clips recorded before the climb is saved live, relative to the capture directory. */
    const val PENDING_DIR = "$CLIMBS_DIR/pending"

    private fun Send.withClips(clips: List<Clip>) = Send(id, routeId, at, result, attempts, note, clips)

    private fun SendLog.copy(sends: List<Send> = this.sends, ratings: Map<Int, Int> = this.ratings, comments: Map<Int, String> = this.comments) =
        SendLog(sends, ratings, comments)

    private fun write(dir: File, log: SendLog) {
        val arr = JSONArray()
        for (s in log.sends) {
            val clips = JSONArray()
            for (c in s.clips) {
                clips.put(JSONObject().put("id", c.id).put("attempt", c.attempt).put("file", c.file).put("durationMs", c.durationMs)
                    .put("trimStartMs", c.trimStartMs).put("trimEndMs", c.trimEndMs).put("outcome", c.outcome?.id ?: JSONObject.NULL)
                    .put("auto", c.auto).put("at", c.at))
            }
            val o = JSONObject().put("id", s.id).put("routeId", s.routeId).put("at", s.at)
                .put("result", s.result.id).put("attempts", s.attempts).put("note", s.note)
            if (s.clips.isNotEmpty()) o.put("clips", clips)
            arr.put(o)
        }
        val rated = JSONObject()
        for ((routeId, stars) in log.ratings.toSortedMap()) rated.put(routeId.toString(), stars)
        val comments = JSONObject()
        for ((routeId, text) in log.comments.toSortedMap()) comments.put(routeId.toString(), text)
        File(dir, SENDS_FILE).writeText(JSONObject().put("version", 2).put("sends", arr).put("ratings", rated).put("comments", comments).toString(2))
    }

    private fun fromJson(o: JSONObject): Send {
        val clips = o.optJSONArray("clips")?.let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Clip(
                    c.getInt("id"), c.optInt("attempt", i + 1), c.getString("file"), c.optLong("durationMs"),
                    c.optLong("trimStartMs"), c.optLong("trimEndMs", c.optLong("durationMs")),
                    ClipOutcome.fromId(if (c.isNull("outcome")) null else c.optString("outcome")), c.optBoolean("auto"), c.optString("at"),
                ).clamped()
            }
        } ?: emptyList()
        return Send(
            o.getInt("id"), o.getInt("routeId"), o.optString("at"), SendResult.fromId(o.optString("result")),
            o.optInt("attempts", 1), o.optString("note"), clips,
        )
    }

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
            write(dir, SendLog(sends, emptyMap(), emptyMap()))
        }
    }
}
