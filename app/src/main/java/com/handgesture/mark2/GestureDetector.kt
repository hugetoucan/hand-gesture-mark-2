package com.handgesture.mark2

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class GestureDetector {

    companion object {
        // Landmark indices
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val MIDDLE_TIP = 12
        private const val RING_TIP = 16
        private const val PINKY_TIP = 20
        private const val WRIST = 0
        private const val INDEX_MCP = 5
        private const val MIDDLE_MCP = 9
        private const val RING_MCP = 13
        private const val PINKY_MCP = 17

        // Thresholds
        private const val PINCH_THRESHOLD = 0.05f
        private const val POINT_THRESHOLD = 0.1f
        private const val SWIPE_DISTANCE = 0.18f     // total displacement (normalized) that triggers a swipe
        private const val SWIPE_AXIS_RATIO = 1.3f    // dominant axis must exceed the other by this ratio
        private const val SWIPE_COOLDOWN_MS = 600L
        private const val HOLD_DURATION_MS = 1000L
        private const val MISC_GESTURE_COOLDOWN_MS = 500L
    }

    enum class Gesture {
        NONE,
        POINT,
        SWIPE_LEFT,
        SWIPE_RIGHT,
        SWIPE_UP,
        SWIPE_DOWN,
        PINCH_IN,
        PINCH_OUT,
        TWO_FINGERS
    }

    interface GestureListener {
        fun onGestureDetected(gesture: Gesture, x: Float, y: Float)
    }

    private var listener: GestureListener? = null

    // Tap state: fires ONCE per hold, re-arms only when the finger moves away
    private var lastPointPosition: Pair<Float, Float>? = null
    private var pointStartTime: Long = 0
    private var pointFired = false

    // Swipe state: accumulates tracked-point displacement, cooldown between swipes
    private var swipeStart: Pair<Float, Float>? = null
    private var swipeCooldownUntil: Long = 0

    // One-shot lock: after a gesture fires, the SAME gesture is suppressed until
    // the hand is fully removed (reset()) or a DIFFERENT gesture fires first.
    private var lastFiredGesture: Gesture? = null

    private var lastPinchDistance: Float = 0f
    private var lastMiscGestureAt: Long = 0

    fun setListener(listener: GestureListener) {
        this.listener = listener
    }

    fun detectGesture(landmarks: List<FloatArray>) {
        if (landmarks.size < 21) return
        val now = System.currentTimeMillis()

        // Activation gesture: index + middle extended (status-only for now)
        if (isTwoFingers(landmarks)) {
            if (now - lastMiscGestureAt >= MISC_GESTURE_COOLDOWN_MS) {
                lastMiscGestureAt = now
                fire(Gesture.TWO_FINGERS, 0f, 0f)
            }
            return
        }

        // Pinch detection (status-only for now)
        val pinchDistance = calculateDistance(
            landmarks[THUMB_TIP],
            landmarks[INDEX_TIP]
        )

        if (pinchDistance < PINCH_THRESHOLD) {
            val gesture = if (lastPinchDistance > 0 && pinchDistance < lastPinchDistance) {
                Gesture.PINCH_IN
            } else {
                Gesture.PINCH_OUT
            }

            val centerX = (landmarks[THUMB_TIP][0] + landmarks[INDEX_TIP][0]) / 2
            val centerY = (landmarks[THUMB_TIP][1] + landmarks[INDEX_TIP][1]) / 2

            if (now - lastMiscGestureAt >= MISC_GESTURE_COOLDOWN_MS) {
                lastMiscGestureAt = now
                fire(gesture, centerX, centerY)
            }
            lastPinchDistance = pinchDistance
            return
        }

        lastPinchDistance = pinchDistance

        if (isPointing(landmarks)) {
            // Pointing finger: tap (hold) + swipe track the index fingertip
            updateTap(landmarks[INDEX_TIP][0], landmarks[INDEX_TIP][1], now)
            updateSwipe(landmarks[INDEX_TIP][0], landmarks[INDEX_TIP][1], now)
        } else if (isOpenPalm(landmarks)) {
            // Open palm: whole-hand swipe - wave the hand and it's a swipe
            val (palmX, palmY) = palmCenter(landmarks)
            updateSwipe(palmX, palmY, now)
        } else {
            // Unrecognized hand shape: clear tracking state but keep the
            // one-shot gesture lock (only a full hand removal releases it)
            resetTracking()
        }
    }

    private fun updateTap(currentX: Float, currentY: Float, now: Long) {
        val lastPos = lastPointPosition
        if (lastPos != null) {
            val distance = calculateDistance(
                floatArrayOf(currentX, currentY, 0f),
                floatArrayOf(lastPos.first, lastPos.second, 0f)
            )

            if (distance < POINT_THRESHOLD) {
                if (!pointFired) {
                    val holdTime = now - pointStartTime

                    if (holdTime >= HOLD_DURATION_MS) {
                        pointFired = true
                        fire(Gesture.POINT, currentX, currentY)
                    }
                }
            } else {
                // Finger moved: restart the hold timer and re-arm the tap
                lastPointPosition = Pair(currentX, currentY)
                pointStartTime = now
                pointFired = false
            }
        } else {
            lastPointPosition = Pair(currentX, currentY)
            pointStartTime = now
            pointFired = false
        }
    }

    private fun updateSwipe(trackX: Float, trackY: Float, now: Long) {
        if (now < swipeCooldownUntil) return

        val start = swipeStart
        if (start == null) {
            swipeStart = Pair(trackX, trackY)
            return
        }

        val dx = trackX - start.first
        val dy = trackY - start.second
        val adx = abs(dx)
        val ady = abs(dy)

        if (max(adx, ady) < SWIPE_DISTANCE) return

        val gesture = when {
            adx > ady * SWIPE_AXIS_RATIO && dx > 0 -> Gesture.SWIPE_RIGHT
            adx > ady * SWIPE_AXIS_RATIO -> Gesture.SWIPE_LEFT
            dy > 0 -> Gesture.SWIPE_DOWN
            else -> Gesture.SWIPE_UP
        }
        fire(gesture, trackX, trackY)
        swipeStart = null
        swipeCooldownUntil = now + SWIPE_COOLDOWN_MS
    }

    /**
     * One-shot emission: the same gesture will not fire again until the hand is
     * fully removed (reset()) or a different gesture fires first.
     */
    private fun fire(gesture: Gesture, x: Float, y: Float) {
        if (gesture == lastFiredGesture) return
        lastFiredGesture = gesture
        listener?.onGestureDetected(gesture, x, y)
    }

    private fun isPointing(landmarks: List<FloatArray>): Boolean {
        // Index finger extended, others curled
        val indexExtended = landmarks[INDEX_TIP][1] < landmarks[INDEX_MCP][1]
        val middleCurled = landmarks[MIDDLE_TIP][1] > landmarks[INDEX_MCP][1]
        val ringCurled = landmarks[RING_TIP][1] > landmarks[INDEX_MCP][1]
        val pinkyCurled = landmarks[PINKY_TIP][1] > landmarks[INDEX_MCP][1]

        return indexExtended && middleCurled && ringCurled && pinkyCurled
    }

    private fun isOpenPalm(landmarks: List<FloatArray>): Boolean {
        // All four fingers extended above their knuckles
        return landmarks[INDEX_TIP][1] < landmarks[INDEX_MCP][1] &&
            landmarks[MIDDLE_TIP][1] < landmarks[MIDDLE_MCP][1] &&
            landmarks[RING_TIP][1] < landmarks[RING_MCP][1] &&
            landmarks[PINKY_TIP][1] < landmarks[PINKY_MCP][1]
    }

    private fun isTwoFingers(landmarks: List<FloatArray>): Boolean {
        // Index and middle fingers extended, others curled
        val indexExtended = landmarks[INDEX_TIP][1] < landmarks[INDEX_MCP][1]
        val middleExtended = landmarks[MIDDLE_TIP][1] < landmarks[INDEX_MCP][1]
        val ringCurled = landmarks[RING_TIP][1] > landmarks[INDEX_MCP][1]
        val pinkyCurled = landmarks[PINKY_TIP][1] > landmarks[INDEX_MCP][1]

        return indexExtended && middleExtended && ringCurled && pinkyCurled
    }

    /** Palm center: average of the wrist and the four finger knuckles */
    private fun palmCenter(landmarks: List<FloatArray>): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        val indices = intArrayOf(WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP)
        for (i in indices) {
            x += landmarks[i][0]
            y += landmarks[i][1]
        }
        return Pair(x / indices.size, y / indices.size)
    }

    private fun calculateDistance(point1: FloatArray, point2: FloatArray): Float {
        val dx = point1[0] - point2[0]
        val dy = point1[1] - point2[1]
        val dz = point1[2] - point2[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Clears tracking state but KEEPS the one-shot gesture lock */
    private fun resetTracking() {
        lastPointPosition = null
        pointStartTime = 0
        pointFired = false
        swipeStart = null
    }

    /** Full reset including the one-shot gesture lock. Called when the hand leaves the frame. */
    fun reset() {
        resetTracking()
        swipeCooldownUntil = 0
        lastPinchDistance = 0f
        lastMiscGestureAt = 0
        lastFiredGesture = null
    }
}
