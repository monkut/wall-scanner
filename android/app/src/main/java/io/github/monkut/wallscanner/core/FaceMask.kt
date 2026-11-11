package io.github.monkut.wallscanner.core

import kotlin.math.max
import kotlin.math.min

/** Whose face a box is: the person climbing (found by the pose model) or anyone else in the frame. */
enum class FaceRole(val id: String) {
    CLIMBER("climber"), BYSTANDER("bystander");

    companion object {
        fun fromId(id: String?): FaceRole = entries.firstOrNull { it.id == id } ?: BYSTANDER
    }
}

/** An axis-aligned box in normalised image coordinates (0..1 of the upright frame, y down). */
class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val cx: Float get() = x + w / 2
    val cy: Float get() = y + h / 2
    val area: Float get() = w * h

    /** Grown by [f] of its own size on every side. */
    fun expand(f: Float): Box = Box(x - w * f, y - h * f, w * (1 + 2 * f), h * (1 + 2 * f))

    /** Cut to the frame. */
    fun clamp(): Box {
        val l = x.coerceIn(0f, 1f); val t = y.coerceIn(0f, 1f)
        return Box(l, t, right.coerceIn(0f, 1f) - l, bottom.coerceIn(0f, 1f) - t)
    }

    fun iou(o: Box): Float {
        val iw = min(right, o.right) - max(x, o.x)
        val ih = min(bottom, o.bottom) - max(y, o.y)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        return inter / (area + o.area - inter)
    }

    fun lerp(o: Box, t: Float): Box = Box(x + (o.x - x) * t, y + (o.y - y) * t, w + (o.w - w) * t, h + (o.h - h) * t)
}

class FaceBox(val box: Box, val role: FaceRole)

/** The faces seen at [tMs] into a recording. */
class FaceFrame(val tMs: Long, val faces: List<FaceBox>)

/**
 * Face masks for climb video: which boxes to hide at a moment of a clip, from the faces sampled while it was
 * filmed (`FaceFrame`s about [io.github.monkut.wallscanner.climbs.ClimbRecordActivity]'s sampling interval
 * apart). The climber's face is the one on the pose model's head; every other face is a bystander's.
 */
object FaceMask {
    /** A face stays masked this long after it was last seen, so a missed sample does not flash it. */
    const val HOLD_MS = 500L
    /** Mask margin around a detected face, as a fraction of the box on every side (a moving head outruns its last box). */
    const val MARGIN = 0.25f
    /** Margin around the pose model's face landmarks (nose, eyes, ears, mouth), which cover only the middle of the face. */
    const val HEAD_MARGIN = 0.6f
    /** Detections from the whole frame and its tiles that overlap at least this much are one face. */
    const val MERGE_IOU = 0.3f

    /** Boxes found on the whole frame and on tiles merged: of two that overlap (IoU ≥ [MERGE_IOU]) the larger stays. */
    fun merge(boxes: List<Box>): List<Box> {
        val kept = ArrayList<Box>()
        for (b in boxes.sortedByDescending { it.area }) if (kept.none { it.iou(b) >= MERGE_IOU }) kept.add(b)
        return kept
    }

    /**
     * Roles for the faces found in a frame: the one overlapping the climber's [head] (from the pose) is the
     * climber's; the rest are bystanders'. A head without a face found on it (back turned, too far for the
     * face model) is masked as the climber's anyway.
     */
    fun assign(faces: List<Box>, head: Box?): List<FaceBox> {
        if (head == null) return faces.map { FaceBox(it, FaceRole.BYSTANDER) }
        val onHead = faces.filter { it.iou(head) > 0f || (it.cx in head.x..head.right && it.cy in head.y..head.bottom) }
        val climber = onHead.maxByOrNull { it.iou(head) } ?: head
        return listOf(FaceBox(climber, FaceRole.CLIMBER)) + faces.filter { it !== climber }.map { FaceBox(it, FaceRole.BYSTANDER) }
    }

    /**
     * The boxes to hide at [tMs]: the faces of the sample at or before it, moved towards their match in the
     * next sample when both are within [HOLD_MS]; faces only in the next sample come in early; a face is held
     * [HOLD_MS] after its last sample. Filtered by role, grown by [MARGIN] and cut to the frame.
     */
    fun boxesAt(frames: List<FaceFrame>, tMs: Long, climber: Boolean, bystanders: Boolean): List<FaceBox> {
        if (frames.isEmpty() || (!climber && !bystanders)) return emptyList()
        fun wanted(f: FaceBox) = if (f.role == FaceRole.CLIMBER) climber else bystanders
        val i = lastAtOrBefore(frames, tMs)
        val prev = frames.getOrNull(i)?.takeIf { tMs - it.tMs <= HOLD_MS }
        val next = frames.getOrNull(i + 1)?.takeIf { it.tMs - tMs <= HOLD_MS }
        val out = ArrayList<FaceBox>()
        val taken = HashSet<FaceBox>()
        if (prev != null) for (f in prev.faces.filter(::wanted)) {
            val match = next?.faces?.filter { it.role == f.role && it !in taken }?.maxByOrNull { it.box.iou(f.box) }?.takeIf { it.box.iou(f.box) > 0f }
            if (match == null) { out.add(f); continue }
            taken.add(match)
            val t = (tMs - prev.tMs).toFloat() / (next!!.tMs - prev.tMs).coerceAtLeast(1)
            out.add(FaceBox(f.box.lerp(match.box, t.coerceIn(0f, 1f)), f.role))
        }
        if (next != null) for (f in next.faces.filter(::wanted)) if (f !in taken) out.add(f)
        return out.map { FaceBox(it.box.expand(MARGIN).clamp(), it.role) }
    }

    /** Whether anything would be masked between [startMs] and [endMs] of a clip. */
    fun any(frames: List<FaceFrame>, startMs: Long, endMs: Long, climber: Boolean, bystanders: Boolean): Boolean =
        frames.any { it.tMs in (startMs - HOLD_MS)..(endMs + HOLD_MS) && it.faces.any { f -> if (f.role == FaceRole.CLIMBER) climber else bystanders } }

    /**
     * A box seen on the camera preview, as video-frame coordinates. The preview shows the frame scaled to fill
     * the view and cropped centred (PreviewView FILL_CENTER), so a preview narrower than the frame
     * ([previewAspect] < [frameAspect], width / height) sees a centred part of the frame's width, and a wider
     * one a centred part of its height.
     */
    fun previewToFrame(box: Box, previewAspect: Float, frameAspect: Float): Box {
        if (previewAspect <= 0f || frameAspect <= 0f || previewAspect == frameAspect) return box
        return if (previewAspect < frameAspect) {
            val fx = previewAspect / frameAspect // the fraction of the frame's width the preview shows
            Box((1 - fx) / 2 + box.x * fx, box.y, box.w * fx, box.h)
        } else {
            val fy = frameAspect / previewAspect
            Box(box.x, (1 - fy) / 2 + box.y * fy, box.w, box.h * fy)
        }
    }

    /** Index of the last frame at or before [tMs]; -1 before the first. */
    private fun lastAtOrBefore(frames: List<FaceFrame>, tMs: Long): Int {
        var lo = 0; var hi = frames.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].tMs <= tMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}
