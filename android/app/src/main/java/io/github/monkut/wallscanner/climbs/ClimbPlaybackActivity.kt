package io.github.monkut.wallscanner.climbs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.RangeSlider
import androidx.media3.transformer.Transformer
import io.github.monkut.wallscanner.Labels
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.Settings
import io.github.monkut.wallscanner.core.FaceFrame
import io.github.monkut.wallscanner.core.FaceMask
import io.github.monkut.wallscanner.io.FaceStore
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.Clip
import io.github.monkut.wallscanner.io.ClipExport
import io.github.monkut.wallscanner.io.ClipOutcome
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.Send
import java.io.File
import kotlin.concurrent.thread

/**
 * A climb's filmed attempts: a tab per attempt, played between its trim points (the file is never changed; a
 * session recording holds several attempts), the trim range, the outcome (Top / Fall), delete, and share (a
 * trimmed copy, [ClipExport]). Faces seen while filming ([FaceStore]) are masked on playback ([FaceMaskView])
 * and burnt into the shared copy ([MaskedExport]) by the Settings switches.
 */
class ClimbPlaybackActivity : AppCompatActivity() {
    private lateinit var captureDir: File
    private var sendId = -1
    private lateinit var send: Send
    private lateinit var video: VideoView
    private lateinit var trim: RangeSlider
    private lateinit var trimLabel: TextView
    private lateinit var outcome: MaterialButtonToggleGroup
    private lateinit var chips: ChipGroup
    private lateinit var masks: FaceMaskView
    private var clip: Clip? = null
    /** The faces of the shown clip's file. */
    private var faces: List<FaceFrame> = emptyList()
    private var export: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopAtOut = object : Runnable {
        override fun run() {
            val c = clip ?: return
            masks.timeMs = video.currentPosition.toLong()
            if (video.isPlaying && video.currentPosition >= c.trimEndMs) video.pause() else handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDir = File(CaptureStore.capturesDir(this), intent.getStringExtra(EXTRA_CAPTURE) ?: "")
        sendId = intent.getIntExtra(EXTRA_SEND, -1)
        setContentView(R.layout.activity_climb_playback)
        video = findViewById(R.id.video)
        trim = findViewById(R.id.trim)
        trimLabel = findViewById(R.id.trimLabel)
        outcome = findViewById(R.id.outcome)
        chips = findViewById(R.id.attempts)
        masks = findViewById(R.id.faceMask)
        masks.target = video
        findViewById<MaterialButton>(R.id.btnPlay).setOnClickListener { handlePlay() }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { handleShare() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { handleDelete() }
        trim.addOnChangeListener { slider, _, fromUser -> if (fromUser) { showTrim(slider.values[0].toLong(), slider.values[1].toLong()); video.seekTo(slider.values[0].toInt()); masks.timeMs = slider.values[0].toLong() } }
        trim.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = video.pause()
            override fun onStopTrackingTouch(slider: RangeSlider) = save { it.with(trimStartMs = slider.values[0].toLong(), trimEndMs = slider.values[1].toLong()) }
        })
        outcome.addOnButtonCheckedListener { group, _, _ ->
            val chosen = when (group.checkedButtonId) { R.id.outcomeTop -> ClipOutcome.TOP; R.id.outcomeFall -> ClipOutcome.FALL; else -> null }
            if (chosen != clip?.outcome) save { it.with(outcome = chosen) }
        }
        if (!reload()) finish()
    }

    override fun onResume() {
        super.onResume()
        masks.climber = Settings.maskClimber(this)
        masks.bystanders = Settings.maskBystanders(this)
        masks.invalidate()
    }

    override fun onPause() {
        super.onPause()
        video.pause()
        handler.removeCallbacks(stopAtOut)
    }

    override fun onDestroy() {
        super.onDestroy()
        export?.cancel()
        export = null
    }

    /** Reads the climb again and shows its attempts; false when the climb or its last clip is gone. */
    private fun reload(selectId: Int? = clip?.id): Boolean {
        send = RouteStore.sends(captureDir).firstOrNull { it.id == sendId } ?: return false
        if (send.clips.isEmpty()) return false
        findViewById<TextView>(R.id.title).text = Labels.isoDateTime(this, send.at)
        chips.removeAllViews()
        for (c in send.clips) {
            chips.addView(Chip(this).apply {
                id = c.id
                text = getString(R.string.attempt_n, c.attempt)
                isCheckable = true
                setOnClickListener { send.clips.firstOrNull { it.id == c.id }?.let { show(it) } } // the stored clip, with its latest trim and outcome
            })
        }
        val selected = send.clips.firstOrNull { it.id == selectId } ?: send.clips.first()
        chips.check(selected.id)
        show(selected)
        return true
    }

