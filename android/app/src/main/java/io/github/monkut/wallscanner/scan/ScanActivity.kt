package io.github.monkut.wallscanner.scan

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
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
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnsupportedConfigurationException
import io.github.monkut.wallscanner.Labels
import io.github.monkut.wallscanner.ProcessingPanel
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.Settings
import io.github.monkut.wallscanner.io.FaceFinder
import io.github.monkut.wallscanner.io.KeyframeFaces
import io.github.monkut.wallscanner.core.Definition
import io.github.monkut.wallscanner.core.Processing
import io.github.monkut.wallscanner.core.ProcessingStage
import io.github.monkut.wallscanner.core.Rigid
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.core.SceneJson
import io.github.monkut.wallscanner.core.SegmentSnapper
import io.github.monkut.wallscanner.core.Thumbnail
import io.github.monkut.wallscanner.core.ScanGuide
import io.github.monkut.wallscanner.core.Tuning
import io.github.monkut.wallscanner.core.VoxelMap
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.LocationStore
import io.github.monkut.wallscanner.io.ScanLog
import io.github.monkut.wallscanner.io.ScenePipeline
import io.github.monkut.wallscanner.viewer.ViewerActivity
import java.io.File
import java.util.EnumSet
import kotlin.concurrent.thread

class ScanActivity : AppCompatActivity(), ArRenderer.Listener {
    private lateinit var glView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var guideText: TextView
    private var lastCueAtMs = 0L
    /** Why tracking is lost right now; null while tracking. The guide line shows what to do about it until it returns. */
    @Volatile private var lostReason: TrackingFailureReason? = null
    private lateinit var btnRecord: Button
    private lateinit var btnFinish: Button
    private lateinit var processingOverlay: View

    private val segments = SegmentList()
    private val processor = DepthFrameProcessor()
    private lateinit var renderer: ArRenderer
    private lateinit var captureDir: File
    private lateinit var log: ScanLog
    private lateinit var definition: Definition
    private var session: Session? = null
    private var installRequested = false
    private var processing = false
    private var finished = false
    /** Position fix for the location, requested only while the location has no coordinates yet. */
    private var gps: GpsFix? = null
    private var everRecorded = false
    private var statusCalls = 0
    private var pausedAtMs = 0L
    private var trackingChangedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        // A screen timeout pauses the ARCore session mid-scan and breaks tracking continuity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView = findViewById(R.id.glView)
        statusText = findViewById(R.id.statusText)
        guideText = findViewById(R.id.guideText)
        btnRecord = findViewById(R.id.btnRecord)
        btnFinish = findViewById(R.id.btnFinish)
        processingOverlay = findViewById(R.id.processingOverlay)

        captureDir = CaptureStore.create(this)
        log = ScanLog(File(captureDir, ScanLog.FILE))
        ScanLog.active = log
        processor.log = log
        processor.keyframes = KeyframeWriter(captureDir)
        definition = Definition(RouteDefinition.fromId(intent.getStringExtra(EXTRA_DEFINITION)))
        CaptureStore.writeMeta(captureDir, definition, intent.getStringExtra(EXTRA_LOCATION) ?: "", intent.getStringExtra(EXTRA_SET) ?: "", intent.getStringExtra(EXTRA_WALL) ?: "")
        logEnvironment()
        log.log("location: ${intent.getStringExtra(EXTRA_LOCATION) ?: "none"} · wall: ${intent.getStringExtra(EXTRA_WALL)?.ifEmpty { null } ?: "none"} · set: ${intent.getStringExtra(EXTRA_SET)?.ifEmpty { null } ?: "none"}")
        val location = LocationStore.get(this, intent.getStringExtra(EXTRA_LOCATION))
        if (location.id.isNotEmpty() && location.coords == null) {
            gps = GpsFix(this, log::log) { point ->
                CaptureStore.writeGps(captureDir, point)
                if (LocationStore.offerCoords(this, location.id, point)) log.log("gps: location ${location.id} (${location.name}) coordinates set to $point")
            }
        }
        log.log("route definition: ${definition.type.id} (${definition.type.label}) · feet=${definition.rules.feet.id} volumesOn=${definition.rules.volumesOn}")
        processor.maxDepth = if (definition.type.outdoor) Tuning.OUTDOOR_MAX_DEPTH_RANGE else Tuning.MAX_DEPTH_RANGE
        log.log("depth range: ${processor.maxDepth} m · voxel cap ${Tuning.MAX_VOXELS} per segment · min hits at finish ${Tuning.MIN_VOXEL_HITS}")

