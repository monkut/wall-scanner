package io.github.monkut.wallscanner.scan

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import io.github.monkut.wallscanner.core.Rigid
import io.github.monkut.wallscanner.io.CaptureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Saves sparse colour keyframes (JPEG + camera pose + intrinsics) for image-based detection of tape, tags
 * and placards, which the depth point cloud cannot resolve. Poses are stored segment-local while scanning
 * (frames.jsonl) and resolved to world poses at Finish (frames.json) once segments are snapped.
 */
class KeyframeWriter(private val captureDir: File) {
    private val dir = File(captureDir, CaptureStore.FRAMES_DIR).apply { mkdirs() }
    private val index = File(captureDir, CaptureStore.FRAMES_INDEX)
    private var count = 0
    private var lastSegment = -1
    private var lastMatrix: FloatArray? = null
    private var lastTimeMs = 0L
    private var nv21: ByteArray? = null

    /**
     * Claims the next keyframe index for a frame [due] chose (directly or after waiting for a sharper one). Call
     * from one thread (the depth integration thread); the JPEG itself can then be written from another with [write].
     */
    fun claim(segmentId: Int, localFromCamera: FloatArray, nowMs: Long): Int {
        lastSegment = segmentId
        lastMatrix = localFromCamera.copyOf()
        lastTimeMs = nowMs
        return count++
    }

    /** A keyframe is due: the camera moved or turned far enough (or a new segment started) since the last claim. */
    fun due(segmentId: Int, localFromCamera: FloatArray, nowMs: Long): Boolean {
        if (nowMs - lastTimeMs < MIN_INTERVAL_MS) return false
        if (segmentId != lastSegment) return true
        val last = lastMatrix ?: return true
        val dx = localFromCamera[12] - last[12]; val dy = localFromCamera[13] - last[13]; val dz = localFromCamera[14] - last[14]
        if (sqrt(dx * dx + dy * dy + dz * dz) >= MIN_TRAVEL_M) return true
        // Angle between the two forward axes (third column, negated Z in camera space).
        val dot = (localFromCamera[8] * last[8] + localFromCamera[9] * last[9] + localFromCamera[10] * last[10]).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(dot).toDouble()) >= MIN_TURN_DEG
    }

    /**
     * Writes the JPEG and index line for a claimed [index]. Call on one thread (the keyframe thread). [gyro] is the mean
     * rate over the exposure (rad/s, device axes) and [blurPx] its blur estimate; both may be missing (#59).
     */
    fun write(
        index: Int, segmentId: Int, localFromCamera: FloatArray, timestampNs: Long,
        y: ByteArray, yRowStride: Int, u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixelStride: Int, width: Int, height: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        imageTimestampNs: Long, exposure: Exposure, gyro: FloatArray?, blurPx: Float,
    ) {
        val name = "frame-${"%04d".format(index)}.jpg"
        FileOutputStream(File(dir, name)).use { out ->
            YuvImage(toNv21(y, yRowStride, u, v, uvRowStride, uvPixelStride, width, height), ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        }
        val line = JSONObject()
            .put("file", "${CaptureStore.FRAMES_DIR}/$name")
            .put("segment", segmentId)
            .put("timestampNs", timestampNs)
            .put("localFromCamera", JSONArray(localFromCamera.map { it.toDouble() }))
            .put("intrinsics", JSONObject().put("fx", fx).put("fy", fy).put("cx", cx).put("cy", cy).put("width", width).put("height", height))
            .put("exposure", exposureJson(imageTimestampNs, exposure, gyro, blurPx))
        this.index.appendText(line.toString() + "\n")
    }

    /** Rewrites the index as frames.json with world-from-camera matrices using each segment's final transform. */
    fun resolve(segmentTransforms: Map<Int, Rigid>): Int {
        if (!index.exists()) return 0
        val frames = JSONArray()
        for (line in index.readLines()) {
            if (line.isBlank()) continue
            val f = JSONObject(line)
            val segT = segmentTransforms[f.getInt("segment")] ?: continue
            val local = f.getJSONArray("localFromCamera")
            val m = FloatArray(16) { local.getDouble(it).toFloat() }
            val world = segT.compose(Rigid.fromColumnMajor(m))
            f.put("worldFromCamera", JSONObject()
                .put("r", JSONArray(world.r.toList()))
                .put("t", JSONArray(world.t.toList())))
            frames.put(f)
        }
        File(captureDir, CaptureStore.FRAMES_RESOLVED).writeText(JSONObject().put("frames", frames).toString())
        return frames.length()
    }

    /** Values the camera or gyro did not give are left out (JSON has no NaN). */
    private fun exposureJson(imageTimestampNs: Long, e: Exposure, gyro: FloatArray?, blurPx: Float): JSONObject {
        val o = JSONObject().put("imageTimestampNs", imageTimestampNs)
        if (e.exposureNs > 0) o.put("exposureNs", e.exposureNs)
        if (e.rollingShutterSkewNs > 0) o.put("rollingShutterSkewNs", e.rollingShutterSkewNs)
        if (e.iso > 0) o.put("iso", e.iso)
        if (gyro != null) o.put("gyro", JSONArray(gyro.map { it.toDouble() }))
        if (!blurPx.isNaN()) o.put("blurPx", blurPx.toDouble())
        return o
    }

    private fun toNv21(y: ByteArray, yRowStride: Int, u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixelStride: Int, w: Int, h: Int): ByteArray {
        val size = w * h + (w / 2) * (h / 2) * 2
        val out = nv21?.takeIf { it.size == size } ?: ByteArray(size).also { nv21 = it }
        for (row in 0 until h) System.arraycopy(y, row * yRowStride, out, row * w, w)
        var k = w * h
        for (row in 0 until h / 2) {
            val base = row * uvRowStride
            for (col in 0 until w / 2) {
                val i = base + col * uvPixelStride
                out[k++] = v[i]
                out[k++] = u[i]
            }
        }
        return out
    }

    companion object {
        private const val MIN_INTERVAL_MS = 700L
        private const val MIN_TRAVEL_M = 0.25f
        private const val MIN_TURN_DEG = 15.0
        private const val JPEG_QUALITY = 85
    }
}
