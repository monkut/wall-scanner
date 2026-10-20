package io.github.monkut.wallscanner.scan

import android.media.Image
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.monkut.wallscanner.core.Tuning
import io.github.monkut.wallscanner.core.VoxelMap
import io.github.monkut.wallscanner.io.ScanLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Raw bytes of one image plane plus its strides. */
private class PlaneData(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int)

/** Everything needed to unproject one depth frame, copied off the ARCore images so they can be released. */
private class FrameData(
    val depth: PlaneData, val depthW: Int, val depthH: Int,
    val confidence: PlaneData,
    val yPlane: PlaneData, val uPlane: PlaneData, val vPlane: PlaneData, val imgW: Int, val imgH: Int,
    val texFx: Float, val texFy: Float, val texCx: Float, val texCy: Float, val texW: Int, val texH: Int,
    val imgFx: Float, val imgFy: Float, val imgCx: Float, val imgCy: Float,
    val pose: FloatArray, val cameraPosition: FloatArray,
) {
    companion object {
        fun copy(camera: Camera, depth: Image, confidence: Image, color: Image): FrameData {
            val tex = camera.textureIntrinsics
            val img = camera.imageIntrinsics
            val pose = FloatArray(16)
            camera.pose.toMatrix(pose, 0)
            return FrameData(
                planeOf(depth.planes[0]), depth.width, depth.height,
                planeOf(confidence.planes[0]),
                planeOf(color.planes[0]), planeOf(color.planes[1]), planeOf(color.planes[2]), color.width, color.height,
                tex.focalLength[0], tex.focalLength[1], tex.principalPoint[0], tex.principalPoint[1],
                tex.imageDimensions[0], tex.imageDimensions[1],
                img.focalLength[0], img.focalLength[1], img.principalPoint[0], img.principalPoint[1],
                pose, camera.pose.translation,
            )
        }

        private fun planeOf(plane: Image.Plane): PlaneData {
            val buffer = plane.buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return PlaneData(bytes, plane.rowStride, plane.pixelStride)
        }
    }
}

/**
 * Converts ARCore raw depth frames into colored world-space points and integrates them into a [VoxelMap].
 * [process] runs on the GL thread and only copies buffers; unprojection runs on a single worker thread.
 */
class DepthFrameProcessor(private val voxelMap: VoxelMap) {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var lastDepthTimestamp = -1L
    private val cameraPositions = ArrayList<FloatArray>()
    private val xyz = FloatArray(Tuning.MAX_SAMPLES_PER_DEPTH_FRAME * 3)
    private val rgb = ByteArray(Tuning.MAX_SAMPLES_PER_DEPTH_FRAME * 3)

    @Volatile
    var depthFrames = 0
        private set

    /** Diagnostics sink; first-frame geometry and rejection counters are reported here. */
    @Volatile
    var log: ScanLog? = null

    @Volatile private var samplesSeen = 0L
    @Volatile private var rejectedNoDepth = 0L
    @Volatile private var rejectedConfidence = 0L
    @Volatile private var rejectedRange = 0L
    @Volatile private var rejectedOutOfImage = 0L
    @Volatile private var kept = 0L
    @Volatile private var skippedBusy = 0
    @Volatile private var skippedStaleDepth = 0
    @Volatile private var skippedNotAvailable = 0
    private var loggedFirstFrame = false

    fun cameraPositions(): List<FloatArray> = synchronized(cameraPositions) { ArrayList(cameraPositions) }

    fun stats(): String =
        "depthFrames=$depthFrames samples=$samplesSeen kept=$kept noDepth=$rejectedNoDepth lowConf=$rejectedConfidence " +
            "range=$rejectedRange outOfImage=$rejectedOutOfImage skipped(busy=$skippedBusy stale=$skippedStaleDepth notReady=$skippedNotAvailable)"

