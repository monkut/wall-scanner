package io.github.monkut.wallscanner

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.scan.ScanActivity
import io.github.monkut.wallscanner.viewer.ViewerActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private var captures: List<File> = emptyList()
    private val thumbnails = HashMap<String, Bitmap>()

    private companion object {
        const val PREF_DEFINITION = "definition"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.captureList)
        emptyText = findViewById(R.id.emptyText)
        findViewById<View>(R.id.btnNewScan).setOnClickListener { handleNewScan() }
        list.setOnItemClickListener { _, _, position, _ ->
            val dir = captures[position]
            if (CaptureStore.hasScene(dir)) {
                startActivity(ViewerActivity.intent(this, dir.name))
            } else {
                Toast.makeText(this, R.string.no_scene_hint, Toast.LENGTH_LONG).show()
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            handleCaptureMenu(captures[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        captures = CaptureStore.list(this)
        emptyText.visibility = if (captures.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = CaptureAdapter(captures)
        val missing = captures.filter { !thumbnails.containsKey(it.name) }
        if (missing.isEmpty()) return
        thread(name = "thumbnails") {
            for (dir in missing) {
                CaptureStore.ensureThumbnail(dir)
                val bitmap = CaptureStore.readThumbnail(dir) ?: continue
                runOnUiThread {
                    thumbnails[dir.name] = bitmap
                    (list.adapter as? CaptureAdapter)?.notifyDataSetChanged()
                }
            }
        }
    }

    /** Asks which route definition the gym uses, remembers it, then opens the scanner with that choice. */
    private fun handleNewScan() {
        val prefs = getPreferences(MODE_PRIVATE)
        val types = RouteDefinition.entries
        var chosen = RouteDefinition.fromId(prefs.getString(PREF_DEFINITION, null))
        val labels = types.map { "${it.label}\n${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_definition)
            .setSingleChoiceItems(labels, types.indexOf(chosen)) { _, which -> chosen = types[which] }
            .setPositiveButton(R.string.start_scan) { _, _ ->
                prefs.edit().putString(PREF_DEFINITION, chosen.id).apply()
                startActivity(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_DEFINITION, chosen.id))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun label(dir: File): String {
        val type = CaptureStore.readDefinition(dir).type.label
        if (!CaptureStore.hasScene(dir)) return "${dir.name} · $type\n${getString(R.string.incomplete_capture)}"
        return try {
            val scene = JSONObject(CaptureStore.readScene(dir))
            val holds = scene.getJSONArray("holds").length()
            val routes = scene.getJSONArray("routes").length()
            val sends = scene.optJSONArray("sends")?.length() ?: 0
            "${dir.name} · $type\n$holds holds · $routes routes · $sends sends"
        } catch (e: Exception) {
            dir.name
        }
    }

    private fun handleCaptureMenu(dir: File) {
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setItems(arrayOf(getString(R.string.share), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> CaptureStore.share(this, dir)
                    1 -> { CaptureStore.delete(dir); thumbnails.remove(dir.name); refresh() }
                }
            }
            .show()
    }

    private inner class CaptureAdapter(items: List<File>) : ArrayAdapter<File>(this, R.layout.item_capture, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_capture, parent, false)
            val dir = getItem(position)!!
            view.findViewById<TextView>(R.id.label).text = label(dir)
            view.findViewById<ImageView>(R.id.thumbnail).setImageBitmap(thumbnails[dir.name])
            return view
        }
    }
}
