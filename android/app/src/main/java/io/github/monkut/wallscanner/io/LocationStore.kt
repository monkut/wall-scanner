package io.github.monkut.wallscanner.io

import android.content.Context
import io.github.monkut.wallscanner.core.GradeSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class LocationKind(val id: String) {
    GYM("gym"), OUTDOOR("outdoor");

    companion object {
        fun fromId(id: String?): LocationKind = entries.firstOrNull { it.id == id } ?: GYM
    }
}

/** A GPS fix: WGS84 degrees, horizontal accuracy in metres, ISO time with offset. */
class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Float, val at: String) {
    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("accuracyM", accuracyM.toDouble()).put("at", at)

    /** "35.656718, 139.703624", as [parse] reads it. */
    fun text(): String = String.format(Locale.US, "%.6f, %.6f", lat, lon)

    /** Entered by hand rather than measured by the device. */
    val manual: Boolean get() = accuracyM <= MANUAL_ACCURACY

    override fun toString(): String = if (manual) text() else String.format(Locale.US, "%s (±%.0f m)", text(), accuracyM)

    companion object {
        /** Entered by hand (no device accuracy). */
        const val MANUAL_ACCURACY = 0f

        /** "35.6567, 139.7036" (decimal degrees, comma or space separated) to a hand-entered point; null if not. */
        fun parse(text: String, at: String): GeoPoint? {
            val parts = text.trim().split(Regex("[,\\s]+"))
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return GeoPoint(lat, lon, MANUAL_ACCURACY, at)
        }

        fun fromJson(o: JSONObject?): GeoPoint? {
            if (o == null || !o.has("lat") || !o.has("lon")) return null
            return GeoPoint(o.getDouble("lat"), o.getDouble("lon"), o.optDouble("accuracyM", 0.0).toFloat(), o.optString("at"))
        }
    }
}

/**
 * A gym's route set: the routes on a wall between two resets ("October Set"). A set is retired when the wall
 * is reset; its routes and sends stay in the logbook under it. Outdoor areas have no sets.
 */
class WallSet(val id: String, val name: String, val createdAt: String, val retiredAt: String? = null) {
    val active: Boolean get() = retiredAt == null

    companion object {
        /** The set a new scan at the location goes to: the newest active one, or null when none is open. */
        fun current(sets: List<WallSet>): WallSet? = sets.filter { it.active }.maxByOrNull { it.createdAt }

        /** Display order: active sets newest first, then retired ones newest first. */
        fun ordered(sets: List<WallSet>): List<WallSet> = sets.sortedWith(compareBy<WallSet> { !it.active }.thenByDescending { it.createdAt })
    }
}

/** A gym or outdoor area. Captures (and so routes) belong to one location, and at a gym to one of its sets. */
class Location(
    val id: String,
    val name: String,
    val kind: LocationKind,
    val gradeSystem: GradeSystem = GradeSystem.DEFAULT,
    /** Set from the first scan made there with location permission; null until then. */
    val coords: GeoPoint? = null,
    val sets: List<WallSet> = emptyList(),
) {
    fun set(id: String?): WallSet? = sets.firstOrNull { it.id == id }
}

/**
 * files/locations.json: `{ version, nextId, locations: [{ id, name, kind, gradeSystem, coords?,
 * sets: [{ id, name, createdAt, retiredAt? }] }] }`. `nextId` only grows and numbers locations and sets
 * alike, so a removed location's id is never reissued to a new one (captures keep dangling ids).
 */
object LocationStore {
    private const val FILE = "locations.json"

    /** Placeholder for captures made before locations existed or whose location was deleted. */
    val UNKNOWN = Location("", "", LocationKind.GYM)

    private class Stored(val locations: List<Location>, val nextId: Int)

