package io.github.monkut.wallscanner.scan

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.monkut.wallscanner.core.Tuning

/**
 * Reads the raw depth frame's central window ([WINDOW_FROM]..[WINDOW_TO] of each axis on a [GRID] × [GRID] grid)
 * into camera-space points for the scan guide (#36). Runs on the GL thread whether or not a scan is recording,
 * so the guide can place the climber before Start; a few hundred samples cost well under a millisecond.
 */
class GuideSampler {
    val points = FloatArray(GRID * GRID * 3)

    /** Fills [points]; returns how many were valid, or -1 when no depth frame was available. */
    fun sample(frame: Frame): Int {
        val depth = try { frame.acquireRawDepthImage16Bits() } catch (e: NotYetAvailableException) { return -1 }
        val confidence = try { frame.acquireRawDepthConfidenceImage() } catch (e: NotYetAvailableException) { depth.close(); return -1 }
        try {
            val w = depth.width; val h = depth.height
            val tex = frame.camera.textureIntrinsics
            val fx = tex.focalLength[0] * w / tex.imageDimensions[0]; val fy = tex.focalLength[1] * h / tex.imageDimensions[1]
            val cx = tex.principalPoint[0] * w / tex.imageDimensions[0]; val cy = tex.principalPoint[1] * h / tex.imageDimensions[1]
            val dp = depth.planes[0]; val cp = confidence.planes[0]
            val db = dp.buffer; val cb = cp.buffer
            var n = 0
            for (gy in 0 until GRID) for (gx in 0 until GRID) {
                val x = (w * (WINDOW_FROM + (WINDOW_TO - WINDOW_FROM) * (gx + 0.5f) / GRID)).toInt()
                val y = (h * (WINDOW_FROM + (WINDOW_TO - WINDOW_FROM) * (gy + 0.5f) / GRID)).toInt()
                val di = y * dp.rowStride + x * dp.pixelStride
                if (di + 1 >= db.limit()) continue
                val mm = (db.get(di).toInt() and 0xff) or ((db.get(di + 1).toInt() and 0xff) shl 8)
                if (mm == 0) continue
                val ci = y * cp.rowStride + x * cp.pixelStride
                if (ci >= cb.limit() || (cb.get(ci).toInt() and 0xff) < Tuning.MIN_DEPTH_CONFIDENCE) continue
                val d = mm / 1000f
                points[n * 3] = d * (x - cx) / fx; points[n * 3 + 1] = d * (cy - y) / fy; points[n * 3 + 2] = -d
                n++
            }
            return n
        } finally {
            depth.close(); confidence.close()
        }
    }

    companion object {
        const val GRID = 20
        const val WINDOW_FROM = 0.25f
        const val WINDOW_TO = 0.75f
    }
}
