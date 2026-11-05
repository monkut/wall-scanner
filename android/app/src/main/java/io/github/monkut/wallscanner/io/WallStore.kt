package io.github.monkut.wallscanner.io

import android.content.Context
import io.github.monkut.wallscanner.core.WallMap
import org.json.JSONObject
import java.io.File

/**
 * A gym wall's model on disk, `files/walls/<wallId>/wall.json` (`{ version, map }`): today the hand-entered
 * [WallMap] (#8 phase 4); the wall's faces, extent and a bare orthophoto join it when captures are placed on
 * the map. Removed with the wall ([LocationStore.removeWall]).
 */
object WallStore {
    const val FILE = "wall.json"
    private const val VERSION = 1

    fun dir(context: Context, wallId: String): File = File(File(context.filesDir, "walls"), wallId)

    /** The wall's map, or null when none has been set up (or the file is unreadable). */
    fun readMap(context: Context, wallId: String): WallMap? {
        val file = File(dir(context, wallId), FILE)
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()).optJSONObject("map")?.let(WallMap::fromJson) }.getOrNull()
    }

    fun writeMap(context: Context, wallId: String, map: WallMap) {
        val dir = dir(context, wallId).apply { mkdirs() }
        File(dir, FILE).writeText(JSONObject().put("version", VERSION).put("map", map.toJson()).toString(2))
    }

    fun remove(context: Context, wallId: String) {
        dir(context, wallId).deleteRecursively()
    }
}