    private fun read(context: Context): Stored {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return Stored(emptyList(), 1)
        return runCatching {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("locations")
            val locations = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                // A damaged set entry is skipped on its own: one bad set must never empty the whole file on the next write.
                val sets = o.optJSONArray("sets")?.let { arr ->
                    (0 until arr.length()).mapNotNull { k ->
                        val s = arr.optJSONObject(k) ?: return@mapNotNull null
                        val id = s.optString("id"); val name = s.optString("name")
                        if (id.isEmpty() || name.isEmpty()) null
                        else WallSet(id, name, s.optString("createdAt"), s.optString("retiredAt").takeIf { it.isNotEmpty() })
                    }
                } ?: emptyList()
                Location(
                    o.getString("id"), o.getString("name"), LocationKind.fromId(o.optString("kind")),
                    GradeSystem.fromId(o.optString("gradeSystem")) ?: GradeSystem.DEFAULT,
                    GeoPoint.fromJson(o.optJSONObject("coords")), sets,
                )
            }
            // Files written before nextId existed fall back to max + 1.
            val highest = (locations.mapNotNull { it.id.removePrefix("loc-").toIntOrNull() }.maxOrNull() ?: 0) + 1
            Stored(locations, maxOf(json.optInt("nextId", 0), highest))
        }.getOrDefault(Stored(emptyList(), 1))
    }

    fun list(context: Context): List<Location> = read(context).locations

    fun get(context: Context, id: String?): Location = list(context).firstOrNull { it.id == id } ?: UNKNOWN

    fun add(context: Context, name: String, kind: LocationKind, gradeSystem: GradeSystem, coords: GeoPoint? = null): Location {
        val stored = read(context)
        val location = Location("loc-${stored.nextId}", name.trim(), kind, gradeSystem, coords)
        write(context, stored.locations + location, stored.nextId + 1)
        return location
    }

    /**
     * Records a device fix on location [id] when it improves on what is stored: nothing yet, or a less accurate
     * device fix. A hand-entered position is never replaced. Returns whether anything was written.
     */
    fun offerCoords(context: Context, id: String, point: GeoPoint): Boolean {
        val stored = read(context)
        val target = stored.locations.firstOrNull { it.id == id } ?: return false
        val current = target.coords
        if (current != null && (current.manual || point.accuracyM >= current.accuracyM)) return false
        write(context, stored.locations.map { if (it.id == id) Location(it.id, it.name, it.kind, it.gradeSystem, point, it.sets) else it }, stored.nextId)
        return true
    }

    /** Replaces the location with [location]'s id (name, kind, scale, coordinates; its sets stay); a no-op for an unknown id. */
    fun update(context: Context, location: Location) {
        val stored = read(context)
        val old = stored.locations.firstOrNull { it.id == location.id } ?: return
        val merged = Location(location.id, location.name, location.kind, location.gradeSystem, location.coords, old.sets)
        write(context, stored.locations.map { if (it.id == location.id) merged else it }, stored.nextId)
    }

    /** Opens a new set at location [locationId], dated now. */
    fun addSet(context: Context, locationId: String, name: String): WallSet {
        val stored = read(context)
        val set = WallSet("set-${stored.nextId}", name.trim(), CaptureStore.nowIso())
        write(context, stored.locations.map { if (it.id == locationId) withSets(it, it.sets + set) else it }, stored.nextId + 1)
        return set
    }

    /** Replaces the set with [set]'s id at [locationId] (rename, retire, reopen). */
    fun updateSet(context: Context, locationId: String, set: WallSet) {
        val stored = read(context)
        write(context, stored.locations.map { l -> if (l.id == locationId) withSets(l, l.sets.map { if (it.id == set.id) set else it }) else l }, stored.nextId)
    }

    private fun withSets(l: Location, sets: List<WallSet>) = Location(l.id, l.name, l.kind, l.gradeSystem, l.coords, sets)

    /** Removes location [id]; captures that point at it show as [UNKNOWN] afterwards. The id is not reused. */
    fun remove(context: Context, id: String) {
        val stored = read(context)
        write(context, stored.locations.filter { it.id != id }, stored.nextId)
    }

    private fun write(context: Context, locations: List<Location>, nextId: Int) {
        val arr = JSONArray()
        for (l in locations) {
            val o = JSONObject().put("id", l.id).put("name", l.name).put("kind", l.kind.id).put("gradeSystem", l.gradeSystem.id)
            l.coords?.let { o.put("coords", it.toJson()) }
            if (l.sets.isNotEmpty()) {
                val sets = JSONArray()
                for (s in l.sets) {
                    val so = JSONObject().put("id", s.id).put("name", s.name).put("createdAt", s.createdAt)
                    s.retiredAt?.let { so.put("retiredAt", it) }
                    sets.put(so)
                }
                o.put("sets", sets)
            }
            arr.put(o)
        }
        File(context.filesDir, FILE).writeText(JSONObject().put("version", 1).put("nextId", nextId).put("locations", arr).toString(2))
    }
}
