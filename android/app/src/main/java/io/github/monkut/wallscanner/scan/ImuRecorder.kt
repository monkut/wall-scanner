package io.github.monkut.wallscanner.scan

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import io.github.monkut.wallscanner.core.GyroBuffer
import io.github.monkut.wallscanner.io.ScanLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Raw gyro and accelerometer while the scan screen is open (#59): every sample goes to [file] (gzip CSV
 * `t_ns,sensor,x,y,z`, sensor `g` rad/s or `a` m/s², device axes, the sensor clock that camera image timestamps also
 * use) and the gyro also to [gyro], from which keyframes get their motion-blur estimate. Each [start]…[stop] appends
 * one gzip member; readers that accept concatenated members (Python's gzip) see one CSV.
 */
class ImuRecorder(context: Context, private val file: File, private val log: ScanLog) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val gyro = GyroBuffer()
    private var thread: HandlerThread? = null
    private var out: BufferedWriter? = null
    private var samples = 0L

    fun start() {
        if (thread != null) return
        if (gyroSensor == null) log.log("imu: no gyroscope; keyframes are taken without a blur estimate")
        val header = !file.exists()
        val writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(file, true)), Charsets.US_ASCII))
        if (header) writer.write("t_ns,sensor,x,y,z\n")
        out = writer
        val t = HandlerThread("imu").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        for (s in listOfNotNull(gyroSensor, accelSensor)) manager.registerListener(this, s, PERIOD_US, handler)
        log.log("imu: recording ${listOfNotNull(gyroSensor, accelSensor).joinToString { "${it.name} (${it.vendor})" }} every ${PERIOD_US} µs to ${file.name}")
    }

    fun stop() {
        val t = thread ?: return
        manager.unregisterListener(this)
        // Close on the sensor thread, after any event already queued there.
        Handler(t.looper).post {
            runCatching { out?.close() }.onFailure { log.log("imu: close failed: $it") }
            out = null
            t.quitSafely()
        }
        thread = null
        log.log("imu: stopped, $samples samples so far")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val kind = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) 'g' else 'a'
        if (kind == 'g') gyro.add(event.timestamp, v[0], v[1], v[2])
        val w = out ?: return
        try {
            w.write(String.format(Locale.US, "%d,%c,%.5f,%.5f,%.5f\n", event.timestamp, kind, v[0], v[1], v[2]))
            samples++
        } catch (e: Exception) {
            log.log("imu: write failed, logging stopped: $e")
            out = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        /** 200 Hz: the fastest rate without the HIGH_SAMPLING_RATE_SENSORS permission. */
        const val PERIOD_US = 5_000
    }
}