    fun process(frame: Frame) {
        if (busy.get()) { skippedBusy++; return }
        val depth = try {
            frame.acquireRawDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            skippedNotAvailable++; return
        }
        if (depth.timestamp == lastDepthTimestamp) { depth.close(); skippedStaleDepth++; return }
        val confidence = try {
            frame.acquireRawDepthConfidenceImage()
        } catch (e: NotYetAvailableException) {
            depth.close(); skippedNotAvailable++; return
        }
        val color = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            depth.close(); confidence.close(); skippedNotAvailable++
            log?.log("acquireCameraImage failed: $e")
            return
        }
        lastDepthTimestamp = depth.timestamp
        val data = try {
            FrameData.copy(frame.camera, depth, confidence, color)
        } finally {
            depth.close(); confidence.close(); color.close()
        }
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                integrate(data)
            } catch (e: Exception) {
                Log.e(TAG, "Depth integration failed", e)
                log?.log("integrate failed: $e")
            } finally {
                busy.set(false)
            }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun integrate(f: FrameData) {
        val w = f.depthW
        val h = f.depthH
        val step = max(1, ceil(sqrt(w * h / Tuning.MAX_SAMPLES_PER_DEPTH_FRAME.toDouble())).toInt())
        // Depth image shares the GPU texture's aspect ratio; scale the texture intrinsics to the depth size.
        val fx = f.texFx * w / f.texW
        val fy = f.texFy * h / f.texH
        val cx = f.texCx * w / f.texW
        val cy = f.texCy * h / f.texH
        val pointCamera = FloatArray(4)
        val pointWorld = FloatArray(4)
        var n = 0

        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            log?.log(
                "first depth frame: depth=${w}x$h rowStride=${f.depth.rowStride} pixelStride=${f.depth.pixelStride} " +
                    "confidence rowStride=${f.confidence.rowStride} step=$step | cpuImage=${f.imgW}x${f.imgH} " +
                    "y(rs=${f.yPlane.rowStride}) uv(rs=${f.uPlane.rowStride} ps=${f.uPlane.pixelStride}) | " +
                    "texIntrinsics ${f.texW}x${f.texH} f=(${f.texFx}, ${f.texFy}) c=(${f.texCx}, ${f.texCy}) | " +
                    "imgIntrinsics f=(${f.imgFx}, ${f.imgFy}) c=(${f.imgCx}, ${f.imgCy}) | " +
                    "camPos=(${f.cameraPosition[0]}, ${f.cameraPosition[1]}, ${f.cameraPosition[2]})"
            )
        }

        var seen = 0; var noDepth = 0; var lowConf = 0; var range = 0; var outOfImage = 0
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val di = y * f.depth.rowStride + x * f.depth.pixelStride
                if (di + 1 >= f.depth.bytes.size) continue
                seen++
                val mm = (f.depth.bytes[di].toInt() and 0xff) or ((f.depth.bytes[di + 1].toInt() and 0xff) shl 8)
                if (mm == 0) { noDepth++; continue }
                val ci = y * f.confidence.rowStride + x * f.confidence.pixelStride
                if (ci >= f.confidence.bytes.size) continue
                if ((f.confidence.bytes[ci].toInt() and 0xff) < Tuning.MIN_DEPTH_CONFIDENCE) { lowConf++; continue }
                val d = mm / 1000f
                if (d > Tuning.MAX_DEPTH_RANGE) { range++; continue }

                // Camera space: +X right, +Y up, -Z forward.
                val xc = d * (x - cx) / fx
                val yc = d * (cy - y) / fy
                val zc = -d

                // Color from the CPU image via its own intrinsics (handles differing aspect ratios).
                val u = (f.imgFx * xc / d + f.imgCx).toInt()
                val v = (f.imgCy - f.imgFy * yc / d).toInt()
                if (u < 0 || v < 0 || u >= f.imgW || v >= f.imgH) { outOfImage++; continue }
                val yi = v * f.yPlane.rowStride + u * f.yPlane.pixelStride
                val uvi = (v shr 1) * f.uPlane.rowStride + (u shr 1) * f.uPlane.pixelStride
                if (yi >= f.yPlane.bytes.size || uvi >= f.uPlane.bytes.size || uvi >= f.vPlane.bytes.size) { outOfImage++; continue }
                val yv = (f.yPlane.bytes[yi].toInt() and 0xff) - 16
                val uu = (f.uPlane.bytes[uvi].toInt() and 0xff) - 128
                val vv = (f.vPlane.bytes[uvi].toInt() and 0xff) - 128
                val c = 298 * yv
                val r = clamp((c + 409 * vv + 128) shr 8)
                val g = clamp((c - 100 * uu - 208 * vv + 128) shr 8)
                val b = clamp((c + 516 * uu + 128) shr 8)

                pointCamera[0] = xc; pointCamera[1] = yc; pointCamera[2] = zc; pointCamera[3] = 1f
                Matrix.multiplyMV(pointWorld, 0, f.pose, 0, pointCamera, 0)
                xyz[n * 3] = pointWorld[0]; xyz[n * 3 + 1] = pointWorld[1]; xyz[n * 3 + 2] = pointWorld[2]
                rgb[n * 3] = r.toByte(); rgb[n * 3 + 1] = g.toByte(); rgb[n * 3 + 2] = b.toByte()
                n++
                if (n >= Tuning.MAX_SAMPLES_PER_DEPTH_FRAME) break
            }
            if (n >= Tuning.MAX_SAMPLES_PER_DEPTH_FRAME) break
        }

        voxelMap.addAll(xyz, rgb, n)
        synchronized(cameraPositions) { cameraPositions.add(f.cameraPosition) }
        samplesSeen += seen; rejectedNoDepth += noDepth; rejectedConfidence += lowConf
        rejectedRange += range; rejectedOutOfImage += outOfImage; kept += n
        depthFrames++
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    companion object {
        private const val TAG = "DepthFrameProcessor"
    }
}
