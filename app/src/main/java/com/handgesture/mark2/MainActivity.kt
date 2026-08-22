package com.handgesture.mark2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity(),
    HandTracker.HandTrackingListener,
    GestureDetector.GestureListener {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var instructionsText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handTracker: HandTracker
    private lateinit var gestureDetector: GestureDetector

    private var camera: Camera? = null
    private var isTracking = false

    // Fallback screen size (used only if the preview view is not laid out yet)
    private var screenWidth = 0
    private var screenHeight = 0

    // Dimensions of the analysis frame as processed (rotated/mirrored)
    private var frameWidth = 0
    private var frameHeight = 0

    // UI update throttling for per-frame hand status
    private var handVisible = false
    private var lastHandStatusUpdate = 0L

    // Timestamp of when the hand left the frame (used to release gesture locks)
    private var noHandSince = 0L

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA = 100
        private const val REQUEST_OVERLAY = 101
        private const val REQUEST_ACCESSIBILITY = 102

        // Preview and analysis share the same resolution so the visible preview
        // content matches exactly what MediaPipe sees.
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480

        // Screen-space swipe distance in pixels
        private const val SWIPE_PIXELS = 250f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get screen dimensions (fallback for coordinate mapping)
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        // Initialize views
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        instructionsText = findViewById(R.id.instructionsText)

        // Initialize components
        cameraExecutor = Executors.newSingleThreadExecutor()
        handTracker = HandTracker(this)
        gestureDetector = GestureDetector()

        // Set listeners
        handTracker.setListener(this)
        gestureDetector.setListener(this)

        // Set up button click listener
        toggleButton.setOnClickListener {
            if (isTracking) {
                stopTracking()
            } else {
                startTracking()
            }
        }

        // Check and request permissions
        checkPermissions()
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA
                )
            }
            !Settings.canDrawOverlays(this) -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            }
            !isAccessibilityServiceEnabled() -> {
                showAccessibilityDialog()
            }
            else -> {
                initializeHandTracker()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${GestureAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }

    private fun showAccessibilityDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Accessibility Permission Required")
            .setMessage("Please enable the Hand Gesture accessibility service to allow gesture control.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, REQUEST_ACCESSIBILITY)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initializeHandTracker() {
        if (handTracker.initialize()) {
            startCamera()
            updateStatus("Ready - Press Start to begin tracking")
        } else {
            updateStatus("Failed to initialize hand tracker")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview uses the SAME resolution as analysis so landmark
            // coordinates map 1:1 onto the visible preview content.
            val preview = Preview.Builder()
                .setTargetResolution(Size(CAMERA_WIDTH, CAMERA_HEIGHT))
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Image analysis for hand tracking
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(CAMERA_WIDTH, CAMERA_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Select front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                Log.d(TAG, "Camera started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Camera error", e)
                updateStatus("Camera error: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(imageProxy: ImageProxy) {
        if (!isTracking) {
            imageProxy.close()
            return
        }

        try {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                // Convert to bitmap
                val bitmap = imageProxy.toBitmap()

                // Rotate bitmap for front camera
                val matrix = Matrix()
                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                matrix.postScale(-1f, 1f) // Mirror for front camera

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )

                frameWidth = rotatedBitmap.width
                frameHeight = rotatedBitmap.height

                // Process with hand tracker (VIDEO mode needs monotonic timestamps)
                handTracker.processFrame(rotatedBitmap, SystemClock.uptimeMillis())

                rotatedBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun startTracking() {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDialog()
            return
        }

        isTracking = true
        toggleButton.text = getString(R.string.stop_tracking)
        updateStatus("Tracking active - Show your hand")

        Log.d(TAG, "Tracking started")
    }

    private fun stopTracking() {
        isTracking = false
        handVisible = false
        toggleButton.text = getString(R.string.start_tracking)
        updateStatus("Tracking stopped")
        gestureDetector.reset()

        Log.d(TAG, "Tracking stopped")
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    // HandTracker.HandTrackingListener implementation
    override fun onHandDetected(landmarks: List<FloatArray>) {
        noHandSince = 0L

        // Throttle the per-frame status update to avoid UI jank
        val now = System.currentTimeMillis()
        if (!handVisible || now - lastHandStatusUpdate > 1000L) {
            handVisible = true
            lastHandStatusUpdate = now
            runOnUiThread {
                statusText.text = "Hand detected - ${landmarks.size} landmarks"
            }
        }

        // Detect gestures
        gestureDetector.detectGesture(landmarks)
    }

    override fun onNoHandDetected() {
        val now = System.currentTimeMillis()
        if (noHandSince == 0L) noHandSince = now

        // Only update the status text when the hand state actually changes
        if (handVisible) {
            handVisible = false
            lastHandStatusUpdate = now
            runOnUiThread {
                statusText.text = "No hand detected"
            }
        }

        // The hand has been fully out of view for a moment: release the
        // one-shot gesture lock so the same gesture can be used again.
        if (now - noHandSince >= 300L) {
            gestureDetector.reset()
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "Hand tracking error: $error")
        runOnUiThread {
            statusText.text = "Error: $error"
        }
    }

    // GestureDetector.GestureListener implementation
    override fun onGestureDetected(gesture: GestureDetector.Gesture, x: Float, y: Float) {
        Log.d(TAG, "Gesture detected: $gesture at ($x, $y)")

        runOnUiThread {
            when (gesture) {
                GestureDetector.Gesture.POINT -> {
                    statusText.text = "👆 TAP detected!"
                    performTap(x, y)
                }
                GestureDetector.Gesture.SWIPE_LEFT -> {
                    statusText.text = "← SWIPE LEFT"
                    performSwipe(x, y, -SWIPE_PIXELS, 0f)
                }
                GestureDetector.Gesture.SWIPE_RIGHT -> {
                    statusText.text = "→ SWIPE RIGHT"
                    performSwipe(x, y, SWIPE_PIXELS, 0f)
                }
                GestureDetector.Gesture.SWIPE_UP -> {
                    statusText.text = "↑ SWIPE UP"
                    performSwipe(x, y, 0f, -SWIPE_PIXELS)
                }
                GestureDetector.Gesture.SWIPE_DOWN -> {
                    statusText.text = "↓ SWIPE DOWN"
                    performSwipe(x, y, 0f, SWIPE_PIXELS)
                }
                GestureDetector.Gesture.PINCH_IN -> {
                    statusText.text = "🤏 PINCH IN (Zoom out)"
                }
                GestureDetector.Gesture.PINCH_OUT -> {
                    statusText.text = "🤏 PINCH OUT (Zoom in)"
                }
                GestureDetector.Gesture.TWO_FINGERS -> {
                    statusText.text = "✌️ TWO FINGERS (Activation)"
                }
                else -> {}
            }
        }
    }

    /**
     * Maps normalized landmark coordinates (in the processed frame space) to
     * PreviewView coordinates using the same fit-center math the preview uses.
     */
    private fun mapToView(normalizedX: Float, normalizedY: Float): Pair<Float, Float> {
        val viewW = previewView.width
        val viewH = previewView.height
        if (viewW == 0 || viewH == 0 || frameWidth == 0 || frameHeight == 0) {
            // Preview not laid out yet - fall back to full-screen mapping
            return Pair(normalizedX * screenWidth, normalizedY * screenHeight)
        }
        val scale = min(viewW.toFloat() / frameWidth, viewH.toFloat() / frameHeight)
        val offsetX = (viewW - frameWidth * scale) / 2f
        val offsetY = (viewH - frameHeight * scale) / 2f
        return Pair(
            offsetX + normalizedX * frameWidth * scale,
            offsetY + normalizedY * frameHeight * scale
        )
    }

    private fun performTap(normalizedX: Float, normalizedY: Float) {
        val service = GestureAccessibilityService.getInstance()
        if (service != null) {
            // Convert normalized coordinates to screen coordinates
            val (screenX, screenY) = mapToView(normalizedX, normalizedY)

            service.performTap(screenX, screenY)
            Log.d(TAG, "Tap performed at ($screenX, $screenY)")
        } else {
            Toast.makeText(this, "Accessibility service not enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSwipe(normalizedX: Float, normalizedY: Float, dx: Float, dy: Float) {
        val service = GestureAccessibilityService.getInstance()
        if (service != null) {
            val (startX, startY) = mapToView(normalizedX, normalizedY)
            val endX = startX + dx
            val endY = startY + dy

            service.performSwipe(startX, startY, endX, endY, 300)
            Log.d(TAG, "Swipe performed from ($startX, $startY) to ($endX, $endY)")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CAMERA -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkPermissions()
                } else {
                    Toast.makeText(
                        this,
                        "Camera permission is required",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY, REQUEST_ACCESSIBILITY -> {
                checkPermissions()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handTracker.close()
        cameraExecutor.shutdown()
    }
}
