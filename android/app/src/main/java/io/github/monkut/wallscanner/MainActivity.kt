package io.github.monkut.wallscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.Location
import io.github.monkut.wallscanner.io.LocationKind
import io.github.monkut.wallscanner.io.LocationStore
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.io.count
import io.github.monkut.wallscanner.routes.RouteActivity
import io.github.monkut.wallscanner.scan.ScanActivity
import io.github.monkut.wallscanner.viewer.ViewerActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Home screen with a mode switch. **Capture** mode: New scan plus the capture list (scan sessions opened in
 * the viewer to identify and confirm routes). **Logbook** mode: progress summary and the confirmed routes
 * grouped by location, opened in [RouteActivity] to log sends. New scan asks for the location (a registered
 * gym or outdoor area, or a new one) and then the wall type.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var heading: TextView
    private lateinit var summary: TextView
    private lateinit var btnNewScan: View
    private lateinit var mode: MaterialButtonToggleGroup
    private var captures: List<File> = emptyList()
    private var routeRows: List<RouteRow> = emptyList()
    private var locations: Map<String, Location> = emptyMap()
    private val thumbnails = HashMap<String, Bitmap>()

    /** One row of the Routes tab: a location header or a confirmed route. */
    private class RouteRow(val header: Location?, val route: RouteRecord?)

    private companion object {
        const val PREF_DEFINITION = "definition"
        const val PREF_LOCATION = "location"
        const val PREF_MODE = "mode"
    }

    private val logbook get() = mode.checkedButtonId == R.id.modeLogbook

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.captureList)
        emptyText = findViewById(R.id.emptyText)
        heading = findViewById(R.id.heading)
        summary = findViewById(R.id.summary)
        btnNewScan = findViewById(R.id.btnNewScan)
        mode = findViewById(R.id.mode)
        btnNewScan.setOnClickListener { handleNewScan() }
        mode.check(if (getPreferences(MODE_PRIVATE).getBoolean(PREF_MODE, false)) R.id.modeLogbook else R.id.modeCapture)
        mode.addOnButtonCheckedListener { _, _, checked ->
            if (!checked) return@addOnButtonCheckedListener
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_MODE, logbook).apply()
            refresh()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            if (logbook) routeRows[position].route?.let { handleOpenRoute(it) } else handleOpenCapture(captures[position])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (!logbook) handleCaptureMenu(captures[position])
            !logbook
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        locations = LocationStore.list(this).associateBy { it.id }
        captures = CaptureStore.list(this)
        val confirmed = if (logbook) RouteStore.confirmedRoutes(this) else emptyList()
        routeRows = groupByLocation(confirmed)
        btnNewScan.visibility = if (logbook) View.GONE else View.VISIBLE
        heading.setText(if (logbook) R.string.routes else R.string.captures)
        summary.visibility = if (logbook) View.VISIBLE else View.GONE
        if (logbook) summary.text = progressSummary(confirmed)
        emptyText.setText(if (logbook) R.string.no_routes else R.string.no_captures)
        emptyText.visibility = if ((if (logbook) routeRows.isEmpty() else captures.isEmpty())) View.VISIBLE else View.GONE
        list.adapter = if (logbook) RouteAdapter(routeRows) else CaptureAdapter(captures)
        val missing = captures.filter { !thumbnails.containsKey(it.name) }
        if (missing.isEmpty()) return
        thread(name = "thumbnails") {
            for (dir in missing) {
                CaptureStore.ensureThumbnail(dir)
                val bitmap = CaptureStore.readThumbnail(dir) ?: continue
                runOnUiThread {
                    thumbnails[dir.name] = bitmap
                    (list.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun locationOf(id: String): Location = locations[id] ?: LocationStore.UNKNOWN

    private fun sendsOf(route: RouteRecord) = RouteStore.sendsFor(File(CaptureStore.capturesDir(this), route.captureId), route.routeId)

    /** Routes under a header per location, registered locations in list order, unknown last. */
    private fun groupByLocation(routes: List<RouteRecord>): List<RouteRow> {
        val order = locations.keys.toList() + ""
        val byLocation = routes.groupBy { if (it.locationId in locations) it.locationId else "" }
        return order.flatMap { id ->
            val group = byLocation[id] ?: return@flatMap emptyList()
            listOf(RouteRow(locationOf(id), null)) + group.map { RouteRow(null, it) }
        }
    }

    /** "3 routes · 5 sends · 2 flashes · 2 locations" across every confirmed route. */
    private fun progressSummary(routes: List<RouteRecord>): String {
        val sends = routes.flatMap { sendsOf(it) }
        val places = routes.map { if (it.locationId in locations) it.locationId else "" }.distinct().size
        return listOf(
            count(routes.size, "route"), count(sends.size, "send"),
            count(sends.count { it.result == SendResult.FLASH }, "flash", "flashes"), count(places, "location"),
        ).joinToString(" · ")
    }

    private fun headerLabel(location: Location, routes: List<RouteRecord>): String {
        val sends = routes.sumOf { sendsOf(it).size }
        return "${location.label} · ${count(routes.size, "route")} · ${count(sends, "send")}"
    }

    private fun handleOpenCapture(dir: File) {
        if (CaptureStore.hasScene(dir)) startActivity(ViewerActivity.intent(this, dir.name))
        else Toast.makeText(this, R.string.no_scene_hint, Toast.LENGTH_LONG).show()
    }

    private fun handleOpenRoute(route: RouteRecord) = startActivity(RouteActivity.intent(this, route.captureId, route.routeId))

    /** New scan: pick the location (remembered), then how routes are marked there (remembered), then scan. */
    private fun handleNewScan() {
        val prefs = getPreferences(MODE_PRIVATE)
        pickLocation(prefs.getString(PREF_LOCATION, null)) { location ->
            prefs.edit().putString(PREF_LOCATION, location.id).apply()
            val preferred = if (location.kind == LocationKind.OUTDOOR) RouteDefinition.OUTDOOR
            else RouteDefinition.fromId(prefs.getString(PREF_DEFINITION, null)).takeIf { !it.outdoor } ?: RouteDefinition.DEFAULT
            pickDefinition(preferred) { chosen ->
                prefs.edit().putString(PREF_DEFINITION, chosen.id).apply()
                startActivity(
                    Intent(this, ScanActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_DEFINITION, chosen.id)
                        .putExtra(ScanActivity.EXTRA_LOCATION, location.id)
                )
            }
        }
    }

    /** Single-choice list of registered locations plus "New location…"; the last used one is preselected. */
    private fun pickLocation(preselectedId: String?, onPicked: (Location) -> Unit) {
        val registered = LocationStore.list(this)
        if (registered.isEmpty()) { handleNewLocation(onPicked); return }
        val labels = (registered.map { it.label } + getString(R.string.new_location)).toTypedArray()
        var index = registered.indexOfFirst { it.id == preselectedId }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_location)
            .setSingleChoiceItems(labels, index) { _, which -> index = which }
            .setPositiveButton(R.string.next) { _, _ ->
                if (index < registered.size) onPicked(registered[index]) else handleNewLocation(onPicked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleNewLocation(onPicked: (Location) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location, null)
        val name = view.findViewById<EditText>(R.id.locationName)
        val kind = view.findViewById<RadioGroup>(R.id.kind)
        AlertDialog.Builder(this)
            .setTitle(R.string.new_location)
            .setView(view)
            .setPositiveButton(R.string.next) { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty()) { handleNewLocation(onPicked); return@setPositiveButton }
                val chosenKind = if (kind.checkedRadioButtonId == R.id.kindOutdoor) LocationKind.OUTDOOR else LocationKind.GYM
                onPicked(LocationStore.add(this, text, chosenKind))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDefinition(preselected: RouteDefinition, onPicked: (RouteDefinition) -> Unit) {
        val types = RouteDefinition.entries
        var chosen = preselected
        val labels = types.map { "${it.label}\n${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_definition)
            .setSingleChoiceItems(labels, types.indexOf(chosen)) { _, which -> chosen = types[which] }
            .setPositiveButton(R.string.start_scan) { _, _ -> onPicked(chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun captureLabel(dir: File): String {
        val type = CaptureStore.readDefinition(dir).type.label
        val location = locationOf(CaptureStore.readLocationId(dir)).name
        if (!CaptureStore.hasScene(dir)) return "${dir.name} · $location · $type\n${getString(R.string.incomplete_capture)}"
        return try {
            val holds = JSONObject(CaptureStore.readScene(dir)).getJSONArray("holds").length()
            val routes = RouteStore.routes(dir)
            "${dir.name} · $location · $type\n" + getString(R.string.capture_summary, holds, routes.size, routes.count { it.confirmed })
        } catch (e: Exception) {
            dir.name
        }
    }

    private fun routeLabel(route: RouteRecord): String {
        val sends = sendsOf(route)
        val title = if (route.grade.isBlank()) route.name else "${route.name} · ${route.grade}"
        val last = sends.maxByOrNull { it.at }?.let { " · last ${it.result.label.lowercase()} ${it.at.take(10)}" } ?: ""
        return "$title\n${route.type.label} · ${route.captureId} · ${count(sends.size, "send")}$last"
    }

    private fun handleCaptureMenu(dir: File) {
        val items = arrayOf(getString(R.string.set_location), getString(R.string.share), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickLocation(CaptureStore.readLocationId(dir)) { CaptureStore.setLocation(dir, it.id); refresh() }
                    1 -> CaptureStore.share(this, dir)
                    2 -> { CaptureStore.delete(dir); thumbnails.remove(dir.name); refresh() }
                }
            }
            .show()
    }

    private fun swatch(color: IntArray) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(color[0], color[1], color[2]))
        setStroke(2, Color.WHITE)
    }

    private inner class CaptureAdapter(items: List<File>) : ArrayAdapter<File>(this, R.layout.item_capture, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_capture, parent, false)
            val dir = getItem(position)!!
            view.findViewById<TextView>(R.id.label).text = captureLabel(dir)
            view.findViewById<ImageView>(R.id.thumbnail).setImageBitmap(thumbnails[dir.name])
            view.findViewById<View>(R.id.swatch).visibility = View.GONE
            return view
        }
    }

    private inner class RouteAdapter(items: List<RouteRow>) : ArrayAdapter<RouteRow>(this, R.layout.item_capture, items) {
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (getItem(position)!!.header != null) 0 else 1
        override fun isEnabled(position: Int) = getItem(position)!!.route != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)!!
            if (row.header != null) {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_header, parent, false)
                val group = routeRows.drop(position + 1).takeWhile { it.route != null }.map { it.route!! }
                view.findViewById<TextView>(R.id.header).text = headerLabel(row.header, group)
                return view
            }
            val route = row.route!!
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_capture, parent, false)
            view.findViewById<TextView>(R.id.label).text = routeLabel(route)
            view.findViewById<ImageView>(R.id.thumbnail).setImageBitmap(thumbnails[route.captureId])
            view.findViewById<View>(R.id.swatch).apply { visibility = View.VISIBLE; background = swatch(route.color) }
            return view
        }
    }
}
