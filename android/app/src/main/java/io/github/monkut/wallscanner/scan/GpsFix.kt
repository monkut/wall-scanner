package io.github.monkut.wallscanner.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.GeoPoint

/**
 * One-shot position fix for a scan: the best of the last known position (if fresh) and a current fix from
 * each enabled provider. Network (wifi / cell) usually answers first and is enough to place a gym; GPS
 * refines it outdoors. [onFix] runs on the main thread each time a more accurate position arrives;
 * [onUnavailable] once if no provider is enabled or nothing answered within the timeout.
 */
class GpsFix(
    private val activity: Activity,
    private val log: (String) -> Unit,
    private val onUnavailable: () -> Unit = {},
    private val onFix: (GeoPoint) -> Unit,
) {
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val cancel = CancellationSignal()
    private val handler = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var started = false

    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission() = ActivityCompat.requestPermissions(activity, PERMISSIONS, PERMISSION_CODE)

    @SuppressLint("MissingPermission") // callers check hasPermission()
    fun start() {
        if (started) return
        started = true
        val providers = PROVIDERS.filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        if (providers.isEmpty()) { log("gps: no location provider enabled"); onUnavailable(); return }
        log("gps: requesting fix from ${providers.joinToString()}")
        for (p in providers) {
            manager.getLastKnownLocation(p)?.let { if (ageSeconds(it) <= MAX_LAST_KNOWN_AGE_S) offer(it, "$p last known") }
            LocationManagerCompat.getCurrentLocation(manager, p, cancel, ContextCompat.getMainExecutor(activity)) { loc ->
                if (loc == null) log("gps: $p gave no fix") else offer(loc, p)
            }
        }
        handler.postDelayed({ handleTimeout() }, TIMEOUT_MS)
    }

    private fun handleTimeout() {
        if (best == null) { log("gps: no fix within ${TIMEOUT_MS / 1000} s"); onUnavailable() }
        stop()
    }

    fun stop() {
        if (!cancel.isCanceled) cancel.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    private fun offer(loc: Location, source: String) {
        val current = best
        if (current != null && loc.accuracy >= current.accuracy) return
        best = loc
        val point = GeoPoint(loc.latitude, loc.longitude, loc.accuracy, CaptureStore.nowIso())
        log("gps: $point from $source, ${ageSeconds(loc)} s old")
        onFix(point)
    }

    private fun ageSeconds(loc: Location): Long = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000

    companion object {
        const val PERMISSION_CODE = 2
        private val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        private val PROVIDERS = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        private const val MAX_LAST_KNOWN_AGE_S = 120L
        private const val TIMEOUT_MS = 60_000L
    }
}
