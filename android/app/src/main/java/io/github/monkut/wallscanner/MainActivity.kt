package io.github.monkut.wallscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.scan.ScanActivity
import io.github.monkut.wallscanner.viewer.ViewerActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private var captures: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.captureList)
        emptyText = findViewById(R.id.emptyText)
        findViewById<View>(R.id.btnNewScan).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(ViewerActivity.intent(this, captures[position].name))
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
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, captures.map { label(it) })
    }

    private fun label(dir: File): String = try {
        val scene = JSONObject(CaptureStore.readScene(dir))
        val holds = scene.getJSONArray("holds").length()
        val routes = scene.getJSONArray("routes").length()
        val sends = scene.optJSONArray("sends")?.length() ?: 0
        "${dir.name} · $holds holds · $routes routes · $sends sends"
    } catch (e: Exception) {
        dir.name
    }

    private fun handleCaptureMenu(dir: File) {
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setItems(arrayOf(getString(R.string.share), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> CaptureStore.share(this, dir)
                    1 -> { CaptureStore.delete(dir); refresh() }
                }
            }
            .show()
    }
}
