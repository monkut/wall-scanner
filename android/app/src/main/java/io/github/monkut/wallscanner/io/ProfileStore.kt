package io.github.monkut.wallscanner.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.monkut.wallscanner.core.Crop
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

/** files/profile.json: `{ version, username, heightCm?, reachCm? }`; the picture is files/profile.jpg, a [PICTURE_PX] square. */
object ProfileStore {
    private const val FILE = "profile.json"
    private const val PICTURE = "profile.jpg"
    private const val PICTURE_PX = 512

    fun read(context: Context): Profile {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return Profile()
        return runCatching { Profile.fromJson(JSONObject(file.readText())) }.getOrDefault(Profile())
    }

    fun write(context: Context, profile: Profile) = File(context.filesDir, FILE).writeText(profile.toJson().toString(2))

    fun picture(context: Context): Bitmap? = File(context.filesDir, PICTURE).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Stores [bitmap] as the picture; null removes it. */
    fun writePicture(context: Context, bitmap: Bitmap?) {
        val file = File(context.filesDir, PICTURE)
        if (bitmap == null) { file.delete(); return }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    /** The image at [uri] as a centred [PICTURE_PX] square, upright; null when it cannot be decoded. Slow: call off the main thread. */
    fun decodePicture(context: Context, uri: Uri): Bitmap? = runCatching {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.setTargetSampleSize(maxOf(1, minOf(info.size.width, info.size.height) / PICTURE_PX))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val (left, top, side) = Crop.centreSquare(decoded.width, decoded.height)
        Bitmap.createScaledBitmap(Bitmap.createBitmap(decoded, left, top, side, side), PICTURE_PX, PICTURE_PX, true)
    }.getOrNull()
}
