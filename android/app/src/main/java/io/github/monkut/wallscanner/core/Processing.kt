package io.github.monkut.wallscanner.core

/**
 * The stages of processing a scan after Finish (and of Reprocess), in order, each weighted by the seconds it
 * took on a Pixel 9a for a 56–71 keyframe gym wall (issue #26). The weights only pace the progress bar.
 */
enum class ProcessingStage(val weight: Int) {
    /** Segments merged, keyframes resolved to world poses (Finish only). */
    MERGE(1),
    /** Plane fit, wall faces, depth holds and routes (SceneBuilder). */
    CLOUD(11),
    ORTHOPHOTO(3),
    SHAPES(5),
    /** Hold model, split and prune. */
    MODEL(3),
    /** SAM outlines, depth merge, tape marks. */
    OUTLINES(11),
    /** Scene, images and thumbnail written. */
    WRITE(1),
}

/** Progress of the pipeline: [ProcessingStage] has reached the fraction (0..1) of its own work. */
typealias ProgressListener = (ProcessingStage, Float) -> Unit

/** Overall progress over the [stages] that will run; stages before the reported one count as done. */
class Processing(private val stages: List<ProcessingStage>) {
    private val total = stages.sumOf { it.weight }.toFloat()

    /** 0..1 when [stage] is at [fraction] of its work; a stage not in [stages] counts as the point before the next one. */
    fun fraction(stage: ProcessingStage, fraction: Float): Float {
        if (total == 0f) return 0f
        val done = stages.filter { it < stage }.sumOf { it.weight }
        val own = if (stage in stages) stage.weight * fraction.coerceIn(0f, 1f) else 0f
        return ((done + own) / total).coerceIn(0f, 1f)
    }

    companion object {
        /** The stages Finish runs: all of them. */
        val FINISH: List<ProcessingStage> = ProcessingStage.entries

        /** The stages Reprocess runs: the pipeline on the saved cloud and keyframes, no merge. */
        val REPROCESS: List<ProcessingStage> = ProcessingStage.entries.filter { it != ProcessingStage.MERGE }
    }
}
