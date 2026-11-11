package io.github.monkut.wallscanner

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import io.github.monkut.wallscanner.core.Processing
import io.github.monkut.wallscanner.core.ProcessingStage

/**
 * The processing panel (`view_processing.xml`): a determinate bar paced by [Processing] over the [stages] that
 * will run, and the current stage's name. [update] may be called from any thread.
 */
class ProcessingPanel(private val root: View, stages: List<ProcessingStage>) {
    private val processing = Processing(stages)
    private val bar = root.findViewById<ProgressBar>(R.id.processingBar)
    private val stageText = root.findViewById<TextView>(R.id.processingStage)

    fun update(stage: ProcessingStage, fraction: Float) {
        val percent = (processing.fraction(stage, fraction) * 100).toInt()
        root.post {
            bar.progress = percent
            stageText.text = root.context.getString(R.string.processing_stage, root.context.getString(label(stage)), percent)
        }
    }

    private fun label(stage: ProcessingStage) = when (stage) {
        ProcessingStage.MERGE -> R.string.stage_merge
        ProcessingStage.CLOUD -> R.string.stage_cloud
        ProcessingStage.ORTHOPHOTO -> R.string.stage_orthophoto
        ProcessingStage.SHAPES -> R.string.stage_shapes
        ProcessingStage.MODEL -> R.string.stage_model
        ProcessingStage.OUTLINES -> R.string.stage_outlines
        ProcessingStage.WRITE -> R.string.stage_write
    }
}
