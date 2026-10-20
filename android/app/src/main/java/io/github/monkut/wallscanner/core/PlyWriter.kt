package io.github.monkut.wallscanner.core

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary little-endian PLY with float xyz and uchar rgb per vertex (15 bytes per point). */
object PlyWriter {
    const val BYTES_PER_POINT = 15

    fun write(cloud: VoxelCloud, out: OutputStream) {
        val header = "ply\n" +
            "format binary_little_endian 1.0\n" +
            "element vertex ${cloud.count}\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\n" +
            "end_header\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        val buf = ByteBuffer.allocate(cloud.count * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until cloud.count) {
            buf.putFloat(cloud.xyz[i * 3]).putFloat(cloud.xyz[i * 3 + 1]).putFloat(cloud.xyz[i * 3 + 2])
            buf.put(cloud.rgb[i * 3]).put(cloud.rgb[i * 3 + 1]).put(cloud.rgb[i * 3 + 2])
        }
        out.write(buf.array())
        out.flush()
    }
}
