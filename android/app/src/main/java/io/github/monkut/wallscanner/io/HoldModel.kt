package io.github.monkut.wallscanner.io

import android.content.res.AssetManager
import io.github.monkut.wallscanner.core.Clahe
import io.github.monkut.wallscanner.core.Hold
import io.github.monkut.wallscanner.core.Orthophoto
import io.github.monkut.wallscanner.core.SegDecoder
import io.github.monkut.wallscanner.core.WallSurface
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The trained hold detector (assets/holds.tflite, a YOLO11-seg export from tools/holds_model) run on the
 * orthophoto. Absent from the build until a model has been trained; [HoldMerge] combines its holds with the
 * others. The asset is memory-mapped (it is stored uncompressed). The input is the photo letterboxed into
 * the model's square, RGB 0..1, in whichever channel order the model declares; the two outputs are told
 * apart by rank (boxes 3-D, prototypes 4-D) and the prototypes' channel axis by where the 32 sits.
 */
class HoldModel private constructor(private val interpreter: Interpreter) {

    /** What the model found: holds, and the grade tape strips a two-class model also outlines (not holds). */
    class Found(val holds: List<Hold>, val tape: List<Hold>)

    /**
     * Every hold and tape strip the model finds, numbered from [firstId]. The photo goes through [Clahe] first,
     * as the training images did. One run at a time: the interpreter is not thread-safe.
     */
    @Synchronized
    fun find(photo: Orthophoto, surface: WallSurface, extent: FloatArray, firstId: Int): Found {
        val inShape = interpreter.getInputTensor(0).shape()
        val channelsFirst = inShape[1] == 3
        val size = if (channelsFirst) inShape[2] else inShape[1]
        val box = SegDecoder.Letterbox.fit(photo.image.width, photo.image.height, size)
        val input = ByteBuffer.allocateDirect(3 * size * size * 4).order(ByteOrder.nativeOrder())
        val plane = size * size
        val pixels = box.pixels(Clahe.equalize(photo.image))
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = (p shr 16 and 0xff) / 255f; val g = (p shr 8 and 0xff) / 255f; val b = (p and 0xff) / 255f
            if (channelsFirst) { input.putFloat(i * 4, r); input.putFloat((plane + i) * 4, g); input.putFloat((2 * plane + i) * 4, b) }
            else { input.putFloat(i * 12, r); input.putFloat(i * 12 + 4, g); input.putFloat(i * 12 + 8, b) }
        }
        val shapes = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).shape() }
        val boxesIndex = shapes.indexOfFirst { it.size == 3 }; val protoIndex = shapes.indexOfFirst { it.size == 4 }
        require(boxesIndex >= 0 && protoIndex >= 0) { "unexpected outputs ${shapes.map { it.toList() }}" }
        val boxShape = shapes[boxesIndex]; val protoShape = shapes[protoIndex]
        val protosChannelsFirst = protoShape[1] == SegDecoder.COEFFS
        val protoSize = if (protosChannelsFirst) protoShape[2] else protoShape[1]
        val boxes = ByteBuffer.allocateDirect(boxShape[1] * boxShape[2] * 4).order(ByteOrder.nativeOrder())
        val protos = ByteBuffer.allocateDirect(protoShape[1] * protoShape[2] * protoShape[3] * 4).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(boxesIndex to boxes, protoIndex to protos))
        val boxArr = FloatArray(boxShape[1] * boxShape[2]).also { boxes.rewind(); boxes.asFloatBuffer().get(it) }
        val protoArr = FloatArray(protoShape[1] * protoShape[2] * protoShape[3]).also { protos.rewind(); protos.asFloatBuffer().get(it) }
        val detections = SegDecoder.detections(boxArr, boxShape[1], boxShape[2], size)
        val (holdDets, tapeDets) = detections.partition { it.cls != SegDecoder.TAPE }
        val holds = SegDecoder.holds(holdDets, protoArr, protoSize, protosChannelsFirst, box, photo, surface, extent, firstId)
        val tape = SegDecoder.holds(tapeDets, protoArr, protoSize, protosChannelsFirst, box, photo, surface, extent, firstId + holds.size, plausibleOnly = false)
        return Found(holds, tape)
    }

    companion object {
        private const val ASSET = "holds.tflite"
        private const val THREADS = 4
        private var cached: HoldModel? = null
        private var tried = false

        /** Why the model is not available: the asset is missing from the build, or it failed to load; null when it loaded. */
        var loadError: String? = null
            private set

        /** The model, loaded once from [assets]; null when the build has none or it cannot be loaded (see [loadError]). */
        @Synchronized
        fun get(assets: AssetManager): HoldModel? {
            if (tried) return cached
            tried = true
            cached = runCatching {
                val buffer = assets.openFd(ASSET).use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
                HoldModel(Interpreter(buffer, Interpreter.Options().setNumThreads(THREADS)))
            }.onFailure { loadError = if (it is FileNotFoundException) "none in this build" else "failed to load: $it" }.getOrNull()
            return cached
        }
    }
}
