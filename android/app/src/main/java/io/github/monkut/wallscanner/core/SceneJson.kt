package io.github.monkut.wallscanner.core

import java.util.Locale

/** Writes scene.json (hand-rolled so the core stays free of Android/JSON dependencies). */
object SceneJson {
    const val VERSION = 3 // 3: routes[].confirmed; sends moved to sends.json
    /** Orthophoto of the wall next to scene.json; `wall.photo.bounds` maps it into the wall frame. */
    const val PHOTO_FILE = "wall.png"

    fun write(result: SceneResult, pointCount: Int, createdAt: String, device: String): String {
        val sb = StringBuilder(64 * 1024)
        val p = result.frame.plane
        val f = result.frame
        sb.append("{\n")
        sb.append("  \"version\": ").append(VERSION).append(",\n")
        sb.append("  \"createdAt\": ").append(str(createdAt)).append(",\n")
        sb.append("  \"device\": ").append(str(device)).append(",\n")
        sb.append("  \"definition\": {\"type\": ").append(str(result.definition.type.id))
            .append(", \"rules\": {\"feet\": ").append(str(result.definition.rules.feet.id))
            .append(", \"volumesOn\": ").append(result.definition.rules.volumesOn).append("}},\n")
        sb.append("  \"points\": {\"count\": ").append(pointCount)
            .append(", \"voxelSize\": ").append(num(Tuning.VOXEL_SIZE)).append("},\n")
        sb.append("  \"wall\": {")
            .append("\"origin\": ").append(vec(p.ox, p.oy, p.oz))
            .append(", \"normal\": ").append(vec(p.nx, p.ny, p.nz))
            .append(", \"up\": ").append(vec(f.ux, f.uy, f.uz))
            .append(", \"right\": ").append(vec(f.rx, f.ry, f.rz))
            .append(", \"inliers\": ").append(result.planeInliers)
        result.wallColor?.let { sb.append(", \"color\": [").append(it.r).append(", ").append(it.g).append(", ").append(it.b).append("]") }
        val e = result.extent
        sb.append(", \"extent\": [").append(num(e[0])).append(", ").append(num(e[1])).append(", ").append(num(e[2])).append(", ").append(num(e[3])).append("]")
        result.photo?.let { p ->
            sb.append(", \"photo\": {\"file\": ").append(str(PHOTO_FILE)).append(", \"bounds\": [").append(num(p.minU)).append(", ").append(num(p.minV))
                .append(", ").append(num(p.maxU)).append(", ").append(num(p.maxV)).append("], \"px\": ").append(num(p.px)).append("}")
        }
        sb.append("},\n")
        sb.append("  \"holds\": [\n")
        result.holds.forEachIndexed { i, h ->
            sb.append("    {\"id\": ").append(h.id)
                .append(", \"center\": ").append(vec(h.cx, h.cy, h.cz))
                .append(", \"uv\": [").append(num(h.u)).append(", ").append(num(h.v)).append("]")
                .append(", \"size\": ").append(vec(h.width, h.tall, h.height))
                .append(", \"color\": [").append(h.r).append(", ").append(h.g).append(", ").append(h.b).append("]")
                .append(", \"hsv\": ").append(vec(h.hsv[0], h.hsv[1], h.hsv[2]))
                .append(", \"voxels\": ").append(h.voxels)
                .append(", \"routeId\": ").append(h.routeId)
                .append(", \"routeIds\": ").append(h.routeIds.joinToString(", ", "[", "]"))
                .append(", \"role\": ").append(str(h.role.id))
                .append(", \"tape\": ").append(h.tape?.let { "[${it shr 16 and 0xff}, ${it shr 8 and 0xff}, ${it and 0xff}]" } ?: "null")
                .append(", \"outline\": ").append(outline(h.outline))
                .append("}").append(if (i < result.holds.size - 1) ",\n" else "\n")
        }
        sb.append("  ],\n")
        sb.append("  \"routes\": [\n")
        result.routes.forEachIndexed { i, r ->
            sb.append("    {\"id\": ").append(r.id)
                .append(", \"name\": ").append(str(r.name))
                .append(", \"color\": [").append(r.r).append(", ").append(r.g).append(", ").append(r.b).append("]")
                .append(", \"grade\": ").append(str(r.grade))
                .append(", \"gradeSystem\": ").append(r.gradeSystem?.let { str(it.id) } ?: "null")
                .append(", \"holdIds\": ").append(r.holdIds.joinToString(", ", "[", "]"))
                .append(", \"confirmed\": ").append(r.confirmed)
                .append("}").append(if (i < result.routes.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun num(v: Float): String = String.format(Locale.US, "%.4f", v)
    private fun outline(pts: FloatArray): String {
        val sb = StringBuilder("[")
        for (i in 0 until pts.size / 2) {
            if (i > 0) sb.append(", ")
            sb.append('[').append(String.format(Locale.US, "%.3f", pts[i * 2])).append(", ").append(String.format(Locale.US, "%.3f", pts[i * 2 + 1])).append(']')
        }
        return sb.append(']').toString()
    }
    private fun vec(x: Float, y: Float, z: Float): String = "[" + num(x) + ", " + num(y) + ", " + num(z) + "]"
    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> if (c < ' ') sb.append(String.format(Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
