package io.github.monkut.wallscanner

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.monkut.wallscanner.climbs.CaptionPainter
import io.github.monkut.wallscanner.core.CaptionFont
import io.github.monkut.wallscanner.core.Corner
import io.github.monkut.wallscanner.core.GradeSystem
import io.github.monkut.wallscanner.core.Length
import io.github.monkut.wallscanner.core.Processing
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.core.TNutPattern
import io.github.monkut.wallscanner.core.UnitSystem
import io.github.monkut.wallscanner.core.WallMap
import io.github.monkut.wallscanner.io.CaptureJoin
import io.github.monkut.wallscanner.io.CaptureStore
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputLayout
import androidx.annotation.StringRes
import io.github.monkut.wallscanner.io.GeoPoint
import io.github.monkut.wallscanner.io.Location
import io.github.monkut.wallscanner.io.LocationKind
import io.github.monkut.wallscanner.io.LocationStore
import io.github.monkut.wallscanner.io.Profile
import io.github.monkut.wallscanner.io.ProfileStore
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.RouteStore
import io.github.monkut.wallscanner.io.SendLog
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.io.Wall
import io.github.monkut.wallscanner.io.WallSet
import io.github.monkut.wallscanner.io.WallStore
import io.github.monkut.wallscanner.routes.RouteActivity
import io.github.monkut.wallscanner.scan.GpsFix
import io.github.monkut.wallscanner.scan.ScanActivity
import io.github.monkut.wallscanner.viewer.ViewerActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Home screen with a mode switch. **Capture** mode: New scan plus the capture list (scan sessions opened in
 * the viewer to identify and confirm routes). **Logbook** mode: the registered locations with their progress;
 * tap one to list its confirmed routes (opened in [RouteActivity] to log sends) under a header with **Edit**
 * (name, kind, grade scale, coordinates, delete); at a gym the routes are grouped by wall (once the gym has
 * registered walls, each under a header with **Edit**: rename, delete) and inside by set (the routes on the wall
 * between two resets), each under a header with **Edit** (rename, retire, walls covered). New scan asks for the
 * location (a registered gym or outdoor area, or a new one), at a gym the wall and the set (an open one covering
 * that wall, or a new one), and then the wall type. The climber's picture in the header opens Settings: the profile
 * (picture, username, height, reach), the units its lengths are shown in, the face masks for climb videos, and the
 * app language.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var heading: TextView
    private lateinit var summary: TextView
    /** The screen's one primary action: New scan in Capture mode, Add location on the Logbook's locations level. */
    private lateinit var btnPrimary: MaterialButton
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
    /** Receives the photo picker's result for the open profile form; null when no form is waiting. */
    private var onPhotoPicked: ((Uri) -> Unit)? = null
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPhotoPicked?.invoke(uri) }
    private val backToLocations = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { selectedLocationId = null; refresh() }
    }

    /**
     * One row of the logbook: a location header, a wall header ([NO_WALL] for routes scanned without one), a set
     * header ([NO_SET] for routes outside every set), or a confirmed route.
     */
    private class RouteRow(val header: Location? = null, val wall: Wall? = null, val set: WallSet? = null, val route: RouteRecord? = null)

    private companion object {
        const val PREF_DEFINITION = "definition"
        const val PREF_LOCATION = "location"
        const val PREF_WALL = "wall"
        const val PREF_SET = "set"
        const val PREF_MODE = "mode"
        val NO_SET = WallSet("", "", "")
        val NO_WALL = Wall("", "")
        /** Logbook header sizes: location and wall headers, and set headers nested under a wall (item_header.xml sets 18sp). */
        const val HEADER_SP = 18f
        const val SUBHEADER_SP = 16f
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
        mode = findViewById(R.id.mode)
        findViewById<View>(R.id.btnSettings).setOnClickListener { handleSettings() }
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
        bindHeaderAvatar()
        refresh()
    }

    private fun bindHeaderAvatar() = Avatar.bind(findViewById(R.id.btnSettings), ProfileStore.read(this), ProfileStore.picture(this))

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
        routeRows = if (selected == null) emptyList() else {
            val location = locationOf(selected)
            listOf(RouteRow(header = location)) + if (location.walls.isEmpty()) setRows(location, shown, wallId = "") else {
                // A gym split into walls: each wall's routes under its header; routes with no (or a deleted) wall last under "No wall".
                val orphans = shown.filter { location.wall(it.wallId) == null }
                val byWall = location.walls.map { wall -> wall to shown.filter { it.wallId == wall.id } } + (if (orphans.isEmpty()) emptyList() else listOf(NO_WALL to orphans))
                // A set gets an empty header only where it has no routes at all in the gym, once, under the first wall it covers.
                val emptyShown = HashSet(shown.map { it.setId })
                byWall.flatMap { (wall, routes) -> listOf(RouteRow(wall = wall)) + setRows(location, routes, wall.id, emptyShown) }
            }
        }
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

    /**
     * Set headers with their routes: the sets with routes here, plus (so it can be edited or retired) a set
     * covering [wallId] that has routes nowhere in the gym, once ([emptyShown] holds the sets already placed);
     * open sets first, newest first, then retired. Routes outside every set come last under "No set". A gym without
     * walls lists every set.
     */
    private fun setRows(location: Location, routes: List<RouteRecord>, wallId: String, emptyShown: MutableSet<String>? = null): List<RouteRow> {
        val sets = WallSet.ordered(location.sets.filter { set ->
            location.walls.isEmpty() || routes.any { it.setId == set.id } || (set.covers(wallId) && (emptyShown == null || emptyShown.add(set.id)))
        })
        val groups = sets.map { set -> set to routes.filter { it.setId == set.id } }
        val outside = routes.filter { r -> sets.none { it.id == r.setId } }
        return groups.flatMap { (set, inSet) -> listOf(RouteRow(set = set)) + inSet.map { RouteRow(route = it) } } +
            (if (groups.isNotEmpty() && outside.isNotEmpty()) listOf(RouteRow(set = NO_SET)) else emptyList()) +
            outside.map { RouteRow(route = it) }
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

    /**
     * Settings: the climber (picture, name, height and reach) above the app's preferences, each with its current
     * value. Choosing one closes this dialog; it reopens after a profile or units change so the values update.
     */
    private fun handleSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val profile = ProfileStore.read(this)
        val units = Settings.units(this)
        Avatar.bind(view.findViewById(R.id.settingsAvatar), profile, ProfileStore.picture(this))
        view.findViewById<TextView>(R.id.settingsName).text = profile.username.ifEmpty { getString(R.string.profile_add) }
        view.findViewById<TextView>(R.id.settingsDetail).text =
            if (profile.username.isEmpty() && profile.heightCm == null && profile.reachCm == null) getString(R.string.profile_add_hint)
            else Labels.profileDetail(this, profile, units).ifEmpty { getString(R.string.profile) }
        bindSetting(view.findViewById(R.id.settingsFaceMasks), R.string.face_masks, Labels.faceMasks(this, Settings.maskClimber(this), Settings.maskBystanders(this)))
        bindSetting(view.findViewById(R.id.settingsCaption), R.string.caption, Labels.captionSetting(this, Settings.caption(this), Settings.captionCorner(this), Settings.captionFont(this)))
        bindSetting(view.findViewById(R.id.settingsUnits), R.string.units, Labels.units(this, units))
        bindSetting(view.findViewById(R.id.settingsLanguage), R.string.language, getString(Language.current().label))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.settings).setView(view).show()
        fun open(id: Int, action: () -> Unit) = view.findViewById<View>(id).setOnClickListener { dialog.dismiss(); action() }
        open(R.id.settingsProfile) { handleEditProfile() }
        open(R.id.settingsFaceMasks) { handleFaceMasks() }
        open(R.id.settingsCaption) { handleCaption() }
        open(R.id.settingsUnits) { handleUnits() }
        open(R.id.settingsLanguage) { handleLanguage() }
    }

    private fun bindSetting(row: View, @StringRes title: Int, value: String) {
        row.findViewById<TextView>(R.id.settingTitle).setText(title)
        row.findViewById<TextView>(R.id.settingValue).text = value
    }

    /** Face masks (issue #12): whether the climber's face and bystanders' faces are hidden in climb videos; both on by default. */
    private fun handleFaceMasks() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_face_masks, null)
        val climber = view.findViewById<CheckBox>(R.id.maskClimber).apply { isChecked = Settings.maskClimber(this@MainActivity) }
        val bystanders = view.findViewById<CheckBox>(R.id.maskBystanders).apply { isChecked = Settings.maskBystanders(this@MainActivity) }
        AlertDialog.Builder(this)
            .setTitle(R.string.face_masks)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                Settings.setFaceMasks(this, climber.isChecked, bystanders.isChecked)
                handleSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handleSettings() }
            .show()
    }

    /** Caption on climb videos (issue #13): on or off, which corner, and the font (each choice set in its own typeface). */
    private fun handleCaption() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_caption, null)
        val on = view.findViewById<CheckBox>(R.id.captionOn).apply { isChecked = Settings.caption(this@MainActivity) }
        val corners = view.findViewById<RadioGroup>(R.id.captionCorner)
        val cornerIds = mapOf(Corner.UPPER_LEFT to R.id.cornerUpperLeft, Corner.UPPER_RIGHT to R.id.cornerUpperRight, Corner.LOWER_LEFT to R.id.cornerLowerLeft, Corner.LOWER_RIGHT to R.id.cornerLowerRight)
        corners.check(cornerIds.getValue(Settings.captionCorner(this)))
        val fonts = view.findViewById<RadioGroup>(R.id.captionFont)
        val current = Settings.captionFont(this)
        for (font in CaptionFont.entries) {
            fonts.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = font
                text = Labels.captionFont(this@MainActivity, font)
                typeface = CaptionPainter.typeface(this@MainActivity, font, bold = false)
                isChecked = font == current
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.caption)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val corner = cornerIds.entries.firstOrNull { it.value == corners.checkedRadioButtonId }?.key ?: Corner.DEFAULT
                val font = fonts.findViewById<RadioButton>(fonts.checkedRadioButtonId)?.tag as? CaptionFont ?: CaptionFont.DEFAULT
                Settings.setCaption(this, on.isChecked, corner, font)
                handleSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handleSettings() }
            .show()
    }

    private fun handleUnits() {
        val choices = UnitSystem.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.units)
            .setSingleChoiceItems(choices.map { Labels.units(this, it) }.toTypedArray(), choices.indexOf(Settings.units(this))) { dialog, which ->
                dialog.dismiss()
                Settings.setUnits(this, choices[which])
                handleSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Picture, username, height and reach, all optional. A chosen or removed picture is kept with the form until
     * Save (Cancel leaves the stored one); a length outside [Length.RANGE_CM] keeps the form open with an error on it.
     */
    private fun handleEditProfile() {
        val profile = ProfileStore.read(this)
        val units = Settings.units(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)
        val avatar = view.findViewById<ShapeableImageView>(R.id.profileAvatar)
        val remove = view.findViewById<View>(R.id.btnRemovePhoto)
        val username = view.findViewById<EditText>(R.id.username).apply { setText(profile.username) }
        val height = LengthField(view.findViewById(R.id.height), units, profile.heightCm, getString(R.string.profile_height), null)
        val reach = LengthField(view.findViewById(R.id.reach), units, profile.reachCm, getString(R.string.profile_reach), getString(R.string.profile_reach_hint))
        var photo = ProfileStore.picture(this)
        var photoChanged = false
        fun showPhoto() {
            Avatar.bind(avatar, Profile(username.text.toString()), photo)
            remove.visibility = if (photo != null) View.VISIBLE else View.GONE
        }
        showPhoto()
        username.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (photo == null) showPhoto() } // the initial follows the name
        })
        onPhotoPicked = { uri ->
            thread(name = "profile-photo") {
                val picked = ProfileStore.decodePicture(this, uri)
                runOnUiThread {
                    if (picked == null) Toast.makeText(this, R.string.profile_photo_unreadable, Toast.LENGTH_LONG).show()
                    else { photo = picked; photoChanged = true; showPhoto() }
                }
            }
        }
        val choose = View.OnClickListener { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        avatar.setOnClickListener(choose)
        view.findViewById<View>(R.id.btnChoosePhoto).setOnClickListener(choose)
        remove.setOnClickListener { photo = null; photoChanged = true; showPhoto() }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.profile)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.setOnDismissListener { onPhotoPicked = null }
        // Set after show(): the builder's listener always dismisses the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val error = getString(R.string.length_invalid, Length.format(Length.RANGE_CM.start, units), Length.format(Length.RANGE_CM.endInclusive, units))
            if (false in listOf(height, reach).map { it.validate(error) }) return@setOnClickListener
            ProfileStore.write(this, Profile(username.text.toString().trim(), height.value(), reach.value()))
            if (photoChanged) ProfileStore.writePicture(this, photo)
            bindHeaderAvatar()
            dialog.dismiss()
            handleSettings()
        }
    }

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

    /** New scan: pick the location (remembered), at a gym its wall and set (remembered), then how routes are marked there (remembered), then scan. */
    private fun handleNewScan() {
        val prefs = getPreferences(MODE_PRIVATE)
        pickLocation(prefs.getString(PREF_LOCATION, null)) { location ->
            prefs.edit().putString(PREF_LOCATION, location.id).apply()
            pickWall(location, prefs.getString(PREF_WALL, null)) { wall ->
                prefs.edit().putString(PREF_WALL, wall?.id ?: "").apply()
                pickSet(location, wall?.id ?: "", prefs.getString(PREF_SET, null)) { set ->
                    prefs.edit().putString(PREF_SET, set?.id ?: "").apply()
                    val preferred = if (location.kind == LocationKind.OUTDOOR) RouteDefinition.OUTDOOR
                    else RouteDefinition.fromId(prefs.getString(PREF_DEFINITION, null)).takeIf { !it.outdoor } ?: RouteDefinition.DEFAULT
                    pickDefinition(preferred) { chosen ->
                        prefs.edit().putString(PREF_DEFINITION, chosen.id).apply()
                        startActivity(
                            Intent(this, ScanActivity::class.java)
                                .putExtra(ScanActivity.EXTRA_DEFINITION, chosen.id)
                                .putExtra(ScanActivity.EXTRA_LOCATION, location.id)
                                .putExtra(ScanActivity.EXTRA_WALL, wall?.id ?: "")
                                .putExtra(ScanActivity.EXTRA_SET, set?.id ?: "")
                        )
                    }
                }
            }
        }
    }

    /**
     * Gym walls: single choice of the registered walls plus "New wall…"; the last used wall is preselected. A gym
     * without walls goes straight to the New wall form, whose "Without a wall" carries on with none. An outdoor
     * area has no walls. Cancel runs [onCancel] (New scan stops; Set location keeps the capture's wall and set).
     */
    private fun pickWall(location: Location, preselectedId: String?, onCancel: () -> Unit = {}, onPicked: (Wall?) -> Unit) {
        if (location.kind != LocationKind.GYM) { onPicked(null); return }
        val walls = location.walls
        if (walls.isEmpty()) { handleNewWall(location, onPicked); return }
        val labels = (walls.map { it.name } + getString(R.string.new_wall)).toTypedArray()
        var index = walls.indexOfFirst { it.id == preselectedId }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_wall)
            .setSingleChoiceItems(labels, index) { _, which -> index = which }
            .setPositiveButton(R.string.next) { _, _ -> if (index < walls.size) onPicked(walls[index]) else handleNewWall(location, onPicked) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    /** Registers a wall at the gym ("Main wall"); "Without a wall" goes on with none. */
    private fun handleNewWall(location: Location, onPicked: (Wall?) -> Unit) =
        wallDialog(R.string.new_wall, R.string.next, location, existing = null, onSave = { onPicked(LocationStore.addWall(this, location.id, it)) }) {
            it.setNegativeButton(R.string.without_wall) { _, _ -> onPicked(null) }
        }

    /** Edit from a wall header: rename, or delete (asks first). */
    private fun handleEditWall(location: Location, wall: Wall) =
        wallDialog(R.string.edit_wall, R.string.save, location, wall, onSave = { LocationStore.updateWall(this, location.id, Wall(wall.id, it)); refresh() }) {
            it.setNegativeButton(android.R.string.cancel, null)
            it.setNeutralButton(R.string.delete) { _, _ -> handleDeleteWall(location, wall) }
        }

    /**
     * Wall name form shared by New wall and Edit wall; [buttons] adds the dialog's other buttons. An empty name or
     * one already used at the gym (trimmed, case-insensitive) re-opens the form with the draft kept.
     */
    private fun wallDialog(
        @StringRes title: Int, @StringRes positive: Int, location: Location, existing: Wall?, draft: String? = null,
        onSave: (String) -> Unit, buttons: (AlertDialog.Builder) -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_wall, null)
        val name = view.findViewById<EditText>(R.id.wallName).apply { setText(draft ?: existing?.name ?: "") }
        if (existing != null) bindWallMap(view, existing)
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(positive) { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty() || wallNameTaken(location, text, exceptId = existing?.id ?: "")) {
                    if (text.isNotEmpty()) Toast.makeText(this, getString(R.string.wall_name_taken, text), Toast.LENGTH_LONG).show()
                    wallDialog(title, positive, location, existing, text, onSave, buttons)
                    return@setPositiveButton
                }
                onSave(text)
            }
        buttons(builder)
        // Destructive, so it must not read as a second primary action.
        builder.show().getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    private fun wallNameTaken(location: Location, name: String, exceptId: String): Boolean =
        location.walls.any { it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) }

    private fun handleDeleteWall(location: Location, wall: Wall) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_wall)
            .setMessage(getString(R.string.delete_wall_confirm, wall.name))
            .setPositiveButton(R.string.delete) { _, _ -> LocationStore.removeWall(this, location.id, wall.id); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The Edit wall form's map section: what the wall map holds, and the button to its form (#8 phase 4). */
    private fun bindWallMap(view: View, wall: Wall) {
        view.findViewById<View>(R.id.wallMapSection).visibility = View.VISIBLE
        val summary = view.findViewById<TextView>(R.id.wallMapSummary)
        val button = view.findViewById<TextView>(R.id.btnWallMap)
        fun show() {
            val map = WallStore.readMap(this, wall.id)
            summary.text = map?.let { Labels.wallMap(this, it) } ?: getString(R.string.wall_map_not_set)
            button.setText(if (map == null) R.string.wall_map_set_up else R.string.wall_map_edit)
        }
        show()
        button.setOnClickListener { handleWallMap(wall) { show() } }
    }

    /**
     * Wall map form (#8 phase 4): the wall's size, sheet size and orientation, T-nut pitch, pattern and first-hole
     * offset, drawn to scale as the climber types. Save keeps the form open with a toast while the map is invalid.
     */
    private fun handleWallMap(wall: Wall, onSaved: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_wall_map, null)
        val existing = WallStore.readMap(this, wall.id)
        val width = view.findViewById<EditText>(R.id.wallWidth)
        val height = view.findViewById<EditText>(R.id.wallHeight)
        val sheet = view.findViewById<RadioGroup>(R.id.sheetSize)
        val orientation = view.findViewById<RadioGroup>(R.id.sheetOrientation)
        val pitch = view.findViewById<EditText>(R.id.tnutPitch)
        val pattern = view.findViewById<RadioGroup>(R.id.tnutPattern)
        val offsetU = view.findViewById<EditText>(R.id.offsetU)
        val offsetV = view.findViewById<EditText>(R.id.offsetV)
        val preview = view.findViewById<WallMapView>(R.id.wallMapPreview)
        val start = existing ?: WallMap.DEFAULT
        if (existing != null) {
            width.setText(Labels.metres(start.width))
            height.setText(Labels.metres(start.height))
        }
        sheet.check(if (maxOf(start.sheetWidth, start.sheetHeight) > WallMap.SHEET_JIS.second + 0.01f) R.id.sheet1220 else R.id.sheet910)
        orientation.check(if (start.sheetHeight >= start.sheetWidth) R.id.sheetStanding else R.id.sheetLying)
        pitch.setText(Labels.centimetres(start.pitch))
        pattern.check(if (start.pattern == TNutPattern.STAGGERED) R.id.patternStaggered else R.id.patternSquare)
        offsetU.setText(Labels.centimetres(start.offsetU))
        offsetV.setText(Labels.centimetres(start.offsetV))
        fun read(): WallMap? {
            val (short, long) = if (sheet.checkedRadioButtonId == R.id.sheet1220) WallMap.SHEET_4X8 else WallMap.SHEET_JIS
            val standing = orientation.checkedRadioButtonId == R.id.sheetStanding
            val map = WallMap(
                numberOf(width) ?: return null, numberOf(height) ?: return null,
                if (standing) short else long, if (standing) long else short,
                (numberOf(pitch) ?: return null) / 100, if (pattern.checkedRadioButtonId == R.id.patternStaggered) TNutPattern.STAGGERED else TNutPattern.SQUARE,
                (numberOf(offsetU) ?: return null) / 100, (numberOf(offsetV) ?: return null) / 100,
            )
            return map.takeIf { it.valid }
        }
        fun redraw() { preview.map = read() }
        listOf(width, height, pitch, offsetU, offsetV).forEach { it.onChange { redraw() } }
        listOf(sheet, orientation, pattern).forEach { it.setOnCheckedChangeListener { _, _ -> redraw() } }
        redraw()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wall_map)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val map = read()
            if (map == null) {
                Toast.makeText(this, R.string.wall_map_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            WallStore.writeMap(this, wall.id, map)
            onSaved()
            dialog.dismiss()
        }
    }

    /** The field's number ("4.2", "4,2"), null when blank or not one. */
    private fun numberOf(field: EditText): Float? = field.text.toString().trim().replace(',', '.').toFloatOrNull()

    private fun EditText.onChange(handle: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = handle()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })

    /**
     * Fills the set form's wall list with one checkbox per registered wall ([checked] ticked); hidden when the gym
     * has no walls. Returns a reader of the ticked ids (none = the whole gym).
     */
    private fun bindSetWalls(view: View, location: Location, checked: Collection<String>): () -> List<String> {
        if (location.walls.isEmpty()) return { emptyList() }
        val container = view.findViewById<ViewGroup>(R.id.setWalls).apply { visibility = View.VISIBLE }
        val boxes = location.walls.map { wall -> CheckBox(this).apply { text = wall.name; tag = wall.id; isChecked = wall.id in checked; container.addView(this) } }
        return { boxes.filter { it.isChecked }.map { it.tag as String } }
    }

    /**
     * Gym sets: single choice of the open sets covering [wallId] (newest first) plus "New set…", with the
     * capture's own set kept in the list even when retired; the last used set is preselected, else the newest
     * open one. An outdoor area has none. Cancel runs [onCancel] (New scan stops; Set location keeps the capture's set).
     */
    private fun pickSet(picked: Location, wallId: String, preselectedId: String?, onCancel: () -> Unit = {}, onPicked: (WallSet?) -> Unit) {
        // Read the location again: a wall registered one step earlier is not on the object the location picker handed over.
        val location = LocationStore.get(this, picked.id).takeIf { it.id.isNotEmpty() } ?: picked
        if (location.kind != LocationKind.GYM) { onPicked(null); return }
        val walls = listOf(wallId).filter { it.isNotEmpty() }
        val choices = WallSet.ordered(location.sets.filter { it.covers(wallId) && (it.active || it.id == preselectedId) })
        if (choices.isEmpty()) { handleNewSet(location, walls, onPicked); return }
        val labels = (choices.map { "${Labels.set(this, it)}\n${getString(R.string.set_since, Labels.isoDate(this, it.createdAt))}" } + getString(R.string.new_set)).toTypedArray()
        var index = choices.indexOfFirst { it.id == preselectedId }.takeIf { it >= 0 } ?: choices.indexOf(WallSet.current(choices))
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_set)
            .setSingleChoiceItems(labels, index) { _, which -> index = which }
            .setPositiveButton(R.string.next) { _, _ -> if (index < choices.size) onPicked(choices[index]) else handleNewSet(location, walls, onPicked) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    /**
     * Opens a set at the gym, named after the month ("October Set") unless renamed and covering [walls] (the wall
     * being scanned; untick all for the whole gym); "Without a set" goes on with none.
     */
    private fun handleNewSet(location: Location, walls: Collection<String>, onPicked: (WallSet?) -> Unit, draft: String? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_set, null)
        val name = view.findViewById<EditText>(R.id.setName)
        val chosenWalls = bindSetWalls(view, location, walls)
        view.findViewById<View>(R.id.setRetired).visibility = View.GONE
        name.setText(draft ?: Labels.defaultSetName(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.new_set)
            .setView(view)
            .setPositiveButton(R.string.next) { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty() || setNameTaken(location, text, exceptId = "")) {
                    if (text.isNotEmpty()) Toast.makeText(this, getString(R.string.set_name_taken, text), Toast.LENGTH_LONG).show()
                    handleNewSet(location, chosenWalls(), onPicked, text)
                    return@setPositiveButton
                }
                onPicked(LocationStore.addSet(this, location.id, text, chosenWalls()))
            }
            .setNegativeButton(R.string.without_set) { _, _ -> onPicked(null) }
            .show()
    }

    /** Among the open sets: a retired "October Set" does not block next year's. */
    private fun setNameTaken(location: Location, name: String, exceptId: String): Boolean =
        location.sets.any { it.active && it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) }

    /** Edit from a set header: rename, change the walls it covers, or retire it once the wall has been reset (reopen by unticking). */
    private fun handleEditSet(location: Location, set: WallSet, draft: String? = null, walls: Collection<String> = set.wallIds) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_set, null)
        val name = view.findViewById<EditText>(R.id.setName)
        val retired = view.findViewById<CheckBox>(R.id.setRetired)
        val chosenWalls = bindSetWalls(view, location, walls)
        name.setText(draft ?: set.name)
        retired.isChecked = !set.active
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_set)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty() || setNameTaken(location, text, exceptId = set.id)) {
                    if (text.isNotEmpty()) Toast.makeText(this, getString(R.string.set_name_taken, text), Toast.LENGTH_LONG).show()
                    handleEditSet(location, set, text, chosenWalls())
                    return@setPositiveButton
                }
                val retiredAt = if (retired.isChecked) set.retiredAt ?: CaptureStore.nowIso() else null
                LocationStore.updateSet(this, location.id, WallSet(set.id, text, set.createdAt, retiredAt, chosenWalls()))
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        val items = arrayOf(getString(R.string.set_location), getString(R.string.reprocess), getString(R.string.join_with), getString(R.string.share), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(Labels.captureDate(this, dir.name))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickLocation(CaptureStore.readLocationId(dir)) { location ->
                        val current = CaptureStore.readPlace(dir)
                        pickWall(location, current.wallId, onCancel = { CaptureStore.setLocation(dir, location.id, current.setId, current.wallId); refresh() }) { wall ->
                            val wallId = wall?.id ?: ""
                            pickSet(location, wallId, current.setId, onCancel = { CaptureStore.setLocation(dir, location.id, current.setId, wallId); refresh() }) { set ->
                                CaptureStore.setLocation(dir, location.id, set?.id ?: "", wallId)
                                refresh()
                            }
                        }
                    }
                    1 -> handleReprocess(dir)
                    2 -> handleJoin(dir)
                    3 -> CaptureStore.share(this, dir)
                    4 -> { CaptureStore.delete(dir); thumbnails.remove(dir.name); refresh() }
                }
            }
            .show()
    }

    /**
     * Join (#7): picks another capture of the same wall (same location, and wall when set), registers it onto this
     * one and builds a joined capture ([CaptureJoin]) that opens in the viewer; the originals stay.
     */
    private fun handleJoin(dir: File) {
        val place = CaptureStore.readPlace(dir)
        val others = captures.filter { other ->
            other != dir && CaptureStore.hasScene(other) && CaptureStore.readPlace(other).let { it.locationId == place.locationId && (place.wallId.isEmpty() || it.wallId.isEmpty() || it.wallId == place.wallId) }
        }
        if (others.isEmpty()) { Toast.makeText(this, R.string.join_none, Toast.LENGTH_LONG).show(); return }
        val labels = others.map { Labels.captureDate(this, it.name) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.join_choose)
            .setItems(labels) { _, which ->
                val other = others[which]
                val progress = AlertDialog.Builder(this).setMessage(R.string.joining).setCancelable(false).show()
                thread(name = "join") {
                    val outcome = runCatching { CaptureJoin.join(this, dir, other) }.getOrElse { CaptureJoin.Outcome(null, null, null) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        progress.dismiss()
                        refresh()
                        val joined = outcome.dir
                        if (joined == null) {
                            Toast.makeText(this, getString(R.string.join_failed, outcome.registration?.toString() ?: "no matching holds"), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, getString(R.string.join_done, outcome.summary ?: ""), Toast.LENGTH_LONG).show()
                            handleOpenCapture(joined)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Re-runs hold and route detection on a capture's saved point cloud (CaptureStore.reprocess) after a confirmation. */
    private fun handleReprocess(dir: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reprocess)
            .setMessage(R.string.reprocess_message)
            .setPositiveButton(R.string.reprocess) { _, _ ->
                val view = LayoutInflater.from(this).inflate(R.layout.view_processing, null)
                val panel = ProcessingPanel(view, Processing.REPROCESS)
                val progress = AlertDialog.Builder(this).setView(view).setCancelable(false).show()
                thread(name = "reprocess") {
                    val summary = runCatching { CaptureStore.reprocess(this, dir, panel::update) }.getOrNull()
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
            val where = CaptureStore.readPlace(dir)
            val location = locationOf(where.locationId)
            val set = location.set(where.setId)
            val joined = CaptureJoin.joinedFrom(dir)
            val place = Labels.join(
                c, Labels.location(c, location), location.wall(where.wallId)?.name ?: "", set?.let { Labels.set(c, it) } ?: "",
                Labels.definition(c, CaptureStore.readDefinition(dir).type),
                if (joined.isEmpty()) "" else getString(R.string.joined_from, joined.joinToString(" + ") { Labels.captureDate(c, it) }),
            )
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

    /** Wall header in the logbook: name ("No wall" for routes scanned without one), its open sets, route count, Edit for a real wall. */
    private fun bindWallHeader(view: View, location: Location, wall: Wall, routes: Int) {
        val c = this@MainActivity
        val real = wall.id.isNotEmpty()
        view.setPaddingRelative(0, view.paddingTop, view.paddingEnd, view.paddingBottom)
        view.findViewById<TextView>(R.id.header).apply { text = if (real) wall.name else getString(R.string.no_wall); textSize = HEADER_SP }
        // The open sets on this wall, newest first; nothing for No wall, whose routes are under their own set headers.
        view.findViewById<TextView>(R.id.headerDetail).text = if (!real) "" else Labels.join(c, *WallSet.ordered(location.sets.filter { it.active && it.covers(wall.id) }).map { it.name }.toTypedArray())
        view.findViewById<TextView>(R.id.headerCounts).apply { text = Labels.plural(c, R.plurals.routes_count, routes); visibility = View.VISIBLE }
        view.findViewById<View>(R.id.chevron).visibility = View.GONE
        view.findViewById<View>(R.id.btnEditLocation).apply {
            visibility = if (real) View.VISIBLE else View.INVISIBLE // keeps the count aligned with the other headers
            setOnClickListener { handleEditWall(location, wall) }
        }
    }

    /** "Main wall, Cave" or "whole gym" for a set at a gym with walls; "" at a gym without. */
    private fun setWallsLabel(location: Location, set: WallSet): String = when {
        location.walls.isEmpty() -> ""
        set.wallIds.isEmpty() -> getString(R.string.set_whole_gym)
        else -> Labels.join(this, *set.wallIds.mapNotNull { location.wall(it)?.name }.toTypedArray())
    }

    /** Set header in the logbook: name (marked when retired), dates and walls, route count, Edit for a real set; indented under a wall header. */
    private fun bindSetHeader(view: View, location: Location, set: WallSet, routes: Int) {
        val c = this@MainActivity
        val real = set.id.isNotEmpty()
        val indent = if (location.walls.isEmpty()) 0 else (16 * resources.displayMetrics.density).toInt()
        view.setPaddingRelative(indent, view.paddingTop, view.paddingEnd, view.paddingBottom)
        // Under a wall header the set is one level down: indented and a step smaller.
        view.findViewById<TextView>(R.id.header).apply { text = if (real) Labels.set(c, set) else getString(R.string.no_set); textSize = if (indent > 0) SUBHEADER_SP else HEADER_SP }
        view.findViewById<TextView>(R.id.headerDetail).text = if (!real) "" else Labels.join(
            c, getString(R.string.set_since, Labels.isoDate(c, set.createdAt)),
            set.retiredAt?.let { getString(R.string.set_retired_on, Labels.isoDate(c, it)) } ?: "",
            setWallsLabel(location, set),
        )
        view.findViewById<TextView>(R.id.headerCounts).apply { text = Labels.plural(c, R.plurals.routes_count, routes); visibility = View.VISIBLE }
        view.findViewById<View>(R.id.chevron).visibility = View.GONE
        view.findViewById<View>(R.id.btnEditLocation).apply {
            visibility = if (real) View.VISIBLE else View.INVISIBLE // keeps the count aligned with the other headers
            setOnClickListener { handleEditSet(location, set) }
        }
    }

    private inner class RouteAdapter(items: List<RouteRow>) : ArrayAdapter<RouteRow>(this, R.layout.item_capture, items) {
        override fun getViewTypeCount() = 3
        /** Wall and set headers share the header layout (and view type); every field is rebound. */
        override fun getItemViewType(position: Int) = getItem(position)!!.let { if (it.header != null) 0 else if (it.set != null || it.wall != null) 2 else 1 }
        override fun isEnabled(position: Int) = getItem(position)!!.route != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)!!
            val c = this@MainActivity
            if (row.wall != null) {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_header, parent, false)
                bindWallHeader(view, routeRows[0].header!!, row.wall, routeRows.drop(position + 1).takeWhile { it.wall == null }.count { it.route != null })
                return view
            }
            if (row.set != null) {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_header, parent, false)
                bindSetHeader(view, routeRows[0].header!!, row.set, routeRows.drop(position + 1).takeWhile { it.route != null }.size)
                return view
            }
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

/**
 * A height or reach input ([R.layout.field_length]): one cm field, or feet and inches fields, by [units]; [label] on
 * the first field, [help] (if any) below them.
 */
private class LengthField(root: View, private val units: UnitSystem, private val initialCm: Double?, label: String, help: String?) {
    private val metric = units == UnitSystem.METRIC
    private val cm: EditText = root.findViewById(R.id.lengthCm)
    private val feet: EditText = root.findViewById(R.id.lengthFeet)
    private val inches: EditText = root.findViewById(R.id.lengthInches)
    private val boxes = if (metric) listOf(cm) else listOf(feet, inches)
    private val first: TextInputLayout = root.findViewById(if (metric) R.id.lengthCmLayout else R.id.lengthFeetLayout)
    private val initialText: List<String>

    init {
        root.findViewById<View>(R.id.lengthCmLayout).visibility = if (metric) View.VISIBLE else View.GONE
        listOf(R.id.lengthFeetLayout, R.id.lengthInchesLayout).forEach { root.findViewById<View>(it).visibility = if (metric) View.GONE else View.VISIBLE }
        first.hint = label
        root.findViewById<TextView>(R.id.lengthHelp).apply { text = help; visibility = if (help == null) View.GONE else View.VISIBLE }
        if (initialCm != null) {
            if (metric) cm.setText(initialCm.roundToInt().toString())
            else Length.feetInches(initialCm).let { (f, i) -> feet.setText(f.toString()); inches.setText(i.toString()) }
        }
        initialText = text()
    }

    private fun text() = boxes.map { it.text.toString().trim() }

    private val blank get() = text().all { it.isEmpty() }

    /** Centimetres entered, null when blank or invalid; untouched boxes keep [initialCm] exactly (no rounding drift through the shown units). */
    fun value(): Double? = when {
        text() == initialText -> initialCm
        blank -> null
        metric -> Length.parseCm(cm.text.toString())
        else -> Length.parseFeetInches(feet.text.toString(), inches.text.toString())
    }

    /** Blank or a length in [Length.RANGE_CM]; otherwise shows [error] under the first field. */
    fun validate(error: String): Boolean {
        val ok = blank || value() != null
        first.error = if (ok) null else error
        return ok
    }
}
