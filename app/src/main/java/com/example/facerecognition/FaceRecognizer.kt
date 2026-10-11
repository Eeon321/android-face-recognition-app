package com.example.facerecognition

import com.google.mlkit.vision.face.Face
import kotlin.math.pow
import kotlin.math.sqrt

data class FaceEmbedding(
    val name: String,
    val features: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceEmbedding

        if (name != other.name) return false
        return features.contentEquals(other.features)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + features.contentHashCode()
        return result
    }
}

class FaceRecognizer {
    
    companion object {
        private const val SIMILARITY_THRESHOLD = 0.7f
        private const val MAX_EMBEDDINGS = 50 // Limit stored faces to prevent memory issues
        private const val FEATURE_VECTOR_SIZE = 20 // Simplified feature vector size
    }
    
    private val faceEmbeddings = mutableListOf<FaceEmbedding>()
    private val _recognizedFaces = mutableMapOf<Int, String>()
    var currentFace: Face? = null
    private lateinit var context: android.content.Context
    
    fun initialize(context: android.content.Context) {
        this.context = context
    }
    
    fun addFaceEmbedding(name: String, face: Face) {
        val features = extractFaceFeatures(face)
        val embedding = FaceEmbedding(name, features)
        
        // Performance optimization: Limit number of stored embeddings
        if (faceEmbeddings.size >= MAX_EMBEDDINGS) {
            faceEmbeddings.removeAt(0) // Remove oldest embedding
        }
        faceEmbeddings.add(embedding)
    }
    
    fun registerFace(name: String) {
        currentFace?.let { face ->
            addFaceEmbedding(name, face)
        }
    }
    
    fun recognize(face: Face): String? {
        currentFace = face
        val faceFeatures = extractFaceFeatures(face)
        var bestMatch: Pair<String, Float>? = null
        
        // Compare with all stored embeddings
        for (embedding in faceEmbeddings) {
            val similarity = calculateSimilarity(faceFeatures, embedding.features)
            
            if (similarity > SIMILARITY_THRESHOLD && 
                (bestMatch == null || similarity > bestMatch.second)) {
                bestMatch = embedding.name to similarity
            }
        }
        
        // If we found a match and the face has a tracking ID, add it to recognized faces
        if (bestMatch != null && face.trackingId != null) {
            _recognizedFaces[face.trackingId!!] = bestMatch.first
        }
        
        return bestMatch?.first
    }
    
    private fun extractFaceFeatures(face: Face): FloatArray {
        // Performance optimization: Simplified feature extraction
        val features = FloatArray(FEATURE_VECTOR_SIZE)
        var index = 0
        
        // Add face rotation angles (3 features)
        features[index++] = face.headEulerAngleX
        features[index++] = face.headEulerAngleY
        features[index++] = face.headEulerAngleZ
        
        // Add normalized bounding box (4 features)
        val boundingBox = face.boundingBox
        features[index++] = boundingBox.left.toFloat() / 1000f // Normalize
        features[index++] = boundingBox.top.toFloat() / 1000f
        features[index++] = boundingBox.width().toFloat() / 1000f
        features[index++] = boundingBox.height().toFloat() / 1000f
        
        // Add face attributes if available (3 features)
        features[index++] = face.smilingProbability ?: 0f
        features[index++] = face.leftEyeOpenProbability ?: 0f
        features[index++] = face.rightEyeOpenProbability ?: 0f
        
        // Add simplified contour points (10 features) - only key points
        val contours = face.allContours
        if (contours.isNotEmpty()) {
            val contour = contours.first()
            val points = contour.points
            val step = maxOf(1, points.size / 10)
            
            for (i in 0 until minOf(10, points.size) step step) {
                if (index < FEATURE_VECTOR_SIZE) {
                    features[index++] = points[i].x / 1000f // Normalize
                }
            }
        }
        
        // Fill remaining slots with zeros
        while (index < FEATURE_VECTOR_SIZE) {
            features[index++] = 0f
        }
        
        return features
    }
    
    private fun calculateSimilarity(features1: FloatArray, features2: FloatArray): Float {
        // Use cosine similarity for feature comparison
        // In a real implementation, we would use a more sophisticated comparison
        
        // Make sure the arrays are the same length
        val minLength = minOf(features1.size, features2.size)
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in 0 until minLength) {
            dotProduct += features1[i] * features2[i]
            norm1 += features1[i].pow(2)
            norm2 += features2[i].pow(2)
        }
        
        // Avoid division by zero
        if (norm1 == 0f || norm2 == 0f) {
            return 0f
        }
        
        return dotProduct / (sqrt(norm1) * sqrt(norm2))
    }
    
    val recognizedFaces: Map<Int, String>
        get() = _recognizedFaces.toMap()
    
    fun clearRecognizedFaces() {
        _recognizedFaces.clear()
    }
    
    fun clearAllEmbeddings() {
        faceEmbeddings.clear()
        _recognizedFaces.clear()
    }
    
    fun getEmbeddingCount(): Int {
        return faceEmbeddings.size
    }
}
