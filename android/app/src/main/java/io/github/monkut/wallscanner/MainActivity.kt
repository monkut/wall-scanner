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
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.monkut.wallscanner.core.GradeSystem
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.io.CaptureStore
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import io.github.monkut.wallscanner.io.GeoPoint
import io.github.monkut.wallscanner.io.Location
import io.github.monkut.wallscanner.io.LocationKind
import io.github.monkut.wallscanner.io.LocationStore
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.SendLog
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.routes.RouteActivity
import io.github.monkut.wallscanner.scan.GpsFix
import io.github.monkut.wallscanner.scan.ScanActivity
import io.github.monkut.wallscanner.viewer.ViewerActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Home screen with a mode switch. **Capture** mode: New scan plus the capture list (scan sessions opened in
 * the viewer to identify and confirm routes). **Logbook** mode: the registered locations with their progress;
 * tap one to list its confirmed routes (opened in [RouteActivity] to log sends) under a header with **Edit**
 * (name, kind, grade scale, coordinates, delete). New scan asks for the location (a registered gym or outdoor
 * area, or a new one) and then the wall type. A language button switches the app language.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var heading: TextView
    private lateinit var summary: TextView
    /** The screen's one primary action: New scan in Capture mode, Add location on the Logbook's locations level. */
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnLanguage: MaterialButton
    private lateinit var mode: MaterialButtonToggleGroup
    private var captures: List<File> = emptyList()
    private var routeRows: List<RouteRow> = emptyList()
    private var locationRows: List<Location> = emptyList()
    private var confirmedRoutes: List<RouteRecord> = emptyList()
    /** sends.json per capture, read once per refresh for the logbook rows. */
    private val logs = HashMap<String, SendLog>()
    private var locations: Map<String, Location> = emptyMap()
    private val thumbnails = HashMap<String, Bitmap>()
    /** Logbook drill-down: null lists the locations, otherwise the routes of this location ("" = no location). */
    private var selectedLocationId: String? = null
    private var gps: GpsFix? = null
    private val backToLocations = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { selectedLocationId = null; refresh() }
    }

    /** One row of the logbook: a location header or a confirmed route. */
    private class RouteRow(val header: Location?, val route: RouteRecord?)

    private companion object {
        const val PREF_DEFINITION = "definition"
        const val PREF_LOCATION = "location"
        const val PREF_MODE = "mode"
    }

    private val logbook get() = mode.checkedButtonId == R.id.modeLogbook

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Language.ensureChosen()
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.captureList)
        emptyText = findViewById(R.id.emptyText)
        heading = findViewById(R.id.heading)
        summary = findViewById(R.id.summary)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnLanguage = findViewById(R.id.btnLanguage)
        mode = findViewById(R.id.mode)
        btnLanguage.setText(Language.current().short)
        btnLanguage.setOnClickListener { handleLanguage() }
        mode.check(if (getPreferences(MODE_PRIVATE).getBoolean(PREF_MODE, false)) R.id.modeLogbook else R.id.modeCapture)
        mode.addOnButtonCheckedListener { _, _, checked ->
            if (!checked) return@addOnButtonCheckedListener
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_MODE, logbook).apply()
            refresh()
        }
        onBackPressedDispatcher.addCallback(this, backToLocations)
        list.setOnItemClickListener { _, _, position, _ ->
            when {
                !logbook -> handleOpenCapture(captures[position])
                selectedLocationId == null -> { selectedLocationId = locationRows[position].id; refresh() }
                else -> routeRows[position].route?.let { handleOpenRoute(it) }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        // Not in onPause: the location permission prompt pauses the activity while the fix is pending.
        gps?.stop()
        gps = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != GpsFix.PERMISSION_CODE || grantResults.isEmpty()) return
        val fix = gps ?: return
        if (fix.hasPermission()) fix.start() else Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        locations = LocationStore.list(this).associateBy { it.id }
        captures = CaptureStore.list(this)
        logs.clear()
        val confirmed = if (logbook) RouteStore.confirmedRoutes(this) else emptyList()
        confirmedRoutes = confirmed
        val selected = selectedLocationId?.takeIf { logbook && (it.isEmpty() || it in locations) }
        selectedLocationId = selected
        backToLocations.isEnabled = selected != null
        locationRows = locations.values.toList() + if (confirmed.any { it.locationId !in locations }) listOf(LocationStore.UNKNOWN) else emptyList()
        val shown = if (selected == null) confirmed else confirmed.filter { keyOf(it) == selected }
        routeRows = if (selected == null) emptyList() else listOf(RouteRow(locationOf(selected), null)) + shown.map { RouteRow(null, it) }
        // One primary action per screen: label and handler decided together.
        btnPrimary.visibility = if (!logbook || selected == null) View.VISIBLE else View.GONE
        if (logbook) {
            btnPrimary.setText(R.string.add_location)
            btnPrimary.setOnClickListener { handleNewLocation(R.string.add_location, R.string.save) { refresh() } }
        } else {
            btnPrimary.setText(R.string.new_scan)
            btnPrimary.setOnClickListener { handleNewScan() }
        }
        heading.setText(if (!logbook) R.string.captures else if (selected == null) R.string.locations else R.string.routes)
        summary.visibility = if (logbook) View.VISIBLE else View.GONE
        if (logbook) summary.text = progressSummary(shown, countLocations = selected == null)
        emptyText.setText(if (!logbook) R.string.no_captures else if (selected == null) R.string.no_locations else R.string.no_routes)
        val empty = if (!logbook) captures.isEmpty() else if (selected == null) locationRows.isEmpty() else shown.isEmpty()
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        list.adapter = when {
            !logbook -> CaptureAdapter(captures)
            selected == null -> LocationAdapter(locationRows)
            else -> RouteAdapter(routeRows)
        }
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

    /** Location id a route is listed under: its own when registered, "" (no location) otherwise. */
    private fun keyOf(route: RouteRecord): String = if (route.locationId in locations) route.locationId else ""

    private fun routesAt(id: String): List<RouteRecord> = confirmedRoutes.filter { keyOf(it) == id }

    private fun capturesAt(id: String): List<File> = captures.filter { CaptureStore.readLocationId(it) == id }

    private fun logOf(captureId: String): SendLog =
        logs.getOrPut(captureId) { RouteStore.log(File(CaptureStore.capturesDir(this), captureId)) }

    private fun sendsOf(route: RouteRecord) = logOf(route.captureId).sends.filter { it.routeId == route.routeId }

    /** "3 routes, 5 sends, 2 flashes[, 2 locations]" over [routes]. */
    private fun progressSummary(routes: List<RouteRecord>, countLocations: Boolean): String {
        val sends = routes.flatMap { sendsOf(it) }
        return Labels.join(
            this,
            Labels.plural(this, R.plurals.routes_count, routes.size),
            Labels.plural(this, R.plurals.sends_count, sends.size),
            Labels.plural(this, R.plurals.flashes, sends.count { it.result == SendResult.FLASH }),
            if (countLocations) Labels.plural(this, R.plurals.locations_count, locationRows.size) else "",
        )
    }

    private fun handleOpenCapture(dir: File) {
        if (CaptureStore.hasScene(dir)) startActivity(ViewerActivity.intent(this, dir.name))
        else Toast.makeText(this, R.string.no_scene_hint, Toast.LENGTH_LONG).show()
    }

    private fun handleOpenRoute(route: RouteRecord) = startActivity(RouteActivity.intent(this, route.captureId, route.routeId))

    private fun handleLanguage() {
        val languages = Language.entries
        val current = Language.current()
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(languages.map { getString(it.label) }.toTypedArray(), languages.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                if (languages[which] != current) Language.apply(languages[which]) // recreates the activity
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
        if (registered.isEmpty()) { handleNewLocation { onPicked(it) }; return }
        val labels = (registered.map { "${it.name}\n${Labels.locationDetail(this, it)}" } + getString(R.string.new_location)).toTypedArray()
        var index = registered.indexOfFirst { it.id == preselectedId }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_location)
            .setSingleChoiceItems(labels, index) { _, which -> index = which }
            .setPositiveButton(R.string.next) { _, _ ->
                if (index < registered.size) onPicked(registered[index]) else handleNewLocation { onPicked(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Registers a location and remembers it for the next New scan. Inside the scan flow the form is titled
     * "New location…" with Next; from the Logbook it is "Add location" with Save. A name already registered
     * (trimmed, case-insensitive) re-opens the form with the draft kept.
     */
    private fun handleNewLocation(
        @StringRes title: Int = R.string.new_location, @StringRes positive: Int = R.string.next, onPicked: (Location) -> Unit,
    ) {
        locationDialog(title, positive, null) { result ->
            if (nameTaken(result.name, exceptId = "")) { reopenWithDraft(title, positive, null, result) { handleNewLocation(title, positive, onPicked) }; return@locationDialog }
            val location = LocationStore.add(this, result.name, result.kind, result.gradeSystem, result.coords)
            getPreferences(MODE_PRIVATE).edit().putString(PREF_LOCATION, location.id).apply()
            onPicked(location)
        }
    }

    private fun nameTaken(name: String, exceptId: String): Boolean =
        LocationStore.list(this).any { it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) }

    /** Tells the user the name is taken and re-opens the form with everything they typed. */
    private fun reopenWithDraft(@StringRes title: Int, @StringRes positive: Int, existing: Location?, result: Location, onSave: (Location) -> Unit) {
        Toast.makeText(this, getString(R.string.location_name_taken, result.name), Toast.LENGTH_LONG).show()
        val draft = LocationDraft(result.name, result.kind, result.gradeSystem, result.coords?.text() ?: "", result.coords?.takeIf { !it.manual })
        locationDialog(title, positive, existing, draft, onSave)
    }

    /** Edit from the logbook header: name, kind, grade scale and coordinates, or delete. */
    private fun handleEditLocation(location: Location) =
        locationDialog(R.string.edit_location, R.string.save, location) { result ->
            if (nameTaken(result.name, exceptId = location.id)) { reopenWithDraft(R.string.edit_location, R.string.save, location, result) { LocationStore.update(this, it); refresh() }; return@locationDialog }
            LocationStore.update(this, result)
            refresh()
        }

    private fun handleDeleteLocation(location: Location) {
        val captures = capturesAt(location.id).size
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_location)
            .setMessage(getString(R.string.delete_location_confirm, location.name, Labels.plural(this, R.plurals.captures_count, captures)))
            .setPositiveButton(R.string.delete) { _, _ ->
                LocationStore.remove(this, location.id)
                selectedLocationId = null
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Unsaved form state, carried over when the form re-opens after a validation error. */
    private class LocationDraft(val name: String, val kind: LocationKind, val gradeSystem: GradeSystem, val coordsText: String, val fixed: GeoPoint?)

    /**
     * Name, kind, grade scale and coordinates form, prefilled from [draft] (a re-open) or [existing] (editing,
     * which also offers Delete). An empty name or unparsable coordinates re-opens the form with what was typed.
     * "Use phone position" fills the coordinates from a fix, asking for location permission first; a fix left
     * as is keeps its accuracy, typed coordinates are stored as hand-entered.
     */
    private fun locationDialog(
        @StringRes title: Int, @StringRes positive: Int, existing: Location?, draft: LocationDraft? = null,
        onSave: (Location) -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location, null)
        val name = view.findViewById<EditText>(R.id.locationName)
        val kind = view.findViewById<RadioGroup>(R.id.kind)
        val grades = view.findViewById<RadioGroup>(R.id.gradeSystem)
        val coords = view.findViewById<EditText>(R.id.locationCoords)
        val initial = draft ?: existing?.let { LocationDraft(it.name, it.kind, it.gradeSystem, it.coords?.text() ?: "", null) }
        if (initial != null) {
            name.setText(initial.name)
            kind.check(if (initial.kind == LocationKind.OUTDOOR) R.id.kindOutdoor else R.id.kindGym)
            grades.check(
                when (initial.gradeSystem) {
                    GradeSystem.YDS -> R.id.gradesYds
                    GradeSystem.V_SCALE -> R.id.gradesV
                    GradeSystem.FONT -> R.id.gradesFont
                    else -> R.id.gradesKyu
                }
            )
            coords.setText(initial.coordsText)
        }
        var fixed: GeoPoint? = initial?.fixed // device fix taken in this form; kept with its accuracy when saved as is
        var fix: GpsFix? = null
        view.findViewById<View>(R.id.btnUsePosition).setOnClickListener { fix = handleUsePhonePosition(coords) { fixed = it } }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(positive) { _, _ ->
                val text = name.text.toString().trim()
                val coordsText = coords.text.toString().trim()
                val point = if (coordsText.isEmpty()) null else GeoPoint.parse(coordsText, CaptureStore.nowIso())
                if (text.isEmpty() || (coordsText.isNotEmpty() && point == null)) {
                    if (coordsText.isNotEmpty() && point == null) Toast.makeText(this, R.string.coordinates_invalid, Toast.LENGTH_LONG).show()
                    locationDialog(title, positive, existing, LocationDraft(text, chosenKind(kind), chosenGrades(grades), coordsText, fixed), onSave)
                    return@setPositiveButton
                }
                val kept = listOfNotNull(fixed, existing?.coords).firstOrNull { it.text() == coordsText }
                onSave(Location(existing?.id ?: "", text, chosenKind(kind), chosenGrades(grades), kept ?: point))
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null && existing.id.isNotEmpty()) builder.setNeutralButton(R.string.delete) { _, _ -> handleDeleteLocation(existing) }
        val dialog = builder.show()
        // Destructive, so it must not read as a second primary action.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ContextCompat.getColor(this, R.color.danger))
        dialog.setOnDismissListener {
            fix?.stop()
            if (gps === fix) gps = null
        }
    }

    private fun chosenKind(group: RadioGroup) = if (group.checkedRadioButtonId == R.id.kindOutdoor) LocationKind.OUTDOOR else LocationKind.GYM

    private fun chosenGrades(group: RadioGroup) = when (group.checkedRadioButtonId) {
        R.id.gradesYds -> GradeSystem.YDS
        R.id.gradesV -> GradeSystem.V_SCALE
        R.id.gradesFont -> GradeSystem.FONT
        else -> GradeSystem.KYU_DAN
    }

    /** Fills [coords] with the best fix as it arrives and reports it to [onFix]; the field stays editable. */
    private fun handleUsePhonePosition(coords: EditText, onFix: (GeoPoint) -> Unit): GpsFix {
        gps?.stop()
        val fix = GpsFix(
            this, log = {},
            onUnavailable = { Toast.makeText(this, R.string.position_unavailable, Toast.LENGTH_LONG).show() },
        ) { point -> coords.setText(point.text()); onFix(point) }
        gps = fix
        if (fix.hasPermission()) fix.start() else fix.requestPermission()
        return fix
    }

    private fun pickDefinition(preselected: RouteDefinition, onPicked: (RouteDefinition) -> Unit) {
        val types = RouteDefinition.entries
        var chosen = preselected
        val labels = types.map { "${Labels.definition(this, it)}\n${Labels.definitionDescription(this, it)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_definition)
            .setSingleChoiceItems(labels, types.indexOf(chosen)) { _, which -> chosen = types[which] }
            .setPositiveButton(R.string.start_scan) { _, _ -> onPicked(chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleCaptureMenu(dir: File) {
        val items = arrayOf(getString(R.string.set_location), getString(R.string.reprocess), getString(R.string.share), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(Labels.captureDate(this, dir.name))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickLocation(CaptureStore.readLocationId(dir)) { CaptureStore.setLocation(dir, it.id); refresh() }
                    1 -> handleReprocess(dir)
                    2 -> CaptureStore.share(this, dir)
                    3 -> { CaptureStore.delete(dir); thumbnails.remove(dir.name); refresh() }
                }
            }
            .show()
    }

    /** Re-runs hold and route detection on a capture's saved point cloud (CaptureStore.reprocess) after a confirmation. */
    private fun handleReprocess(dir: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reprocess)
            .setMessage(R.string.reprocess_message)
            .setPositiveButton(R.string.reprocess) { _, _ ->
                val progress = AlertDialog.Builder(this).setMessage(R.string.processing).setCancelable(false).show()
                thread(name = "reprocess") {
                    val summary = runCatching { CaptureStore.reprocess(dir) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        progress.dismiss()
                        thumbnails.remove(dir.name)
                        refresh()
                        val message = if (summary != null) getString(R.string.reprocess_done, summary) else getString(R.string.reprocess_failed)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun swatch(color: IntArray) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(color[0], color[1], color[2]))
        setStroke(2, Color.WHITE)
    }

    private fun bindRow(view: View, title: String, subtitle: String, meta: String, thumbnail: Bitmap?, swatchColor: IntArray?) {
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.subtitle).text = subtitle
        view.findViewById<TextView>(R.id.meta).text = meta
        view.findViewById<ImageView>(R.id.thumbnail).setImageBitmap(thumbnail)
        view.findViewById<View>(R.id.swatch).apply {
            visibility = if (swatchColor == null) View.GONE else View.VISIBLE
            if (swatchColor != null) background = swatch(swatchColor)
        }
    }

    private inner class CaptureAdapter(items: List<File>) : ArrayAdapter<File>(this, R.layout.item_capture, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_capture, parent, false)
            val dir = getItem(position)!!
            val c = this@MainActivity
            val place = Labels.join(c, Labels.location(c, locationOf(CaptureStore.readLocationId(dir))), Labels.definition(c, CaptureStore.readDefinition(dir).type))
            val meta = if (!CaptureStore.hasScene(dir)) getString(R.string.incomplete_capture) else runCatching {
                val holds = JSONObject(CaptureStore.readScene(dir)).getJSONArray("holds").length()
                val routes = RouteStore.routes(dir)
                Labels.join(
                    c, Labels.plural(c, R.plurals.holds, holds), Labels.plural(c, R.plurals.routes_count, routes.size),
                    Labels.plural(c, R.plurals.confirmed_count, routes.count { it.confirmed }),
                )
            }.getOrDefault("")
            bindRow(view, Labels.captureDate(c, dir.name), place, meta, thumbnails[dir.name], null)
            return view
        }
    }

    private fun bindLocationHeader(view: View, location: Location, routes: List<RouteRecord>) {
        val c = this@MainActivity
        view.findViewById<TextView>(R.id.header).text = Labels.location(c, location)
        view.findViewById<TextView>(R.id.headerDetail).text = Labels.locationDetail(c, location)
        view.findViewById<TextView>(R.id.headerCounts).text =
            Labels.join(c, Labels.plural(c, R.plurals.routes_count, routes.size), Labels.plural(c, R.plurals.sends_count, routes.sumOf { sendsOf(it).size }))
    }

    /** Logbook top level: one row per registered location (plus "No location" when orphan routes exist). */
    private inner class LocationAdapter(items: List<Location>) : ArrayAdapter<Location>(this, R.layout.item_header, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_header, parent, false)
            val location = getItem(position)!!
            bindLocationHeader(view, location, routesAt(location.id))
            view.findViewById<View>(R.id.headerCounts).visibility = View.VISIBLE
            view.findViewById<View>(R.id.btnEditLocation).visibility = View.GONE
            view.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
            return view
        }
    }

    private inner class RouteAdapter(items: List<RouteRow>) : ArrayAdapter<RouteRow>(this, R.layout.item_capture, items) {
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (getItem(position)!!.header != null) 0 else 1
        override fun isEnabled(position: Int) = getItem(position)!!.route != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)!!
            val c = this@MainActivity
            if (row.header != null) {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_header, parent, false)
                bindLocationHeader(view, row.header, routeRows.drop(position + 1).mapNotNull { it.route })
                // The summary line above already carries this location's counts.
                view.findViewById<View>(R.id.headerCounts).visibility = View.GONE
                view.findViewById<View>(R.id.chevron).visibility = View.GONE
                view.findViewById<View>(R.id.btnEditLocation).apply {
                    visibility = if (row.header.id.isEmpty()) View.GONE else View.VISIBLE
                    setOnClickListener { handleEditLocation(row.header) }
                }
                return view
            }
            val route = row.route!!
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_capture, parent, false)
            val sends = sendsOf(route)
            val last = sends.maxByOrNull { it.at }
            val title = if (route.grade.isBlank()) route.name else "${route.name} ${route.grade}"
            val rating = logOf(route.captureId).ratings[route.routeId]
            val meta = Labels.join(
                c, Labels.plural(c, R.plurals.sends_count, sends.size),
                last?.let { getString(R.string.last_send, Labels.result(c, it.result).lowercase(), Labels.isoDate(c, it.at)) } ?: "",
                rating?.let { getString(R.string.star_rating, it) } ?: "",
            )
            val subtitle = Labels.join(c, Labels.definition(c, route.type), getString(R.string.from_capture, Labels.captureDay(c, route.captureId)))
            bindRow(view, title, subtitle, meta, thumbnails[route.captureId], route.color)
            return view
        }
    }
}
