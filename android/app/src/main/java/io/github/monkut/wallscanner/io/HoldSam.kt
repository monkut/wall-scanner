package io.github.monkut.wallscanner.io

import android.content.res.AssetManager
import io.github.monkut.wallscanner.core.Hold
import io.github.monkut.wallscanner.core.HoldRole
import io.github.monkut.wallscanner.core.ImageHold
import io.github.monkut.wallscanner.core.Orthophoto
import io.github.monkut.wallscanner.core.Raster
import io.github.monkut.wallscanner.core.SamRefine
import io.github.monkut.wallscanner.core.WallFrame
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * MobileSAM (assets/sam_encoder.tflite and sam_decoder.tflite, from tools/holds_model/sam_export.py) redrawing
 * hold outlines on the orthophoto; [SamRefine] holds the steps around the two models. The decoder runs once per
 * hold, so [DECODERS] copies of it share the holds out on their own threads. Its two outputs are told apart by
 * rank (mask logits 4-D, predicted IoU 2-D), its inputs by rank too (embedding 4-D, box 2-D). CPU only: the GPU
 * delegate takes under 10% of either graph's operations and its masks came out empty.
 */
class HoldSam private constructor(private val encoder: Interpreter, private val decoders: List<Interpreter>) {

    /**
     * [holds] after SAM: [redrawn] outlines redrawn, [merged] pieces joined into the hold SAM drew around them,
     * [added] model holds the tighter outlines uncovered, [dropped] image-only holds without contrast to the wall
     * around them; [encodeMs] the encoder's share of the time.
     */
    class Refined(val holds: List<Hold>, val redrawn: Int, val merged: Int, val added: Int, val dropped: Int, val encodeMs: Long)

    /**
     * Redraws [holds]; then [candidates] (the hold model's masks) that the new outlines cover less than
     * [SamRefine.MAX_COVERED] join as holds, redrawn too: a loose outline from relief or colour hid them when
     * [io.github.monkut.wallscanner.core.HoldMerge.withModel] ran (the hold above the red volume on Oct 23 2:04 PM).
     * One run at a time: the interpreters are not thread-safe.
     */
    @Synchronized
    fun refine(holds: List<Hold>, photo: Orthophoto, frame: WallFrame, candidates: List<Hold> = emptyList()): Refined {
        val image = photo.image
        val scale = SamRefine.scale(image)
        val input = floats(SamRefine.input(image))
        val embShape = encoder.getOutputTensor(0).shape()
        val embedding = ByteBuffer.allocateDirect(embShape.fold(4) { a, b -> a * b }).order(ByteOrder.nativeOrder())
        val t0 = System.currentTimeMillis()
        encoder.run(input, embedding)
        val encodeMs = System.currentTimeMillis() - t0
        val old = holds.map { Raster.fillPolygon(ImageHold.polygon(it), photo) }
        val masks = decodeAll(holds, old, photo, embedding, scale)
        // Pieces join the hold SAM drew around them; a new outline that takes from other holds is refused (and its pieces stay).
        val into = SamRefine.mergeInto(masks.asList())
        val group = IntArray(holds.size) { if (into[it] >= 0) into[it] else it }
        val owner = ImageHold.owners(holds, photo)
        for (i in holds.indices) {
            val m = masks[i] ?: continue
            if (into[i] < 0 && SamRefine.takesFromOthers(m, old[i], owner, group, i)) {
                masks[i] = null
                for (j in holds.indices) if (into[j] == i) { into[j] = -1; group[j] = j }
            }
        }
        var redrawn = 0; var merged = 0
        val out = ArrayList<Pair<Hold, IntArray>>()
        for (i in holds.indices) {
            if (into[i] >= 0) { merged++; continue }
            val hold = holds[i]
            val m = masks[i]
            if (m == null) { out.add(hold to old[i]); continue }
            redrawn++
            val members = listOf(i) + holds.indices.filter { into[it] == i }
            val pixels = members.flatMap { masks[it]!!.asList() }.distinct().toIntArray()
            val role = members.map { holds[it].role }.firstOrNull { it != HoldRole.ANY } ?: hold.role
            val tape = members.firstNotNullOfOrNull { holds[it].tape }
            out.add(ImageHold.fromPixels(hold.id, pixels, members.sumOf { holds[it].voxels }, photo, frame).also { it.role = role; it.tape = tape } to pixels)
        }
        val covered = BooleanArray(image.width * image.height)
        for ((_, px) in out) for (i in px) covered[i] = true
        val ids = holds.map { it.id }.toSet()
        val late = candidates.filter { it.id !in ids }.map { it to Raster.fillPolygon(ImageHold.polygon(it), photo) }
            .filter { (_, px) -> px.isNotEmpty() && px.count { covered[it] } < SamRefine.MAX_COVERED * px.size }
        val lateMasks = decodeAll(late.map { it.first }, late.map { it.second }, photo, embedding, scale)
        for ((k, pair) in late.withIndex()) {
            val (hold, px) = pair
            val m = lateMasks[k]?.takeIf { mask -> mask.count { covered[it] } < SamRefine.MAX_COVERED * mask.size }
            val pixels = m ?: px
            out.add((if (m != null) ImageHold.fromPixels(hold.id, m, 0, photo, frame) else hold) to pixels)
            for (i in pixels) covered[i] = true
        }
        // Image-only holds that do not stand out from the wall around them are bolt holes, grain or an exposure seam.
        val kept = out.filter { (hold, px) -> hold.voxels > 0 || SamRefine.contrast(px, image, covered) >= SamRefine.MIN_CONTRAST }
        return Refined(kept.map { it.first }, redrawn, merged, late.size, out.size - kept.size, encodeMs)
    }

