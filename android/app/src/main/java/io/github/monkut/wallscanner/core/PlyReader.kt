package io.github.monkut.wallscanner.core

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the binary PLY layout produced by [PlyWriter] (float xyz + uchar rgb per vertex). */
object PlyReader {
    fun read(input: InputStream): VoxelCloud {
        val stream = DataInputStream(input.buffered())
        var count = -1
        while (true) {
            val line = readLine(stream) ?: throw IllegalArgumentException("PLY header truncated")
            if (line.startsWith("element vertex ")) count = line.substringAfterLast(' ').toInt()
            if (line == "end_header") break
        }
        require(count >= 0) { "PLY has no vertex element" }
        val bytes = ByteArray(count * PlyWriter.BYTES_PER_POINT)
        stream.readFully(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val xyz = FloatArray(count * 3)
        val rgb = ByteArray(count * 3)
        for (i in 0 until count) {
            xyz[i * 3] = buf.float; xyz[i * 3 + 1] = buf.float; xyz[i * 3 + 2] = buf.float
            rgb[i * 3] = buf.get(); rgb[i * 3 + 1] = buf.get(); rgb[i * 3 + 2] = buf.get()
        }
        return VoxelCloud(count, xyz, rgb, IntArray(count) { 1 })
    }

    private fun readLine(stream: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }
}
