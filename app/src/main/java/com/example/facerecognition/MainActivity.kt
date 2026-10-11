package com.example.facerecognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

import com.example.facerecognition.databinding.ActivityMainBinding
import com.example.facerecognition.databinding.DialogRegisterFaceBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var detectionEnabled = false
    private val faceRecognizer = FaceRecognizer()
    private val tag = "MainActivity"
    private val cameraPermissionRequestCode = 100
    
    // Performance optimization variables
    private var lastProcessingTime = 0L
    private val processingInterval = 500L // Process every 500ms instead of every frame
    
    // Power management
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, cameraPermissionRequestCode)
        }
        
        // Set up the listeners for buttons
        binding.btnToggleDetection.setOnClickListener { toggleDetection() }
        binding.btnRegisterFace.setOnClickListener {
            if (faceRecognizer.currentFace != null) {
                showRegisterFaceDialog()
            } else {
                Toast.makeText(this, R.string.no_face_detected, Toast.LENGTH_SHORT).show()
            }
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Initialize face recognizer
        faceRecognizer.initialize(this)
        
        // Initialize power management
        powerManager = getSystemService(PowerManager::class.java)
    }
    
    private fun toggleDetection() {
        detectionEnabled = !detectionEnabled
        binding.btnToggleDetection.text = if (detectionEnabled) {
            getString(R.string.stop_detection)
        } else {
            getString(R.string.start_detection)
        }
        binding.faceOverlayView.setDetectionEnabled(detectionEnabled)
        
        // Power management: Acquire/release wake lock
        if (detectionEnabled) {
            acquireWakeLock()
            startBackgroundService()
        } else {
            releaseWakeLock()
            stopBackgroundService()
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FaceRecognition::DetectionWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes timeout
            }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
    
    private fun startBackgroundService() {
        val intent = Intent(this, FaceDetectionService::class.java).apply {
            action = "START_DETECTION"
        }
        startForegroundService(intent)
    }
    
    private fun stopBackgroundService() {
        val intent = Intent(this, FaceDetectionService::class.java).apply {
            action = "STOP_DETECTION"
        }
        stopService(intent)
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            
            val faceDetectorOptions = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE) // Disable contours for better performance
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE) // Disable classifications
                .setMinFaceSize(0.2f) // Increase minimum face size for faster processing
                .enableTracking()
                .build()
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer(faceDetectorOptions) { faces, width, height ->
                        processFaces(faces, width, height)
                    })
                }
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(tag, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun processFaces(faces: List<Face>, imageWidth: Int = 1280, imageHeight: Int = 720) {
        if (!detectionEnabled) {
            binding.tvFaceInfo.visibility = View.INVISIBLE
            binding.faceOverlayView.updateFaces(emptyList(), null)
            return
        }
        
        // Performance optimization: Skip processing if too frequent
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessingTime < processingInterval) {
            return
        }
        lastProcessingTime = currentTime
        
        binding.faceOverlayView.setCameraResolution(imageWidth, imageHeight)
        binding.faceOverlayView.updateFaces(faces, faceRecognizer.recognizedFaces)
        
        // Find the largest face and try to recognize it
        val largestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        largestFace?.let {
            val recognizedName = faceRecognizer.recognize(it)
            binding.tvFaceInfo.text = if (recognizedName != null) {
                getString(R.string.face_recognized, recognizedName)
            } else {
                getString(R.string.unknown_face)
            }
            binding.tvFaceInfo.visibility = View.VISIBLE
        } ?: run {
            binding.tvFaceInfo.visibility = View.INVISIBLE
        }
    }
    
    private fun showRegisterFaceDialog() {
        val dialogBinding = DialogRegisterFaceBinding.inflate(layoutInflater)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.etPersonName.text.toString()
                if (name.isNotBlank()) {
                    faceRecognizer.registerFace(name)
                    Toast.makeText(this, R.string.face_registered, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        
        dialog.show()
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequestCode) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        releaseWakeLock()
        stopBackgroundService()
    }
    
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    private class FaceAnalyzer(
        options: FaceDetectorOptions,
        private val onFacesDetected: (List<Face>, Int, Int) -> Unit
    ) : ImageAnalysis.Analyzer {
        
        private val detector = FaceDetection.getClient(options)
        private var frameCount = 0
        
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            // Performance optimization: Skip frames to reduce processing load
            frameCount++
            if (frameCount % 3 != 0) { // Process every 3rd frame
                imageProxy.close()
                return
            }
            
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        onFacesDetected(faces, image.width, image.height)
                    }
                    .addOnFailureListener { e ->
                        Log.e("FaceAnalyzer", "Face detection failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}
