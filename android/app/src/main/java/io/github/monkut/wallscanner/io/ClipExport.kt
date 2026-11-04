package io.github.monkut.wallscanner.io

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * A clip's trimmed range copied into a file of its own without re-encoding: every track's samples from the sync
 * frame at or before the in-point up to the out-point, timestamps shifted to start at zero, the rotation hint
 * kept (most climbing clips are portrait). Starting on a sync frame can add up to one keyframe interval (~1 s)
 * before the in-point; that is the price of not re-encoding.
 */
object ClipExport {
    private const val BUFFER = 2 * 1024 * 1024

    fun trimmedCopy(source: File, startMs: Long, endMs: Long, target: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.path)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val rotation = metadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            muxer.setOrientationHint(rotation)
            val tracks = (0 until extractor.trackCount).associateWith { i -> muxer.addTrack(extractor.getTrackFormat(i)) }
            muxer.start()
            val buffer = ByteBuffer.allocate(BUFFER)
            val info = android.media.MediaCodec.BufferInfo()
            val startUs = startMs * 1000; val endUs = endMs * 1000
            // One time base for every track: the video's sync frame at or before the in-point (audio frames are all sync).
            val video = tracks.keys.firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val baseUs = video?.let { v ->
                extractor.selectTrack(v)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                extractor.sampleTime.also { extractor.unselectTrack(v) }
            }?.takeIf { it >= 0 } ?: startUs
            for ((track, out) in tracks) {
                extractor.selectTrack(track)
                extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > endUs) break
                    if (extractor.sampleTime >= baseUs) {
                        info.set(0, size, extractor.sampleTime - baseUs, extractor.sampleFlags)
                        muxer.writeSampleData(out, buffer, info)
                    }
                    extractor.advance()
                }
                extractor.unselectTrack(track)
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractor.release()
        }
    }

    /** Width and height of a video file as displayed (its rotation applied); 0 × 0 when unreadable. */
    fun frameSize(file: File): Pair<Int, Int> = runCatching {
        val w = metadata(file, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = metadata(file, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = metadata(file, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation % 180 == 0) w to h else h to w
    }.getOrDefault(0 to 0)

    /** Duration of a video file in ms (0 when unreadable). */
    fun durationMs(file: File): Long = runCatching { metadata(file, MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }.getOrNull() ?: 0L

    // MediaMetadataRetriever is AutoCloseable only from API 29; the app runs from 28.
    private fun metadata(file: File, key: Int): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            return retriever.extractMetadata(key)
        } finally {
            retriever.release()
        }
    }
}
