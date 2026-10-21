package io.github.monkut.wallscanner.routes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.RatingBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.monkut.wallscanner.Labels
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.LocationStore
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.Send
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.viewer.ViewerActivity
import java.io.File

/** Record of one confirmed route: its identification summary and the send log (sends.json of its capture). */
class RouteActivity : AppCompatActivity() {
    private lateinit var captureDir: File
    private lateinit var route: RouteRecord
    private lateinit var sendList: ListView
    private lateinit var emptyText: TextView
    private lateinit var ratingBar: RatingBar
    private lateinit var ratingLabel: TextView
    private lateinit var btnClearRating: View
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
        findViewById<TextView>(R.id.routeMeta).text = getString(
            R.string.route_meta, Labels.location(this, location), Labels.definition(this, route.type),
            Labels.plural(this, R.plurals.holds, route.holdCount),
        )
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
        ratingBar = findViewById(R.id.rating)
        ratingLabel = findViewById(R.id.ratingLabel)
        btnClearRating = findViewById(R.id.btnClearRating)
        ratingBar.numStars = RouteStore.MAX_RATING
        // The label follows the finger; the rating is written once, when the finger lifts.
        ratingBar.setOnRatingBarChangeListener { _, stars, fromUser -> if (fromUser) showRating(stars.toInt()) }
        ratingBar.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                v.performClick()
                handleRate(ratingBar.rating.toInt())
            }
            false
        }
        btnClearRating.setOnClickListener { handleRate(null) }
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
        refreshRating()
    }

    private fun refreshRating() {
        val rating = RouteStore.rating(captureDir, route.routeId)
        ratingBar.rating = (rating ?: 0).toFloat()
        showRating(rating)
    }

    private fun showRating(rating: Int?) {
        ratingLabel.text = if (rating == null) getString(R.string.not_rated) else getString(R.string.rating_value, rating, RouteStore.MAX_RATING)
        btnClearRating.visibility = if (rating == null) View.GONE else View.VISIBLE
    }

    /** Stores [stars] (0–5) as the climb's rating, or clears it with null. */
    private fun handleRate(stars: Int?) {
        if (stars == RouteStore.rating(captureDir, route.routeId)) return
        RouteStore.setRating(captureDir, route.routeId, stars)
        refreshRating()
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
            .setMessage(getString(R.string.delete_send, Labels.join(this, resultLabel(send), Labels.isoDateTime(this, send.at))))
            .setPositiveButton(R.string.delete) { _, _ -> RouteStore.deleteSend(captureDir, send.id); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Flash, 1 try" */
    private fun resultLabel(send: Send): String =
        Labels.join(this, Labels.result(this, send.result), Labels.plural(this, R.plurals.tries, send.attempts))

    private inner class SendAdapter(items: List<Send>) : ArrayAdapter<Send>(this, R.layout.item_send, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_send, parent, false)
            val send = getItem(position)!!
            view.findViewById<TextView>(R.id.result).text = resultLabel(send)
            view.findViewById<TextView>(R.id.date).text = Labels.isoDateTime(this@RouteActivity, send.at)
            view.findViewById<TextView>(R.id.note).apply {
                text = send.note
                visibility = if (send.note.isBlank()) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    companion object {
        private const val EXTRA_CAPTURE = "capture"
        private const val EXTRA_ROUTE = "route"

        fun intent(context: Context, captureId: String, routeId: Int): Intent =
            Intent(context, RouteActivity::class.java).putExtra(EXTRA_CAPTURE, captureId).putExtra(EXTRA_ROUTE, routeId)
    }
}
