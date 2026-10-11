package com.example.facerecognition

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns detection state and the face recognizer across configuration changes.
 *
 * Fixes D-6: the toggle lived in a plain Activity field, so rotating the device
 * reset it and stopped detection mid-session. Fixes D-8: there used to be two
 * independent `FaceRecognizer` instances (Activity and the background service),
 * so a face enrolled in one was invisible to the other; this is now the single
 * owner.
 *
 * Holds only the `Application` context (via AndroidViewModel), never an Activity,
 * so it cannot leak the Activity.
 */
class DetectionViewModel(application: Application) : AndroidViewModel(application) {

    private val _detectionEnabled = MutableStateFlow(false)
    val detectionEnabled: StateFlow<Boolean> = _detectionEnabled.asStateFlow()

    /** Single shared recognizer, backed by app-private on-device storage. */
    val recognizer: FaceRecognizer by lazy {
        FaceRecognizer(FaceTemplateStore(getApplication()))
    }

    fun setDetectionEnabled(enabled: Boolean) {
        _detectionEnabled.value = enabled
    }

    /** Returns the new state, so callers can update UI without racing the flow. */
    fun toggleDetection(): Boolean {
        _detectionEnabled.value = !_detectionEnabled.value
        return _detectionEnabled.value
    }
}