    private fun show(c: Clip) {
        val first = clip?.file != c.file
        clip = c
        if (first) {
            video.setVideoPath(File(captureDir, c.file).path)
            faces = FaceStore.read(File(captureDir, c.file))
            masks.frames = faces
        }
        masks.timeMs = c.trimStartMs
        video.setOnPreparedListener { video.seekTo(c.trimStartMs.toInt()) }
        if (!first) video.seekTo(c.trimStartMs.toInt())
        trim.valueFrom = 0f
        trim.valueTo = maxOf(c.durationMs, 1).toFloat()
        trim.values = listOf(c.trimStartMs.toFloat(), c.trimEndMs.toFloat())
        showTrim(c.trimStartMs, c.trimEndMs)
        when (c.outcome) { ClipOutcome.TOP -> outcome.check(R.id.outcomeTop); ClipOutcome.FALL -> outcome.check(R.id.outcomeFall); null -> outcome.clearChecked() }
    }

    private fun showTrim(start: Long, end: Long) {
        trimLabel.text = getString(R.string.trim_range, Labels.duration(start), Labels.duration(end), Labels.duration(end - start))
    }

    private fun handlePlay() {
        val c = clip ?: return
        if (video.currentPosition < c.trimStartMs || video.currentPosition >= c.trimEndMs) video.seekTo(c.trimStartMs.toInt())
        video.start()
        handler.removeCallbacks(stopAtOut)
        handler.post(stopAtOut)
    }

    private fun save(change: (Clip) -> Clip) {
        val c = clip ?: return
        val updated = change(c)
        RouteStore.updateClip(captureDir, sendId, updated)
        send = RouteStore.sends(captureDir).firstOrNull { it.id == sendId } ?: send
        clip = send.clips.firstOrNull { it.id == updated.id } ?: updated
        showTrim(updated.trimStartMs, updated.trimEndMs)
    }

    private fun handleDelete() {
        val c = clip ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_attempt_confirm, c.attempt))
            .setPositiveButton(R.string.delete) { _, _ ->
                RouteStore.deleteClip(captureDir, sendId, c.id)
                clip = null
                if (!reload()) finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Shares the attempt's trimmed range as its own MP4: a straight copy when nothing is to be masked in it, else
     * re-encoded with the masks drawn on (progress shown; the screen must stay open).
     */
    private fun handleShare() {
        val c = clip ?: return
        if (export != null) return
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "climb-${captureDir.name}-${sendId}-attempt-${c.attempt}.mp4")
        val source = File(captureDir, c.file)
        val climber = Settings.maskClimber(this); val bystanders = Settings.maskBystanders(this)
        if (!FaceMask.any(faces, c.trimStartMs, c.trimEndMs, climber, bystanders)) {
            thread(name = "clip-export") {
                val made = runCatching { ClipExport.trimmedCopy(source, c.trimStartMs, c.trimEndMs, out) }
                    .onFailure { Log.e(TAG, "clip export failed", it) }.isSuccess
                runOnUiThread { if (!isDestroyed) handleExported(made, out) }
            }
            return
        }
        val progress = AlertDialog.Builder(this).setMessage(R.string.masking_faces_wait).setCancelable(false).show()
        export = MaskedExport.start(
            this, source, c.trimStartMs, c.trimEndMs, faces, climber, bystanders, out,
            onProgress = { progress.setMessage(getString(R.string.masking_faces, it)) },
        ) { made ->
            export = null
            if (isDestroyed) return@start
            progress.dismiss()
            handleExported(made, out)
        }
    }

    private fun handleExported(made: Boolean, out: File) {
        if (!made) { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), out.name))
    }

    companion object {
        private const val TAG = "ClimbPlayback"
        private const val EXTRA_CAPTURE = "capture"
        private const val EXTRA_SEND = "send"

        fun intent(context: Context, captureId: String, sendId: Int): Intent =
            Intent(context, ClimbPlaybackActivity::class.java).putExtra(EXTRA_CAPTURE, captureId).putExtra(EXTRA_SEND, sendId)
    }
}
