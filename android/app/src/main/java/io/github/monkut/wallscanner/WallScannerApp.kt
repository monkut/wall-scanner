package io.github.monkut.wallscanner

import android.app.Application
import android.content.Context
import io.github.monkut.wallscanner.io.CaptureStore
import io.github.monkut.wallscanner.io.ScanLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class WallScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/** Writes uncaught exceptions to files/crashes/ and into the active scan log, then defers to the system handler. */
object CrashLog {
    fun dir(context: Context): File = File(context.filesDir, "crashes").apply { mkdirs() }

    fun files(context: Context): List<File> = dir(context).listFiles()?.sortedBy { it.name } ?: emptyList()

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            runCatching { File(dir(context), "crash-${CaptureStore.timestamp()}.txt").writeText("thread=${thread.name}\n$trace") }
            runCatching { ScanLog.active?.log("CRASH thread=${thread.name}\n$trace") }
            previous?.uncaughtException(thread, error)
        }
    }
}
