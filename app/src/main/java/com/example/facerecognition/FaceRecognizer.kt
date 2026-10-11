package com.example.facerecognition

import android.graphics.PointF
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Coarse pose + geometry matching over ML Kit face detections.
 *
 * ── HONESTY NOTE (this is the substance of fix D-4) ─────────────────────────────
 * The previous implementation claimed to "recognize" people. It did not, and could
 * not: its "embedding" was built from *absolute bounding-box pixel coordinates*
 * divided by a magic 1000f, plus contour pixel x-coordinates. Cosine similarity
 * over absolute pixel positions is dominated by *where and how far* the face is in
 * the frame, so two different people at the same distance and pose matched each
 * other, and one person stopped matching themselves after moving closer. Worse,
 * the detector was configured with CONTOUR_MODE_NONE and CLASSIFICATION_MODE_NONE,
 * so 13 of the 20 vector slots were permanently zero and only 7 numbers ever varied.
 *
 * ML Kit's face detector does not produce an identity descriptor at all. Real
 * face *recognition* needs a separate embedding model (MobileFaceNet/ArcFace or
 * similar) fed an aligned crop of the face. Adding one means downloading model
 * weights, which was not approved, so instead this class:
 *   1. uses translation- and scale-invariant features (head angles, expression
 *      probabilities, and landmark positions normalised by inter-ocular distance),
 *      so a match at least means "same pose and similar face proportions" rather
 *      than "same distance from the camera";
 *   2. exposes [match] returning a *candidate* name that callers must present as a
 *      possibility, never as verified identity (see the strings that consume it);
 *   3. keeps [DESCRIPTOR_VERSION] so stale templates from an incompatible
 *      definition are discarded by [FaceTemplateStore] instead of mis-matched.
 *
 * Threading: [extractDescriptor] is called on the camera analysis thread with a
 * live `Face`; [match] only touches in-memory float arrays and is synchronized.
 * No `Context` and no `Face` is retained (the old `lateinit var context` was dead
 * code and a latent Activity leak; the old `var currentFace: Face?` was the
 * use-after-free source).
 */
class FaceRecognizer(private val store: FaceTemplateStore) {

    companion object {
        /**
         * 3 head angles + 3 expression probabilities + 5 landmarks x 2 normalised
         * coordinates. Every slot is populated with a real, varying value.
         */
        const val FEATURE_VECTOR_SIZE = 16

        /** Bump whenever [extractDescriptor] changes the meaning of a slot. */
        const val DESCRIPTOR_VERSION = 2

        /**
         * Cosine-similarity gate. Deliberately strict: a false "this is Alice" is a
         * worse outcome than a missed match, because the UI shows the name to the user.
         */
        const val MATCH_THRESHOLD = 0.95f

        private const val TAG = "FaceRecognizer"
        private const val ANGLE_NORMALIZER = 90.0f
    }

    /** Cached templates, refreshed from disk on registration. */
    private val lock = Any()
    private var templates: List<FaceTemplateStore.Entry> = store.snapshot()

    /**
     * Registers [name] against the descriptor of the currently visible face.
     * Returns false when there is nothing to register or persistence failed.
     */
    fun register(name: String, descriptor: FloatArray): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || descriptor.size != FEATURE_VECTOR_SIZE) return false
        val saved = store.put(trimmed, descriptor)
        if (!saved) return false
        synchronized(lock) { templates = store.snapshot() }
        Log.i(TAG, "Registered '$trimmed'; ${store.size} template(s) on file")
        return true
    }

    fun unregister(name: String): Boolean {
        val removed = store.remove(name)
        if (removed) synchronized(lock) { templates = store.snapshot() }
        return removed
    }

    fun clearAll() {
        store.clear()
        synchronized(lock) { templates = emptyList() }
    }

    fun registeredNames(): List<String> = synchronized(lock) { templates.map { it.name } }

    fun registeredCount(): Int = synchronized(lock) { templates.size }

    /**
     * Returns the best registered candidate name for [descriptor], or null.
     * The result is a *candidate*: callers must word it as a possibility.
     */
    fun match(descriptor: FloatArray): String? {
        if (descriptor.size != FEATURE_VECTOR_SIZE) return null
        val candidates = synchronized(lock) { templates }
        if (candidates.isEmpty()) return null

        var bestName: String? = null
        var bestScore = MATCH_THRESHOLD
        for (entry in candidates) {
            val score = cosineSimilarity(descriptor, entry.descriptor)
            if (score > bestScore) {
                bestScore = score
                bestName = entry.name
            }
        }
        return bestName
    }

    /**
     * Builds the pose/geometry descriptor from a live [Face].
     * Must be called before the originating `ImageProxy` is closed.
     */
    fun extractDescriptor(face: Face): FloatArray {
        val features = FloatArray(FEATURE_VECTOR_SIZE)
        var index = 0

        // Head pose (radians-free, normalised to roughly [-1, 1]).
        features[index++] = face.headEulerAngleX / ANGLE_NORMALIZER
        features[index++] = face.headEulerAngleY / ANGLE_NORMALIZER
        features[index++] = face.headEulerAngleZ / ANGLE_NORMALIZER

        // Expression / eye-state probabilities. Requires CLASSIFICATION_MODE_ALL in
        // the detector options; with classifications disabled these are always null,
        // which is what silently zero-filled 3 of the old 20 slots.
        features[index++] = face.smilingProbability ?: 0f
        features[index++] = face.leftEyeOpenProbability ?: 0f
        features[index++] = face.rightEyeOpenProbability ?: 0f

        // Landmark geometry, made translation-invariant (relative to the inter-ocular
        // midpoint) and scale-invariant (divided by inter-ocular distance). The old
        // code normalised by a literal 1000f, i.e. raw pixel position, which is why
        // distance from camera decided the match.
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val anchor = when {
            leftEye != null && rightEye != null ->
                PointF((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)
            leftEye != null -> PointF(leftEye)
            rightEye != null -> PointF(rightEye)
            else -> PointF(face.boundingBox.centerX().toFloat(), face.boundingBox.centerY().toFloat())
        }
        val eyeDistance = if (leftEye != null && rightEye != null) {
            hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
        } else {
            face.boundingBox.width().toFloat()
        }.coerceAtLeast(1f)

        // ML Kit's FaceLandmark constants are LEFT_EYE/RIGHT_EYE/NOSE_BASE/MOUTH_LEFT/
        // MOUTH_RIGHT (there is no NOSE_TIP or LEFT_MOUTH in this API).
        val landmarkTypes = intArrayOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT
        )
        for (type in landmarkTypes) {
            val point = face.getLandmark(type)?.position
            features[index++] = if (point != null) (point.x - anchor.x) / eyeDistance else 0f
            features[index++] = if (point != null) (point.y - anchor.y) / eyeDistance else 0f
        }

        check(index == FEATURE_VECTOR_SIZE) { "Descriptor size drift: filled $index of $FEATURE_VECTOR_SIZE" }
        return features
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            normA += a[i].pow(2)
            normB += b[i].pow(2)
        }
        if (normA == 0f || normB == 0f) return 0f
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
