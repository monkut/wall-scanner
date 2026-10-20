package io.github.monkut.wallscanner.scan

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import io.github.monkut.wallscanner.core.VoxelMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val listener: Listener,
    private val voxelMap: VoxelMap,
    private val processor: DepthFrameProcessor,
) : GLSurfaceView.Renderer {

    interface Listener {
        fun displayRotation(): Int
        fun onStatus(tracking: Boolean, reason: TrackingFailureReason, voxels: Int, depthFrames: Int)
    }

    @Volatile
    var session: Session? = null

    @Volatile
    var recording = false

    private val background = BackgroundRenderer()
    private val points = PointsRenderer()
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false
    private var frameCount = 0
    private var lastUploadedVoxels = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        background.createOnGlThread()
        points.createOnGlThread()
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
        if (tracking && recording) processor.process(frame)

        if (tracking) {
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP, FAR_CLIP)
            if (frameCount % UPLOAD_EVERY_FRAMES == 0 && voxelMap.size != lastUploadedVoxels) {
                val cloud = voxelMap.snapshot()
                points.update(cloud)
                lastUploadedVoxels = cloud.count
            }
            points.draw(viewMatrix, projectionMatrix)
        }

        frameCount++
        if (frameCount % STATUS_EVERY_FRAMES == 0) {
            listener.onStatus(tracking, camera.trackingFailureReason, voxelMap.size, processor.depthFrames)
        }
    }

    companion object {
        private const val NEAR_CLIP = 0.05f
        private const val FAR_CLIP = 30f
        private const val UPLOAD_EVERY_FRAMES = 15
        private const val STATUS_EVERY_FRAMES = 10
    }
}
