package io.github.monkut.wallscanner.climbs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.monkut.wallscanner.Labels
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.core.AttemptDetector
import io.github.monkut.wallscanner.core.PoseSample
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.Clip
import io.github.monkut.wallscanner.io.ClipExport
import io.github.monkut.wallscanner.io.ClipOutcome
import io.github.monkut.wallscanner.io.PoseTracker
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.routes.SendDialog
import java.io.File
import java.util.concurrent.Executors

/**
 * Films a climb on one route (CLIMB-VIDEO.md). Hands-free (default): one recording for the whole session while
 * the pose model watches ~10 preview frames a second; on STOP the attempts are cut out of it by [AttemptDetector] (each a
 * clip of the same file). One attempt: REC/STOP per attempt, one file each. Clips wait in `climbs/pending/` until
 * Finish climb logs the climb (the send dialog, tries prefilled); leaving asks whether to keep them.
 */
class ClimbRecordActivity : AppCompatActivity() {
    private lateinit var captureDir: File
    private lateinit var route: RouteRecord
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var strip: LinearLayout
    private lateinit var btnRecord: MaterialButton
    private lateinit var btnFinish: MaterialButton
    private lateinit var mode: MaterialButtonToggleGroup
    private var videoCapture: VideoCapture<Recorder>? = null
    @Volatile private var recording: Recording? = null
    private var recordingFile: File? = null
    @Volatile private var recordStart = 0L // SystemClock.elapsedRealtime() when the recording started
    private var found = 0 // attempts found so far in the running session, re-counted about once a second
    private val clips = ArrayList<Clip>()
    private val samples = ArrayList<PoseSample>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var busy = false
    private var tracker: PoseTracker? = null
    private val analysis = Executors.newSingleThreadExecutor()
    private val pending get() = File(captureDir, RouteStore.PENDING_DIR)
    private val hasAudio get() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDir = File(CaptureStore.capturesDir(this), intent.getStringExtra(EXTRA_CAPTURE) ?: "")
        route = RouteStore.route(captureDir, intent.getIntExtra(EXTRA_ROUTE, -1)) ?: run { finish(); return }
        setContentView(R.layout.activity_climb_record)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        strip = findViewById(R.id.attempts)
        btnRecord = findViewById(R.id.btnRecord)
        btnFinish = findViewById(R.id.btnFinish)
        mode = findViewById(R.id.mode)
        findViewById<TextView>(R.id.routeName).text = route.name
        btnRecord.setOnClickListener { handleRecord() }
        btnFinish.setOnClickListener { handleFinish() }
        mode.addOnButtonCheckedListener { _, _, _ -> showStatus() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleLeave()
        })
        pending.mkdirs()
        showStatus()
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCamera() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_CODE) return
        // Without the microphone the clips are silent; without the camera there is nothing to film.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else { Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onPause() {
        super.onPause()
        recording?.stop() // leaving the screen ends the attempt; it is kept
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.execute { tracker?.close(); tracker = null }
        analysis.shutdown()
    }

    private val sessionMode get() = mode.checkedButtonId == R.id.modeSession

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(BITRATE)
                .build()
            val video = VideoCapture.withOutput(recorder)
            val shown = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            provider.unbindAll()
            // Two streams only: a third (image analysis) caps the recording at 720p on a Pixel 9a. The pose model
            // reads the preview instead (sampleFrame).
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, shown, video)
            videoCapture = video
        }, ContextCompat.getMainExecutor(this))
    }

    /** While a hands-free recording runs: the preview's current frame (upright, as shown), scaled down, to the pose model ~[ANALYSE_MS] apart. */
    private val sampleFrame = object : Runnable {
        override fun run() {
            if (recording == null || !sessionMode) return
            handler.postDelayed(this, ANALYSE_MS)
            if (busy) return // the previous frame is still in the pose model
            val frame = preview.bitmap ?: return
            val now = SystemClock.elapsedRealtime()
            val small = Bitmap.createScaledBitmap(frame, POSE_WIDTH, POSE_WIDTH * frame.height / frame.width, true)
            busy = true
            analysis.execute { analyse(small, now) }
        }
    }

    private fun analyse(frame: Bitmap, now: Long) {
        try {
            val t = tracker ?: runCatching { PoseTracker(this) }.onFailure { e -> Log.e(TAG, "pose model failed to load", e) }.getOrNull()?.also { p -> tracker = p } ?: return
            val sample = t.sample(frame, now - recordStart, now)
            synchronized(samples) {
                samples.add(sample)
                if (samples.size % RECOUNT_EVERY == 0) found = AttemptDetector.detect(samples, now - recordStart).size
            }
            val count = found
            runOnUiThread { showStatus(seen = sample.foot != null, found = count) }
        } finally {
            busy = false
        }
    }

    private fun handleRecord() {
        val active = recording
        if (active != null) { active.stop(); return }
        val capture = videoCapture ?: return
        val n = clips.size + 1
        val file = File(pending, if (sessionMode) "session-${System.currentTimeMillis()}.mp4" else "attempt-%02d.mp4".format(n))
        synchronized(samples) { samples.clear(); found = 0 }
        val prepared = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        recordingFile = file
        recording = (if (hasAudio) prepared.withAudioEnabled() else prepared).start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { recordStart = SystemClock.elapsedRealtime(); showStatus(); handler.post(sampleFrame) }
                is VideoRecordEvent.Finalize -> handleFinalized(event, file)
            }
        }
        mode.isEnabled = false
        btnRecord.setText(R.string.stop)
        btnFinish.isEnabled = false
    }

    /** One attempt per manual recording; a hands-free one becomes the attempts found in it (or one clip when none are). */
    private fun handleFinalized(event: VideoRecordEvent.Finalize, file: File) {
        recording = null
        recordingFile = null
        mode.isEnabled = true
        btnRecord.setText(R.string.rec)
        if (event.hasError() && !file.exists()) { Log.e(TAG, "recording failed: ${event.error}", event.cause); showStatus(); return }
        val duration = ClipExport.durationMs(file).takeIf { it > 0 } ?: (event.recordingStats.recordedDurationNanos / 1_000_000)
        val rel = file.relativeTo(captureDir).path
        val now = CaptureStore.nowIso()
        val found = if (file.name.startsWith("session-")) synchronized(samples) { AttemptDetector.detect(samples, duration) } else emptyList()
        if (found.isEmpty()) {
            clips.add(Clip(nextId(), clips.size + 1, rel, duration, 0, duration, null, file.name.startsWith("session-"), now))
        } else for (a in found) {
            clips.add(Clip(nextId(), clips.size + 1, rel, duration, a.startMs, a.endMs, a.outcome, true, now))
        }
        renderStrip()
        showStatus()
    }

    private fun nextId() = (clips.maxOfOrNull { it.id } ?: 0) + 1

    private fun renderStrip() {
        strip.removeAllViews()
        for (c in clips) {
            strip.addView(TextView(this).apply {
                text = listOfNotNull(getString(R.string.attempt_n, c.attempt), Labels.duration(c.trimEndMs - c.trimStartMs), c.outcome?.let { outcomeLabel(it) }).joinToString(" · ")
                setPadding(24, 12, 24, 12)
                setBackgroundResource(R.drawable.thumbnail_frame)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12 }
            })
        }
        btnFinish.isEnabled = clips.isNotEmpty()
    }

    private fun outcomeLabel(o: ClipOutcome) = getString(if (o == ClipOutcome.TOP) R.string.outcome_top else R.string.outcome_fall)

    private fun showStatus(seen: Boolean? = null, found: Int? = null) {
        status.text = when {
            recording != null && sessionMode && found != null -> Labels.join(this, getString(if (seen == true) R.string.climber_seen else R.string.climber_not_seen), Labels.plural(this, R.plurals.attempts_found, found))
            recording != null -> getString(R.string.recording)
            sessionMode -> getString(R.string.session_hint)
            else -> getString(R.string.attempt_hint)
        }
    }

    /** Logs the climb with its clips (the send dialog, tries = attempts filmed). */
    private fun handleFinish() {
        SendDialog.show(this, R.string.finish_climb, clips.maxOf { it.attempt }, onSave = { result, tries, note -> save(result, tries, note) })
    }

    private fun save(result: SendResult, tries: Int, note: String) {
        RouteStore.addSend(captureDir, route.routeId, result, tries, note, clips.toList(), RouteStore.PENDING_DIR)
        clips.clear()
        finish()
    }

    /** Leaving with filmed attempts: keep them as a climb (result Attempt) or discard the files. */
    private fun handleLeave() {
        recording?.let { it.stop(); return } // the attempt is kept when the file is finished; back again to leave
        if (clips.isEmpty()) { pending.deleteRecursively(); finish(); return }
        AlertDialog.Builder(this)
            .setMessage(Labels.plural(this, R.plurals.keep_attempts, clips.size))
            .setPositiveButton(R.string.keep) { _, _ -> save(SendResult.ATTEMPT, clips.maxOf { it.attempt }, "") }
            .setNegativeButton(R.string.discard) { _, _ -> pending.deleteRecursively(); clips.clear(); finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "ClimbRecord"
        private const val EXTRA_CAPTURE = "capture"
        private const val EXTRA_ROUTE = "route"
        private const val PERMISSIONS_CODE = 41
        private const val BITRATE = 8_000_000 // ~60 MB per minute at 1080p
        private const val ANALYSE_MS = 100L
        private const val RECOUNT_EVERY = 10
        private const val POSE_WIDTH = 360 // BlazePose lite works at 256 px; the preview frame is scaled to this first

        fun intent(context: Context, captureId: String, routeId: Int): Intent =
            Intent(context, ClimbRecordActivity::class.java).putExtra(EXTRA_CAPTURE, captureId).putExtra(EXTRA_ROUTE, routeId)
    }
}
