package com.handgesture.mark2

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandTracker(private val context: Context) {

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false
    private var lastTimestampMs = -1L

    companion object {
        private const val TAG = "HandTracker"
        private const val MODEL_FILENAME = "hand_landmarker.task"
    }

    interface HandTrackingListener {
        fun onHandDetected(landmarks: List<FloatArray>)
        fun onNoHandDetected()
        fun onError(error: String)
    }

    private var listener: HandTrackingListener? = null

    fun setListener(listener: HandTrackingListener) {
        this.listener = listener
    }

    fun initialize(): Boolean {
        try {
            handLandmarker = try {
                // Prefer GPU delegate (much faster); fall back to CPU where unavailable
                val gpuOptions = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_FILENAME)
                            .setDelegate(Delegate.GPU)
                            .build()
                    )
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()
                HandLandmarker.createFromOptions(context, gpuOptions)
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
                val cpuOptions = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_FILENAME)
                            .build()
                    )
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()
                HandLandmarker.createFromOptions(context, cpuOptions)
            }

            isInitialized = true
            Log.d(TAG, "HandTracker initialized successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HandTracker", e)
            listener?.onError("Failed to initialize: ${e.message}")
            return false
        }
    }

    fun processFrame(bitmap: Bitmap, timestampMs: Long) {
        if (!isInitialized || handLandmarker == null) {
            listener?.onError("HandTracker not initialized")
            return
        }

        try {
            // VIDEO mode requires strictly increasing timestamps
            val ts = if (timestampMs > lastTimestampMs) timestampMs else lastTimestampMs + 1
            lastTimestampMs = ts

            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: HandLandmarkerResult? = handLandmarker?.detect(mpImage, ts)

            if (result != null && result.landmarks().isNotEmpty()) {
                val landmarks = mutableListOf<FloatArray>()
                val handLandmarks = result.landmarks()[0]

                for (landmark in handLandmarks) {
                    landmarks.add(
                        floatArrayOf(
                            landmark.x(),
                            landmark.y(),
                            landmark.z()
                        )
                    )
                }

                listener?.onHandDetected(landmarks)
            } else {
                listener?.onNoHandDetected()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            listener?.onError("Processing error: ${e.message}")
        }
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        isInitialized = false
    }
}