    /** SAM's accepted mask for each of [holds] (null when refused or without an outline), the decoders sharing the holds out. */
    private fun decodeAll(holds: List<Hold>, old: List<IntArray>, photo: Orthophoto, embedding: ByteBuffer, scale: Float): Array<IntArray?> {
        val image = photo.image
        val masks = arrayOfNulls<IntArray>(holds.size)
        if (holds.isEmpty()) return masks
        val next = AtomicInteger()
        val pool = Executors.newFixedThreadPool(decoders.size)
        try {
            decoders.map { decoder ->
                pool.submit {
                    val run = DecoderRun(decoder, embedding.duplicate().order(ByteOrder.nativeOrder()))
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= holds.size) break
                        if (holds[index].outline.size < 6) continue // no outline (older scenes): no box to prompt with
                        val box = SamRefine.box(holds[index], photo)
                        val (logits, score) = run.decode(FloatArray(4) { box[it] * scale })
                        masks[index] = SamRefine.accept(SamRefine.maskPixels(logits, box, scale, image.width, image.height), old[index], score, image.width)
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return masks
    }

    /** One decoder with its own buffers; [embedding] is this thread's view of the shared embedding. */
    private class DecoderRun(private val decoder: Interpreter, private val embedding: ByteBuffer) {
        private val inShapes = (0 until decoder.inputTensorCount).map { decoder.getInputTensor(it).shape() }
        private val embIndex = inShapes.indexOfFirst { it.size == 4 }
        private val boxIndex = inShapes.indexOfFirst { it.size == 2 }
        private val outShapes = (0 until decoder.outputTensorCount).map { decoder.getOutputTensor(it).shape() }
        private val maskIndex = outShapes.indexOfFirst { it.size == 4 }
        private val scoreIndex = outShapes.indexOfFirst { it.size == 2 }
        private val logits = ByteBuffer.allocateDirect(SamRefine.MASK * SamRefine.MASK * 4).order(ByteOrder.nativeOrder())
        private val score = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        private val logitArr = FloatArray(SamRefine.MASK * SamRefine.MASK)

        init {
            require(embIndex >= 0 && boxIndex >= 0 && maskIndex >= 0 && scoreIndex >= 0) { "unexpected decoder tensors ${inShapes.map { it.toList() }} → ${outShapes.map { it.toList() }}" }
        }

        /** Mask logits (`MASK × MASK`, reused between calls) and predicted IoU for one box prompt in input pixels. */
        fun decode(prompt: FloatArray): Pair<FloatArray, Float> {
            val inputs = arrayOfNulls<Any>(2).also { embedding.rewind(); it[embIndex] = embedding; it[boxIndex] = floats(prompt) }
            logits.rewind(); score.rewind()
            decoder.runForMultipleInputsOutputs(inputs, mapOf(maskIndex to logits, scoreIndex to score))
            logits.rewind(); logits.asFloatBuffer().get(logitArr)
            return logitArr to score.getFloat(0)
        }
    }

    companion object {
        private const val ENCODER = "sam_encoder.tflite"
        private const val DECODER = "sam_decoder.tflite"
        private const val THREADS = 4
        private const val DECODERS = 3
        private const val DECODER_THREADS = 2
        private var cached: HoldSam? = null
        private var tried = false

        /** Why SAM is not available: the assets are missing from the build, or failed to load; null when it loaded. */
        var loadError: String? = null
            private set

        /** The models, loaded once from [assets]; null when the build has none or they cannot be loaded (see [loadError]). */
        @Synchronized
        fun get(assets: AssetManager): HoldSam? {
            if (tried) return cached
            tried = true
            cached = runCatching { HoldSam(load(assets, ENCODER, THREADS), List(DECODERS) { load(assets, DECODER, DECODER_THREADS) }) }
                .onFailure { loadError = if (it is FileNotFoundException) "none in this build" else "failed to load: $it" }
                .getOrNull()
            return cached
        }

        private fun load(assets: AssetManager, name: String, threads: Int): Interpreter {
            val buffer = assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            return Interpreter(buffer, Interpreter.Options().setNumThreads(threads))
        }

        private fun floats(values: FloatArray): ByteBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).also { it.asFloatBuffer().put(values); it.rewind() }
    }
}
