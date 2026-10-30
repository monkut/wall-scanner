package io.github.monkut.wallscanner.io

import android.content.Context
import org.json.JSONObject
import java.io.File

/** The climber using the app. Height and reach are centimetres, null until entered. Reach is standing reach: floor to fingertips, arm raised. */
class Profile(val username: String = "", val heightCm: Double? = null, val reachCm: Double? = null) {
    val empty: Boolean get() = username.isEmpty() && heightCm == null && reachCm == null

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("username", username).also { o ->
        heightCm?.let { o.put("heightCm", it) }
        reachCm?.let { o.put("reachCm", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Profile = Profile(
            o.optString("username").trim(),
            o.optDouble("heightCm").takeIf { !it.isNaN() },
            o.optDouble("reachCm").takeIf { !it.isNaN() },
        )
    }
}

/** files/profile.json: `{ version, username, heightCm?, reachCm? }`. */
object ProfileStore {
    private const val FILE = "profile.json"

    fun read(context: Context): Profile {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return Profile()
        return runCatching { Profile.fromJson(JSONObject(file.readText())) }.getOrDefault(Profile())
    }

    fun write(context: Context, profile: Profile) = File(context.filesDir, FILE).writeText(profile.toJson().toString(2))
}
