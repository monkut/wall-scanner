package io.github.monkut.wallscanner.io

import android.content.Context
import io.github.monkut.wallscanner.core.GradeSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LocationKind(val id: String, val label: String) {
    GYM("gym", "Gym"), OUTDOOR("outdoor", "Outdoor area");

    companion object {
        fun fromId(id: String?): LocationKind = entries.firstOrNull { it.id == id } ?: GYM
    }
}

/** A gym or outdoor area. Captures (and so routes) belong to one location. */
class Location(val id: String, val name: String, val kind: LocationKind, val gradeSystem: GradeSystem = GradeSystem.DEFAULT) {
    val label: String get() = if (id.isEmpty()) name else "$name · ${kind.label}"
}

/** files/locations.json: `{ version, locations: [{ id, name, kind }] }`. */
object LocationStore {
    private const val FILE = "locations.json"

    /** Placeholder for captures made before locations existed or whose location was deleted. */
    val UNKNOWN = Location("", "No location", LocationKind.GYM)

    fun list(context: Context): List<Location> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("locations")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Location(
                    o.getString("id"), o.getString("name"), LocationKind.fromId(o.optString("kind")),
                    GradeSystem.fromId(o.optString("gradeSystem")) ?: GradeSystem.DEFAULT,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String?): Location = list(context).firstOrNull { it.id == id } ?: UNKNOWN

    fun add(context: Context, name: String, kind: LocationKind, gradeSystem: GradeSystem): Location {
        val existing = list(context)
        val id = "loc-${(existing.mapNotNull { it.id.removePrefix("loc-").toIntOrNull() }.maxOrNull() ?: 0) + 1}"
        val location = Location(id, name.trim(), kind, gradeSystem)
        write(context, existing + location)
        return location
    }

    private fun write(context: Context, locations: List<Location>) {
        val arr = JSONArray()
        for (l in locations) arr.put(JSONObject().put("id", l.id).put("name", l.name).put("kind", l.kind.id).put("gradeSystem", l.gradeSystem.id))
        File(context.filesDir, FILE).writeText(JSONObject().put("version", 1).put("locations", arr).toString(2))
    }
}
