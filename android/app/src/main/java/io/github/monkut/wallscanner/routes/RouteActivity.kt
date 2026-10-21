package io.github.monkut.wallscanner.routes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.LocationStore
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.Send
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.io.count
import io.github.monkut.wallscanner.viewer.ViewerActivity
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Record of one confirmed route: its identification summary and the send log (sends.json of its capture). */
class RouteActivity : AppCompatActivity() {
    private lateinit var captureDir: File
    private lateinit var route: RouteRecord
    private lateinit var sendList: ListView
    private lateinit var emptyText: TextView
    private var sends: List<Send> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val captureId = intent.getStringExtra(EXTRA_CAPTURE)
        val routeId = intent.getIntExtra(EXTRA_ROUTE, -1)
        captureDir = File(CaptureStore.capturesDir(this), captureId ?: "")
        route = RouteStore.route(captureDir, routeId) ?: run { finish(); return }
        setContentView(R.layout.activity_route)

        findViewById<TextView>(R.id.routeName).text = route.name
        findViewById<TextView>(R.id.routeGrade).text = route.grade.ifBlank { getString(R.string.no_grade) }
        val location = LocationStore.get(this, route.locationId)
        findViewById<TextView>(R.id.routeMeta).text = getString(R.string.route_meta, location.label, route.type.label, route.captureId, route.holdCount)
        findViewById<ImageView>(R.id.thumbnail).setImageBitmap(CaptureStore.readThumbnail(captureDir))
        findViewById<View>(R.id.swatch).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(route.color[0], route.color[1], route.color[2]))
            setStroke(2, Color.WHITE)
        }
        findViewById<View>(R.id.btnLogSend).setOnClickListener { handleLogSend() }
        findViewById<View>(R.id.btnOpenCapture).setOnClickListener { startActivity(ViewerActivity.intent(this, route.captureId)) }
        sendList = findViewById(R.id.sendList)
        emptyText = findViewById(R.id.emptyText)
        sendList.setOnItemLongClickListener { _, _, position, _ -> handleDeleteSend(sends[position]); true }
    }

    override fun onResume() {
        super.onResume()
        route = RouteStore.route(captureDir, route.routeId) ?: run { finish(); return }
        refresh()
    }

    private fun refresh() {
        sends = RouteStore.sendsFor(captureDir, route.routeId).sortedByDescending { it.at }
        emptyText.visibility = if (sends.isEmpty()) View.VISIBLE else View.GONE
        sendList.adapter = SendAdapter(sends)
    }

    private fun handleLogSend() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_send, null)
        val results = view.findViewById<RadioGroup>(R.id.result)
        val attempts = view.findViewById<EditText>(R.id.attempts)
        val note = view.findViewById<EditText>(R.id.note)
        AlertDialog.Builder(this)
            .setTitle(R.string.log_send)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val result = when (results.checkedRadioButtonId) {
                    R.id.resultFlash -> SendResult.FLASH
                    R.id.resultAttempt -> SendResult.ATTEMPT
                    else -> SendResult.SEND
                }
                RouteStore.addSend(captureDir, route.routeId, result, attempts.text.toString().toIntOrNull() ?: 1, note.text.toString().trim())
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleDeleteSend(send: Send) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_send, sendLabel(send)))
            .setPositiveButton(R.string.delete) { _, _ -> RouteStore.deleteSend(captureDir, send.id); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendLabel(send: Send): String {
        val at = runCatching { ZonedDateTime.parse(send.at).withZoneSameInstant(JST).format(DATE_FORMAT) }.getOrDefault(send.at)
        val tries = count(send.attempts, "try", "tries")
        return "$at · ${send.result.label} · $tries"
    }

    private inner class SendAdapter(items: List<Send>) : ArrayAdapter<Send>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val send = getItem(position)!!
            view.findViewById<TextView>(android.R.id.text1).text = sendLabel(send)
            view.findViewById<TextView>(android.R.id.text2).apply {
                text = send.note
                visibility = if (send.note.isBlank()) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    companion object {
        private const val EXTRA_CAPTURE = "capture"
        private const val EXTRA_ROUTE = "route"
        private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun intent(context: Context, captureId: String, routeId: Int): Intent =
            Intent(context, RouteActivity::class.java).putExtra(EXTRA_CAPTURE, captureId).putExtra(EXTRA_ROUTE, routeId)
    }
}
