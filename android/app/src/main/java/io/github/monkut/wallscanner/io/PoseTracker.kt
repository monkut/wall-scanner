package io.github.monkut.wallscanner.io

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.github.monkut.wallscanner.core.PoseSample
import kotlin.math.hypot

/**
 * MediaPipe Pose Landmarker (assets/pose_landmarker_lite.task, BlazePose lite, 33 landmarks) on camera frames,
 * reduced to what [io.github.monkut.wallscanner.core.AttemptDetector] needs. Frames must be upright (rotated by
 * the camera's rotation), so image y grows downward on the wall. One climber: the first pose found.
 */
class PoseTracker(context: Context) : AutoCloseable {
    private val landmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build(),
    )

    /** The pose in [frame] at [tMs] (time into the recording), or a sample with nothing seen. */
    fun sample(frame: Bitmap, tMs: Long, frameTimeMs: Long): PoseSample =
        toSample(landmarker.detectForVideo(BitmapImageBuilder(frame).build(), frameTimeMs), tMs)

    override fun close() = landmarker.close()

    companion object {
        const val MODEL = "pose_landmarker_lite.task"
        private const val MIN_VISIBILITY = 0.5f
        // BlazePose landmark indices.
        private const val L_WRIST = 15; private const val R_WRIST = 16
        private const val L_HIP = 23; private const val R_HIP = 24
        private const val L_ANKLE = 27; private const val R_ANKLE = 28
        private const val L_HEEL = 29; private const val R_HEEL = 30
        private const val L_TOE = 31; private const val R_TOE = 32

        fun toSample(result: PoseLandmarkerResult, tMs: Long): PoseSample {
            val pose = result.landmarks().firstOrNull() ?: return PoseSample(tMs, null, null, null, null)
            fun y(i: Int): Float? = pose[i].takeIf { (it.visibility().orElse(1f)) >= MIN_VISIBILITY }?.y()
            // Each foot's lowest visible point; "both feet off" needs the lower of the two.
            val left = listOfNotNull(y(L_HEEL), y(L_TOE), y(L_ANKLE)).maxOrNull()
            val right = listOfNotNull(y(R_HEEL), y(R_TOE), y(R_ANKLE)).maxOrNull()
            val foot = listOfNotNull(left, right).maxOrNull()
            val hips = listOfNotNull(y(L_HIP), y(R_HIP))
            val hip = if (hips.isEmpty()) null else hips.average().toFloat()
            val wrists = listOfNotNull(y(L_WRIST), y(R_WRIST))
            val wrist = if (wrists.size == 2) wrists.max() else null // both hands up means the lower one is up
            val legs = listOf(L_HIP to L_ANKLE, R_HIP to R_ANKLE).mapNotNull { (h, a) ->
                if (y(h) == null || y(a) == null) null else hypot(pose[h].x() - pose[a].x(), pose[h].y() - pose[a].y())
            }
            val leg = if (legs.isEmpty()) null else legs.max()
            return PoseSample(tMs, foot, hip, wrist, leg)
        }
    }
}
