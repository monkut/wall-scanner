package io.github.monkut.wallscanner.scan

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnsupportedConfigurationException
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.core.SceneBuilder
import io.github.monkut.wallscanner.core.SceneJson
import io.github.monkut.wallscanner.core.VoxelMap
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.viewer.ViewerActivity
import java.util.EnumSet
import kotlin.concurrent.thread

class ScanActivity : AppCompatActivity(), ArRenderer.Listener {
    private lateinit var glView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnFinish: Button
    private lateinit var processingOverlay: View

    private val voxelMap = VoxelMap()
    private val processor = DepthFrameProcessor(voxelMap)
    private lateinit var renderer: ArRenderer
    private var session: Session? = null
    private var installRequested = false
    private var processing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        glView = findViewById(R.id.glView)
        statusText = findViewById(R.id.statusText)
        btnRecord = findViewById(R.id.btnRecord)
        btnFinish = findViewById(R.id.btnFinish)
        processingOverlay = findViewById(R.id.processingOverlay)

        renderer = ArRenderer(this, voxelMap, processor)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        btnRecord.setOnClickListener { handleToggleRecording() }
        btnFinish.setOnClickListener { handleFinish() }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            if (!ensureArCoreAndPermission()) return
            session = createSession() ?: return
            renderer.session = session
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            toast(getString(R.string.arcore_unavailable, "camera not available"))
            session = null
            return
        }
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
        processor.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasCameraPermission()) {
            toast(getString(R.string.camera_permission_needed))
            finish()
        }
    }

    // ArRenderer.Listener

    @Suppress("DEPRECATION")
    override fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    override fun onStatus(tracking: Boolean, reason: TrackingFailureReason, voxels: Int, depthFrames: Int) {
        val text = if (tracking) {
            val mode = if (renderer.recording) "Scanning" else "Ready"
            "$mode · ${voxels / 1000}k points · $depthFrames depth frames"
        } else {
            "Tracking lost: $reason"
        }
        runOnUiThread { statusText.text = text }
    }

    // Setup

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ensureArCoreAndPermission(): Boolean {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return false
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; false }
                ArCoreApk.InstallStatus.INSTALLED -> true
                else -> false
            }
        } catch (e: UnavailableException) {
            toast(getString(R.string.arcore_unavailable, e.javaClass.simpleName))
            finish()
            false
        }
    }

    private fun createSession(): Session? {
        val session = try {
            Session(this)
        } catch (e: UnavailableException) {
            toast(getString(R.string.arcore_unavailable, e.javaClass.simpleName))
            finish()
            return null
        }
        chooseCameraConfig(session)
        // Depth support depends on the selected camera config, so check it after choosing one.
        val depthMode = when {
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
            session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
            else -> {
                toast(getString(R.string.depth_unsupported))
                session.close()
                finish()
                return null
            }
        }
        val config = session.config
        config.depthMode = depthMode
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        try {
            session.configure(config)
        } catch (e: UnsupportedConfigurationException) {
            toast(getString(R.string.arcore_unavailable, "unsupported configuration"))
            session.close()
            finish()
            return null
        }
        return session
    }

    /** Prefer the largest CPU image (up to 1080p) so point colors come from a sharp frame. */
    private fun chooseCameraConfig(session: Session) {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        val configs = session.getSupportedCameraConfigs(filter)
        val best = configs
            .filter { it.imageSize.width <= MAX_CPU_IMAGE_WIDTH }
            .maxByOrNull { it.imageSize.width * it.imageSize.height } ?: return
        session.cameraConfig = best
    }

    // Actions

    private fun handleToggleRecording() {
        renderer.recording = !renderer.recording
        btnRecord.setText(if (renderer.recording) R.string.pause_scan else R.string.resume_scan)
    }

    private fun handleFinish() {
        if (processing) return
        if (voxelMap.size < MIN_VOXELS_TO_FINISH) {
            toast(getString(R.string.scan_more))
            return
        }
        processing = true
        renderer.recording = false
        processingOverlay.visibility = View.VISIBLE
        thread(name = "scene-builder") {
            val cloud = voxelMap.snapshot()
            val result = SceneBuilder.build(cloud, processor.cameraPositions())
            if (result == null) {
                runOnUiThread {
                    processing = false
                    processingOverlay.visibility = View.GONE
                    toast(getString(R.string.no_wall_found))
                }
                return@thread
            }
            val dir = CaptureStore.create(this)
            CaptureStore.writePly(dir, cloud)
            CaptureStore.writeScene(dir, SceneJson.write(result, cloud.count, CaptureStore.nowIso(), Build.MODEL))
            runOnUiThread {
                startActivity(ViewerActivity.intent(this, dir.name))
                finish()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1
        private const val MAX_CPU_IMAGE_WIDTH = 1920
        private const val MIN_VOXELS_TO_FINISH = 2000
    }
}
