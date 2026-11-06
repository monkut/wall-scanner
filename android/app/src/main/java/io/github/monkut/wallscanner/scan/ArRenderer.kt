package io.github.monkut.wallscanner.scan

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import io.github.monkut.wallscanner.core.GuideState
import io.github.monkut.wallscanner.core.ScanGuide
import io.github.monkut.wallscanner.core.Tuning
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val listener: Listener,
    private val segments: SegmentList,
    private val processor: DepthFrameProcessor,
) : GLSurfaceView.Renderer {

    interface Listener {
        fun displayRotation(): Int
        fun onStatus(tracking: Boolean, reason: TrackingFailureReason, voxels: Int, depthFrames: Int)

        /** Called on the GL thread on the first frame whose tracking state differs from the previous frame. */
        fun onTrackingChanged(tracking: Boolean, reason: TrackingFailureReason, pose: Pose?)

        /** Called on the GL thread when recording starts a new anchored segment. */
        fun onSegmentStarted(segment: Segment, cameraPose: Pose)

        /** Called on the GL thread when the guide's cue changes; [measure] is the reading that settled it (null on tracking loss). */
        fun onGuide(cue: ScanGuide.Cue?, measure: ScanGuide.Measure?)
    }

    @Volatile
    var session: Session? = null

    @Volatile
    var recording = false

    /** Camera pose of the most recent tracked frame; null until tracking has been established once. */
    @Volatile
    var lastTrackedPose: Pose? = null
        private set

    @Volatile
    private var currentSegment: Segment? = null
    private var lastTracking: Boolean? = null

    private val background = BackgroundRenderer()
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false
    private var frameCount = 0

    private val guideSampler = GuideSampler()
    private val guideState = GuideState()
    private var lastPose: Pose? = null
    private var lastPoseNs = 0L
    private var speed = 0f
    private var turnRate = 0f
    private val up = FloatArray(3)
    @Volatile private var lastMeasure: ScanGuide.Measure? = null

    /** The latest guide measurement, for the STATUS log line. */
    fun guideStats(): String = "guide(${lastMeasure ?: "none"}${guideState.cue?.let { " $it" } ?: ""})"

    @Volatile private var uploads = 0
    @Volatile private var uploadMsTotal = 0L
    @Volatile private var uploadMsMax = 0L

    /** Preview upload timing on the GL thread (a slow upload is a visible hitch in the camera image). */
    fun uploadStats(): String =
        "upload(n=$uploads avg=${if (uploads == 0) 0 else uploadMsTotal / uploads}ms max=${uploadMsMax}ms cap=$PREVIEW_MAX_POINTS)"

    /** Ends the current segment; the next tracked, recording frame opens a new one with a fresh anchor. */
    fun endSegment() { currentSegment = null }

    /** Camera speed (m/s) and turning rate (rad/s) smoothed over about [SPEED_WINDOW_S]: the sweep, not the jitter of one frame. */
    private fun updateSpeed(pose: Pose, timestampNs: Long) {
        val prev = lastPose
        val dt = (timestampNs - lastPoseNs) / 1e9f
        if (prev != null && dt > 0f && dt < 1f) {
            val dx = pose.tx() - prev.tx(); val dy = pose.ty() - prev.ty(); val dz = pose.tz() - prev.tz()
            val a = pose.zAxis; val b = prev.zAxis
            val turned = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f))
            val alpha = min(1f, dt / SPEED_WINDOW_S)
            speed += alpha * (sqrt(dx * dx + dy * dy + dz * dz) / dt - speed)
            turnRate += alpha * (turned / dt - turnRate)
        }
        lastPose = pose; lastPoseNs = timestampNs
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (viewportChanged) {
            session.setDisplayGeometry(listener.displayRotation(), viewportWidth, viewportHeight)
            viewportChanged = false
        }
        session.setCameraTextureName(background.textureId)
        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            return
        }
        val camera = frame.camera
        background.draw(frame)

        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) lastTrackedPose = camera.pose
        if (tracking != lastTracking) {
            lastTracking = tracking
            listener.onTrackingChanged(tracking, camera.trackingFailureReason, lastTrackedPose)
        }
        if (!tracking) {
            currentSegment = null
            lastPose = null; speed = 0f; turnRate = 0f; lastMeasure = null
            if (guideState.update(null)) listener.onGuide(null, null)
        } else {
            updateSpeed(camera.pose, frame.timestamp)
            if (frameCount % GUIDE_EVERY_FRAMES == 0) {
                val n = guideSampler.sample(frame)
                if (n >= 0) {
                    camera.pose.inverse().rotateVector(WORLD_UP, 0, up, 0)
                    val measure = ScanGuide.measure(guideSampler.points, n, up, speed, turnRate)
                    lastMeasure = measure
                    val cue = measure?.let { ScanGuide.judge(it, processor.maxDepth * Tuning.GUIDE_FAR_FRACTION) }
                    if (guideState.update(cue)) listener.onGuide(guideState.cue, measure)
                }
            }
        }

        val all = segments.all()
        if (tracking) {
            for (seg in all) seg.refreshAnchor()
            if (recording) {
                val seg = currentSegment ?: startSegment(session, camera.pose)
                processor.process(frame, seg)
            }
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP, FAR_CLIP)
            val upload = frameCount % UPLOAD_EVERY_FRAMES == 0
            for (seg in segments.all()) {
                seg.renderer.createOnGlThread()
                val size = seg.map.size
                if (upload && size != seg.uploadedVoxels) {
                    val t0 = SystemClock.elapsedRealtime()
                    seg.renderer.update(seg.map.preview(PREVIEW_MAX_POINTS))
                    seg.uploadedVoxels = size
                    val ms = SystemClock.elapsedRealtime() - t0
                    uploads++; uploadMsTotal += ms; if (ms > uploadMsMax) uploadMsMax = ms
                }
                seg.renderer.draw(viewMatrix, projectionMatrix, seg.anchorMatrix)
            }
        }

        frameCount++
        if (frameCount % STATUS_EVERY_FRAMES == 0) {
            listener.onStatus(tracking, camera.trackingFailureReason, segments.totalVoxels(), processor.depthFrames)
        }
    }

    private fun startSegment(session: Session, cameraPose: Pose): Segment {
        val segment = Segment(segments.size + 1, session.createAnchor(cameraPose))
        segments.add(segment)
        currentSegment = segment
        listener.onSegmentStarted(segment, cameraPose)
        return segment
    }

    companion object {
        private const val NEAR_CLIP = 0.05f
        private const val FAR_CLIP = 30f
        private const val UPLOAD_EVERY_FRAMES = 15
        private const val GUIDE_EVERY_FRAMES = 10 // about three measurements a second
        private const val SPEED_WINDOW_S = 1f
        private val WORLD_UP = floatArrayOf(0f, 1f, 0f)
        private const val STATUS_EVERY_FRAMES = 10
        /** Live preview points per segment; the full map is drawn only in the viewer after Finish. */
        private const val PREVIEW_MAX_POINTS = 300_000
    }
}
