package io.github.monkut.wallscanner.io

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Append-only diagnostics log stored next to a capture (scan.log). Lines are JST-timestamped and flushed immediately. */
class ScanLog(file: File) {
    private val writer = PrintWriter(FileWriter(file, true), true)

    @Synchronized
    fun log(line: String) {
        writer.println("${TIME.format(ZonedDateTime.now(JST))} $line")
    }

    @Synchronized
    fun close() = writer.close()

    companion object {
        const val FILE = "scan.log"
        private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        /** The log of the scan in progress, so a crash handler can record into it. */
        @Volatile
        var active: ScanLog? = null
    }
}
