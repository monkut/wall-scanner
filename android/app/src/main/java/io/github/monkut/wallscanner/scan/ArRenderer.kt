package io.github.monkut.wallscanner.scan

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
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

    /** Ends the current segment; the next tracked, recording frame opens a new one with a fresh anchor. */
    fun endSegment() { currentSegment = null }

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
        if (!tracking) currentSegment = null

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
                if (upload && seg.map.size != seg.uploadedVoxels) {
                    val cloud = seg.map.snapshot()
                    seg.renderer.update(cloud)
                    seg.uploadedVoxels = cloud.count
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
        private const val STATUS_EVERY_FRAMES = 10
    }
}