        renderer = ArRenderer(this, segments, processor)
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
            // Asked after the camera permission and ARCore are settled so the two prompts never overlap.
            gps?.let { if (it.hasPermission()) it.start() else it.requestPermission() }
        }
        try {
            session?.resume()
            val pausedFor = if (pausedAtMs == 0L) "n/a" else "${(SystemClock.elapsedRealtime() - pausedAtMs) / 1000.0}s"
            log.log(
                "LIFECYCLE onResume · ARCore session resumed · pausedFor=$pausedFor · recording=${renderer.recording} " +
                    "segments=${segments.size} voxels=${segments.totalVoxels()}"
            )
        } catch (e: CameraNotAvailableException) {
            log.log("session resume failed: $e")
            toast(getString(R.string.arcore_unavailable, "camera not available"))
            session = null
            return
        }
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        renderer.endSegment()
        session?.pause()
        pausedAtMs = SystemClock.elapsedRealtime()
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        // Not a button in the app: the activity left the foreground (screen off/lock, home, app switch, or finish).
        log.log(
            "LIFECYCLE onPause · ARCore session paused · screenOn=$screenOn isFinishing=$isFinishing " +
                "recording=${renderer.recording} segments=${segments.size} voxels=${segments.totalVoxels()} " +
                "pose=${fmt(renderer.lastTrackedPose)} · ${processor.stats()}"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
        gps?.stop()
        processor.shutdown()
        if (!finished) {
            log.log("scan abandoned · segments=${segments.size} voxels=${segments.totalVoxels()}")
            if (segments.totalVoxels() == 0) CaptureStore.delete(captureDir)
        }
        ScanLog.active = null
        log.close()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GpsFix.PERMISSION_CODE) {
            // Empty results mean the request was interrupted (Android sends one while the prompt opens
            // during onResume); the real answer follows in a second callback.
            if (grantResults.isEmpty()) return
            val fix = gps ?: return
            if (fix.hasPermission()) fix.start() else { log.log("gps: location permission denied"); toast(getString(R.string.location_permission_denied)) }
            return
        }
        if (!hasCameraPermission()) {
            log.log("camera permission denied")
            toast(getString(R.string.camera_permission_needed))
            finish()
        }
    }

    // ArRenderer.Listener

    @Suppress("DEPRECATION")
    override fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    override fun onStatus(tracking: Boolean, reason: TrackingFailureReason, voxels: Int, depthFrames: Int) {
        val text = if (tracking) {
            val mode = getString(if (renderer.recording) R.string.status_scanning else R.string.status_ready)
            getString(R.string.status_line, mode, voxels / 1000, depthFrames, segments.size)
        } else {
            getString(R.string.tracking_lost, reason.toString())
        }
        // The reason can change while tracking stays lost (NONE at first, then why); the hint follows it.
        if (!tracking && reason != lostReason) { lostReason = reason; showGuide(trackingHint(reason)) }
        runOnUiThread { statusText.text = text }

        for (seg in segments.all()) {
            if (seg.loggedFull || seg.map.evictions == 0) continue
            seg.loggedFull = true
            log.log("SEGMENT ${seg.id} full: ${Tuning.MAX_VOXELS} voxels reached, evicting least-hit voxels · ${processor.stats()}")
        }
        statusCalls++
        if (statusCalls % STATUS_LOG_EVERY_CALLS == 0) {
            log.log(
                "STATUS segments=${segments.size} recording=${renderer.recording} tracking=$tracking voxels=$voxels " +
                    "evicted=${segments.totalEvictions()} pos=${fmtTranslation(renderer.lastTrackedPose)} · ${processor.stats()} " +
                    renderer.uploadStats() + " " + renderer.guideStats()
            )
        }
    }

    override fun onTrackingChanged(tracking: Boolean, reason: TrackingFailureReason, pose: Pose?) {
        val now = SystemClock.elapsedRealtime()
        val first = trackingChangedAtMs == 0L
        val sinceLast = if (first) "n/a" else "${(now - trackingChangedAtMs) / 1000.0}s"
        trackingChangedAtMs = now
        val state = "recording=${renderer.recording} segments=${segments.size} voxels=${segments.totalVoxels()}"
        if (first && !tracking) {
            log.log("TRACKING not established yet ($reason) · $state")
        } else if (tracking) {
            log.log("TRACKING regained after $sinceLast lost · $state pose=${fmt(pose)}")
        } else {
            log.log("TRACKING lost ($reason) after $sinceLast tracked · $state lastPose=${fmt(pose)}")
        }
        // The guide line says what to do about a loss from the frame it happens; the guide's own cues resume with tracking (onGuide).
        lostReason = if (tracking) null else reason
        showGuide(if (tracking) "" else trackingHint(reason))
    }

    /** What to do about a tracking loss, from ARCore's reason (#36); nothing for NONE, the session's normal start. */
    private fun trackingHint(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.NONE -> ""
        TrackingFailureReason.INSUFFICIENT_FEATURES -> getString(R.string.track_hint_features)
        TrackingFailureReason.INSUFFICIENT_LIGHT -> getString(R.string.track_hint_light)
        TrackingFailureReason.EXCESSIVE_MOTION -> getString(R.string.track_hint_motion)
        TrackingFailureReason.CAMERA_UNAVAILABLE -> getString(R.string.track_hint_camera)
        TrackingFailureReason.BAD_STATE -> getString(R.string.track_hint_bad_state)
    }

    private fun showGuide(text: String) = runOnUiThread {
        guideText.text = text
        guideText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onGuide(cue: ScanGuide.Cue?, measure: ScanGuide.Measure?) {
        val text = when (cue) {
            ScanGuide.Cue.TOO_CLOSE -> getString(R.string.guide_too_close, measure?.distance ?: 0f)
            ScanGuide.Cue.TOO_FAR -> getString(R.string.guide_too_far, measure?.distance ?: 0f)
            ScanGuide.Cue.FACE_WALL -> getString(R.string.guide_face_wall)
            ScanGuide.Cue.TOO_FAST -> getString(R.string.guide_too_fast)
            null -> ""
        }
        val now = SystemClock.elapsedRealtime()
        val held = if (lastCueAtMs == 0L) "" else " after ${(now - lastCueAtMs) / 1000.0}s"
        log.log("GUIDE ${cue ?: "ok"}$held · ${measure ?: "no surface"} · recording=${renderer.recording}")
        lastCueAtMs = now
        if (cue == null && lostReason != null) return // the renderer clears its cue on a loss; the loss hint stays
        showGuide(text)
    }

    override fun onSegmentStarted(segment: Segment, cameraPose: Pose) {
        log.log("SEGMENT ${segment.id} started · anchor at camera pose ${fmt(cameraPose)} · segments=${segments.size}")
    }

    // Setup

    private fun logEnvironment() {
        val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val arcoreVersion = runCatching { packageManager.getPackageInfo("com.google.ar.core", 0).versionName }.getOrNull()
        log.log("capture ${captureDir.name} · app=$appVersion · arcore=$arcoreVersion")
        log.log("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ensureArCoreAndPermission(): Boolean {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return false
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; log.log("arcore install requested"); false }
                ArCoreApk.InstallStatus.INSTALLED -> true
                else -> false
            }
        } catch (e: UnavailableException) {
            log.log("arcore unavailable: $e")
            toast(getString(R.string.arcore_unavailable, e.javaClass.simpleName))
            finish()
            false
        }
    }

    private fun createSession(): Session? {
        val session = try {
            Session(this)
        } catch (e: UnavailableException) {
            log.log("session create failed: $e")
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
                log.log("depth unsupported with chosen camera config")
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
            log.log("session configured depthMode=$depthMode")
        } catch (e: UnsupportedConfigurationException) {
            log.log("session configure failed: $e")
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
        log.log("camera configs: " + configs.joinToString(" | ") { describe(it) })
        val best = configs
            .filter { it.imageSize.width <= MAX_CPU_IMAGE_WIDTH }
            .maxByOrNull { it.imageSize.width * it.imageSize.height }
        if (best == null) {
            log.log("no camera config matched; keeping default ${describe(session.cameraConfig)}")
            return
        }
        session.cameraConfig = best
        log.log("camera config chosen: ${describe(best)}")
    }

    private fun describe(c: CameraConfig): String =
        "cpu=${c.imageSize.width}x${c.imageSize.height} tex=${c.textureSize.width}x${c.textureSize.height} " +
            "fps=${c.fpsRange} depthSensor=${c.depthSensorUsage} stereo=${c.stereoCameraUsage}"

    // Actions

    private fun handleToggleRecording() {
        val button = if (renderer.recording) "Pause" else if (everRecorded) "Resume" else "Start"
        everRecorded = true
        renderer.recording = !renderer.recording
        btnRecord.setText(if (renderer.recording) R.string.pause_scan else R.string.resume_scan)
        log.log(
            "USER tapped $button button · recording=${renderer.recording} segments=${segments.size} " +
                "voxels=${segments.totalVoxels()} pose=${fmt(renderer.lastTrackedPose)}"
        )
    }

    private fun handleFinish() {
        log.log("USER tapped Finish button · segments=${segments.size} voxels=${segments.totalVoxels()} pose=${fmt(renderer.lastTrackedPose)}")
        if (processing) return
        if (segments.totalVoxels() < MIN_VOXELS_TO_FINISH) {
            log.log("finish refused: voxels=${segments.totalVoxels()} < $MIN_VOXELS_TO_FINISH")
            toast(getString(R.string.scan_more))
            return
        }
        processing = true
        renderer.recording = false
        processingOverlay.visibility = View.VISIBLE
        val maskFaces = Settings.maskBystanders(this)
        val panel = ProcessingPanel(processingOverlay, Processing.atFinish(maskFaces))
        panel.update(ProcessingStage.MERGE, 0f)
        thread(name = "scene-builder") {
            val t0 = System.currentTimeMillis()
            val all = segments.all()
            log.log("processing: segments=${all.size} voxels=${segments.totalVoxels()} · ${processor.stats()}")
            val merged = VoxelMap(maxVoxels = Int.MAX_VALUE)
            val cameras = ArrayList<FloatArray>()
            val segmentTransforms = HashMap<Int, Rigid>()
            var reference = merged.snapshot()
            for (seg in all) {
                val matrix = FloatArray(16)
                seg.anchorPose.toMatrix(matrix, 0)
                val anchorT = Rigid.fromColumnMajor(matrix)
                val local = seg.map.snapshot(minHits = Tuning.MIN_VOXEL_HITS)
                var world = anchorT.apply(local)
                CaptureStore.writeSegmentPly(captureDir, seg.id, world)
                var snap = "skipped(first segment)"
                var fix = Rigid.IDENTITY
                if (reference.count > 0 && cameras.isNotEmpty()) {
                    val toward = mean(cameras)
                    val result = SegmentSnapper.snap(reference, world, toward)
                    snap = result?.toString() ?: "skipped(no wall plane or < ${Tuning.SNAP_MIN_VOXELS} voxels)"
                    if (result != null && result.accepted) {
                        fix = result.transform
                        world = fix.apply(world)
                    }
                }
                log.log(
                    "SEGMENT ${seg.id}: voxels=${local.count} (of ${seg.map.size}, ${seg.map.size - local.count} under " +
                        "${Tuning.MIN_VOXEL_HITS} hits dropped, ${seg.map.evictions} evicted during scan) " +
                        "anchor=${seg.anchorState} anchorPose=${fmt(seg.anchorPose)} snap=$snap"
                )
                merged.addAll(world.xyz, world.rgb, world.count)
                for (c in seg.cameraPositions()) cameras.add(fix.apply(anchorT.apply(c)))
                segmentTransforms[seg.id] = fix.compose(anchorT)
                reference = merged.snapshot()
            }
            processor.awaitKeyframes()
            val keyframes = processor.keyframes?.resolve(segmentTransforms) ?: 0
            log.log("keyframes: $keyframes resolved to world poses (${CaptureStore.FRAMES_RESOLVED})")
            if (maskFaces) runCatching { FaceFinder(this).use { KeyframeFaces.mask(captureDir, it) { done -> panel.update(ProcessingStage.FACES, done) } } }
                .onSuccess { log.log("keyframe faces: ${it.faces} hidden in ${it.withFaces} of ${it.frames} keyframes (${it.ms} ms)") }
                .onFailure { log.log("keyframe faces failed: $it") }
            val cloud = reference
            log.log("merged: voxels=${cloud.count} cameraPositions=${cameras.size} (${System.currentTimeMillis() - t0} ms)")
            CaptureStore.writePly(captureDir, cloud)
            log.log("points.ply written (${File(captureDir, CaptureStore.PLY_FILE).length()} bytes)")

            val result = ScenePipeline.build(captureDir, cloud, cameras, definition, assets, { log.log(it) }, panel::update)
            val elapsed = System.currentTimeMillis() - t0
            if (result == null) {
                log.log("RESULT: no wall plane (${elapsed} ms)")
                runOnUiThread {
                    processing = false
                    processingOverlay.visibility = View.GONE
                    toast(getString(R.string.points_saved_no_wall))
                }
                return@thread
            }
            val p = result.frame.plane
            log.log(
                "RESULT: plane origin=(${p.ox}, ${p.oy}, ${p.oz}) normal=(${p.nx}, ${p.ny}, ${p.nz}) " +
                    "inliers=${result.planeInliers}/${cloud.count} faces=${SceneJson.facesSummary(result.surface)} wallColor=${result.wallColor ?: "n/a"} holds=${result.holds.size} " +
                    "unassigned=${result.holds.count { it.routeId == -1 }} " +
                    "routes=${result.routes.joinToString { "${it.name}(${it.holdIds.size})" }} (${elapsed} ms)"
            )
            panel.update(ProcessingStage.WRITE, 0.5f)
            CaptureStore.writeScene(captureDir, SceneJson.write(result, cloud.count, CaptureStore.nowIso(), Build.MODEL))
            CaptureStore.writeThumbnail(captureDir, Thumbnail.render(cloud, result.surface))
            panel.update(ProcessingStage.WRITE, 1f)
            finished = true
            val thin = result.thinCoverage
            val quality = result.quality
            runOnUiThread {
                if (thin.isNotEmpty()) toast(getString(R.string.coverage_thin, thin.joinToString { Labels.coverageCell(this, it) }))
                if (quality != null && quality.ghosted) toast(getString(R.string.scan_ghosted, quality.ghostCells.joinToString { Labels.coverageCell(this, it) }, Math.round(quality.ghostShare * 100)))
                else if (quality != null && quality.smeared) toast(getString(R.string.scan_drifted, Math.round(quality.planeShare * 100)))
                startActivity(ViewerActivity.intent(this, captureDir.name))
                finish()
            }
        }
    }

    private fun mean(points: List<FloatArray>): FloatArray {
        val m = FloatArray(3)
        for (p in points) { m[0] += p[0]; m[1] += p[1]; m[2] += p[2] }
        for (i in 0 until 3) m[i] /= points.size
        return m
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** "t=(x, y, z) q=(qx, qy, qz, qw)" in metres, world frame; "none" before the first tracked frame. */
    private fun fmt(pose: Pose?): String {
        if (pose == null) return "none"
        val q = pose.rotationQuaternion
        return "${fmtTranslation(pose)} q=(${"%.3f".format(q[0])}, ${"%.3f".format(q[1])}, ${"%.3f".format(q[2])}, ${"%.3f".format(q[3])})"
    }

    private fun fmtTranslation(pose: Pose?): String {
        if (pose == null) return "none"
        return "t=(${"%.3f".format(pose.tx())}, ${"%.3f".format(pose.ty())}, ${"%.3f".format(pose.tz())})"
    }

    companion object {
        const val EXTRA_DEFINITION = "definition"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_SET = "set"
        const val EXTRA_WALL = "wall"
        private const val CAMERA_PERMISSION_CODE = 1
        private const val MAX_CPU_IMAGE_WIDTH = 1920
        private const val MIN_VOXELS_TO_FINISH = 2000
        private const val STATUS_LOG_EVERY_CALLS = 6 // onStatus fires every 10 frames → one log line per ~2 s
    }
}
