package io.github.monkut.wallscanner.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.io.CaptureStore
import java.io.File

/** Shows a capture in the bundled three.js viewer (assets/viewer) and persists route edits made there. */
class ViewerActivity : AppCompatActivity() {
    private lateinit var captureDir: File

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val captureId = intent.getStringExtra(EXTRA_CAPTURE)
        if (captureId == null) { finish(); return }
        captureDir = File(CaptureStore.capturesDir(this), captureId)
        setContentView(R.layout.activity_viewer)

        val webView = findViewById<WebView>(R.id.webView)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/files/", WebViewAssetLoader.InternalStoragePathHandler(this, filesDir))
            .build()
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        webView.addJavascriptInterface(Bridge(), "WallBridge")
        webView.loadUrl("$BASE_URL/assets/viewer/index.html?capture=" + Uri.encode(captureId))
    }

    inner class Bridge {
        @JavascriptInterface
        fun saveScene(json: String) {
            CaptureStore.writeScene(captureDir, json)
            runOnUiThread {
                Toast.makeText(this@ViewerActivity, R.string.scene_saved, Toast.LENGTH_SHORT).show()
                finish() // back to the capture list / New scan screen
            }
        }

        @JavascriptInterface
        fun share() {
            runOnUiThread { CaptureStore.share(this@ViewerActivity, captureDir) }
        }
    }

    companion object {
        private const val EXTRA_CAPTURE = "capture"
        private const val BASE_URL = "https://" + WebViewAssetLoader.DEFAULT_DOMAIN

        fun intent(context: Context, captureId: String): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_CAPTURE, captureId)
    }
}
