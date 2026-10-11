package com.example.facerecognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.facerecognition.databinding.ActivityMainBinding
import com.example.facerecognition.databinding.DialogRegisterFaceBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DetectionViewModel by lazy {
        ViewModelProvider(this)[DetectionViewModel::class.java]
    }

    /**
     * Dedicated analysis thread; ML Kit inference never runs on the main thread.
     * Deliberately not owned (or shut down) by the Activity — see [AnalysisExecutor].
     */
    private val cameraExecutor: ExecutorService = AnalysisExecutor.instance

    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * One detector client for the Activity's lifetime, closed in onDestroy.
     * Fixes D-9: previously a new `FaceDetection.getClient()` was created inside the
     * analyzer for every Activity recreation and never closed, leaking a native
     * detector each rotation.
     */
    private var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null

    /** Latest frames, produced on the analysis thread and read from the UI thread. */
    @Volatile
    private var latestSnapshots: List<FaceSnapshot> = emptyList()

    @Volatile
    private var lastAnalysisUs = 0L

    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private var openDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Single detector client for this Activity's lifetime (closed in onDestroy).
        // CLASSIFICATION_MODE_ALL is required, not cosmetic: with classifications disabled
        // the smile/eye probabilities are always null, which is what zero-filled a third
        // of the old descriptor vector and made matching nearly blind.
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(MIN_FACE_SIZE)
                .enableTracking()
                .build()
        )

        // The overlay's transform assumes FILL_CENTER; make that explicit instead of
        // relying on a default that has changed between CameraX versions.
        binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER

        binding.btnToggleDetection.setOnClickListener { toggleDetection() }
        binding.btnRegisterFace.setOnClickListener { registerFace() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }

        // Restore the toggle label from ViewModel state (survives rotation).
        syncDetectionUi()

        ensureCameraPermission()
    }

    // ── Permissions (D-21) ──────────────────────────────────────────────────────

    private fun ensureCameraPermission() {
        if (allPermissionsGranted()) {
            startCamera()
            return
        }
        // A bare Toast + finish() used to make the app unrecoverable after a second
        // denial, because Android then stops showing the system dialog entirely.
        if (shouldShowRequestPermissionRationale()) {
            showPermissionDialog(explainFirst = true)
        } else {
            requestCameraPermission()
        }
    }

    private fun shouldShowRequestPermissionRationale() =
        ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE)
    }

    private fun showPermissionDialog(explainFirst: Boolean) {
        openDialog?.dismiss()
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.camera_permission_required)
            .setMessage(R.string.camera_permission_message)

        if (explainFirst) {
            builder.setPositiveButton(R.string.allow) { _, _ -> requestCameraPermission() }
        } else {
            // Permanently denied: only the app settings screen can restore it.
            builder.setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
        }
        builder.setNeutralButton(R.string.close_app) { _, _ -> finish() }

        openDialog = builder.create().also { it.show() }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open app settings", e)
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            showPermissionDialog(explainFirst = false)
        }
    }

    // ── Camera ──────────────────────────────────────────────────────────────────

    private fun startCamera() {
        if (isFinishing || isDestroyed) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable", e)
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        /*
         * Binding can fail for a specific (lens facing, resolution) combination even
         * though the device is perfectly usable — e.g. an emulator or low-end front
         * sensor that cannot satisfy a 4:3 analysis stream. A single attempt used to
         * leave the user on a black preview with only a log line, so try progressively
         * cheaper combinations and remember which one actually bound.
         */
        val otherFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val attempts = listOf(
            BindAttempt(lensFacing, useResolutionSelector = true),
            BindAttempt(otherFacing, useResolutionSelector = true),
            BindAttempt(lensFacing, useResolutionSelector = false),
            BindAttempt(otherFacing, useResolutionSelector = false)
        )

        for (attempt in attempts) {
            try {
                bind(provider, attempt.lensFacing, attempt.useResolutionSelector)
                applyFacing(attempt.lensFacing)
                Log.i(
                    TAG,
                    "Bound camera: lensFacing=${if (attempt.lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"}, " +
                        "resolutionSelector=${attempt.useResolutionSelector}"
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "Bind attempt failed (facing=${attempt.lensFacing}, " +
                    "resolutionSelector=${attempt.useResolutionSelector})", e)
            }
        }
        Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
    }

    private class BindAttempt(val lensFacing: Int, val useResolutionSelector: Boolean)

    private fun bind(provider: ProcessCameraProvider, facing: Int, useResolutionSelector: Boolean) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (useResolutionSelector) {
            // Cap the analysis resolution: inference cost scales with pixels, and the
            // overlay only needs a coarse box. This is the real lever on CPU load.
            analysisBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
        }
        val imageAnalyzer = analysisBuilder.build().also { it.setAnalyzer(cameraExecutor, FaceAnalyzer()) }

        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, imageAnalyzer)
    }

    /** Preview mirroring for the surface and the overlay is decided in one place. */
    private fun applyFacing(facing: Int) {
        lensFacing = facing
        val front = facing == CameraSelector.LENS_FACING_FRONT
        binding.viewFinder.setPreviewMirroredCompat(front)
    }

    /**
     * D-16: a face app should open on the front camera. The facing is held in a field
     * and re-bound on demand rather than hardcoded to DEFAULT_BACK_CAMERA.
     */
    private fun switchCamera() {
        applyFacing(
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK
            else CameraSelector.LENS_FACING_FRONT
        )
        cameraProvider?.let { bindUseCases(it) }
    }

    // ── Detection toggle ────────────────────────────────────────────────────────

    private fun toggleDetection() {
        viewModel.toggleDetection()
        syncDetectionUi()
    }

    private fun syncDetectionUi() {
        val enabled = viewModel.detectionEnabled.value
        binding.btnToggleDetection.setText(
            if (enabled) R.string.stop_detection else R.string.start_detection
        )
        binding.faceOverlayView.setDetectionEnabled(enabled)
        if (!enabled) {
            latestSnapshots = emptyList()
            binding.faceOverlayView.updateSnapshots(emptyList())
            binding.tvFaceInfo.visibility = View.INVISIBLE
        }
    }

    /**
     * Analysis callback. Runs on [cameraExecutor].
     *
     * D-14: the old code skipped 2 of 3 frames in the analyzer *and* throttled again
     * in `processFaces` — but that second gate returned after inference had already
     * run, so it saved no CPU and only made the drawn boxes stale. One gate now runs
     * before inference, keyed off the frame timestamp (monotonic, and 0 on some
     * devices, hence the fallback).
     */
    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val detector = faceDetector
            if (detector == null || !viewModel.detectionEnabled.value) {
                imageProxy.close()
                return
            }

            val frameTimeUs = imageProxy.imageInfo.timestamp.takeIf { it > 0L }
                ?: (System.nanoTime() / 1000L)
            if (frameTimeUs - lastAnalysisUs < ANALYSIS_INTERVAL_MS * 1000L) {
                imageProxy.close()
                return
            }
            lastAnalysisUs = frameTimeUs

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            // InputImage consumes imageInfo.rotationDegrees, so ML Kit reports boxes in
            // the already-rotated space; image.width/height below are that rotated size.
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            try {
                detector.process(image)
                    .addOnSuccessListener(cameraExecutor) { faces ->
                        handleFaces(faces, image.width, image.height)
                    }
                    .addOnFailureListener(cameraExecutor) { e ->
                        Log.e(TAG, "Face detection failed", e)
                    }
                    .addOnCompleteListener(cameraExecutor) {
                        // Closes the frame only after the listeners above have copied
                        // everything they need out of the Face objects.
                        imageProxy.close()
                    }
            } catch (e: RejectedExecutionException) {
                // Activity is being destroyed and the executor has shut down.
                imageProxy.close()
            }
        }
    }

    /**
     * Runs on [cameraExecutor] while the frame is still open. Every value the rest of
     * the app needs is copied into immutable snapshots here; D-3.
     */
    private fun handleFaces(faces: List<Face>, imageWidth: Int, imageHeight: Int) {
        val recognizer = viewModel.recognizer
        val snapshots = faces.map { face ->
            val descriptor = recognizer.extractDescriptor(face)
            FaceSnapshot(
                bounds = android.graphics.Rect(face.boundingBox),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                trackingId = face.trackingId,
                descriptor = descriptor,
                matchedName = recognizer.match(descriptor)
            )
        }
        latestSnapshots = snapshots
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            binding.faceOverlayView.updateSnapshots(snapshots)
            updateFaceInfoText(snapshots)
        }
    }

    /**
     * D-4: the name comes from a coarse pose/geometry match, so the copy says
     * "possible match" instead of asserting an identity.
     */
    private fun updateFaceInfoText(snapshots: List<FaceSnapshot>) {
        if (snapshots.isEmpty() || !viewModel.detectionEnabled.value) {
            binding.tvFaceInfo.visibility = View.INVISIBLE
            return
        }
        val match = snapshots.firstNotNullOfOrNull { it.matchedName }
        binding.tvFaceInfo.text = if (match != null) {
            getString(R.string.candidate_match, match)
        } else {
            getString(R.string.unknown_face)
        }
        binding.tvFaceInfo.visibility = View.VISIBLE
    }

    // ── Registration ────────────────────────────────────────────────────────────

    /**
     * Registers the largest face in the most recent frame. Uses the snapshot, not a
     * live Face, so pressing Save after the frame was released is safe.
     */
    private fun registerFace() {
        val snapshot = latestSnapshots.maxByOrNull { it.area }
        if (snapshot == null) {
            Toast.makeText(this, R.string.no_face_detected, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogRegisterFaceBinding.inflate(layoutInflater)
        // Build without AlertDialog buttons: the custom view carries its own Save/Cancel,
        // which are now the ones actually wired to the save path.
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()

        dialogBinding.btnSave.setOnClickListener {
            val name = dialogBinding.etPersonName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.face_registration_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val saved = viewModel.recognizer.register(name, snapshot.descriptor)
            Toast.makeText(
                this,
                if (saved) R.string.face_registered else R.string.face_registration_failed,
                Toast.LENGTH_SHORT
            ).show()
            if (saved) dialog.dismiss()
        }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener { openDialog = null }
        openDialog = dialog
        dialog.show()
        dialogBinding.etPersonName.requestFocus()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * PreviewView has no mirroring API in CameraX 1.3.0 (`setMirroringEnabled` arrived in
     * 1.4.x), so the front preview is shown unmirrored and the overlay is kept in sync by
     * leaving its mirror flag false. Called reflectively so that upgrading CameraX picks up
     * real mirroring without touching this file; when the method is absent nothing happens.
     */
    private fun PreviewView.setPreviewMirroredCompat(mirrored: Boolean) {
        try {
            javaClass.getMethod("setMirroringEnabled", Boolean::class.javaPrimitiveType)
                .invoke(this, mirrored)
            binding.faceOverlayView.setPreviewMirrored(mirrored)
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "PreviewView mirroring unavailable in this CameraX version")
        } catch (e: Exception) {
            Log.w(TAG, "setMirroringEnabled failed", e)
        }
    }

    override fun onDestroy() {
        // Order matters: stop the flow of frames first, then let queued analysis work
        // drain on the single-threaded executor, and close the native detector as the
        // last queued task so it cannot be closed while still in use (D-9, D-15).
        openDialog?.dismiss()
        openDialog = null

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll failed", e)
        }
        cameraProvider = null

        val detector = faceDetector
        faceDetector = null
        // Close the native detector as the last queued analysis task so it cannot be
        // closed mid-inference. The executor itself is intentionally NOT shut down:
        // see AnalysisExecutor (shutting it down here is what crashed rotation).
        try {
            cameraExecutor.execute { runCatching { detector?.close() } }
        } catch (e: RejectedExecutionException) {
            runCatching { detector?.close() }
        }

        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val CAMERA_PERMISSION_REQUEST_CODE = 100

        /** Analysis gate: ~7 fps is plenty for overlaying boxes. */
        const val ANALYSIS_INTERVAL_MS = 140L

        /** Smallest detected face, as a fraction of frame width. */
        const val MIN_FACE_SIZE = 0.15f

        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
