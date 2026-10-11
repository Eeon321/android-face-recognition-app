package com.example.facerecognition

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.Face
import java.util.HashMap

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val faceBoundsPaint = Paint().apply {
        color = context.getColor(R.color.face_box_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 8f // Increased stroke width for better visibility
    }

    private val faceBackgroundPaint = Paint().apply {
        color = context.getColor(R.color.face_box_background)
        style = Paint.Style.FILL
    }

    private val recognizedFacePaint = Paint().apply {
        color = context.getColor(R.color.recognized_face_box_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 8f // Increased stroke width for better visibility
    }

    private val unknownFacePaint = Paint().apply {
        color = context.getColor(R.color.unknown_face_box_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 8f // Increased stroke width for better visibility
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private var faces: List<Face> = emptyList()
    private var recognizedFaces: Map<Int, String?>? = null
    private var detectionEnabled = false
    private var cameraWidth = 1280
    private var cameraHeight = 720

    fun updateFaces(faces: List<Face>, recognizedFaces: Map<Int, String?>?) {
        this.faces = faces
        this.recognizedFaces = recognizedFaces
        invalidate()
    }

    fun setDetectionEnabled(enabled: Boolean) {
        detectionEnabled = enabled
        invalidate()
    }
    
    fun setCameraResolution(width: Int, height: Int) {
        cameraWidth = width
        cameraHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!detectionEnabled) return

        // Performance optimization: Skip drawing if no faces
        if (faces.isEmpty()) return

        // Scale factors based on actual camera resolution
        val scaleX = width.toFloat() / cameraWidth
        val scaleY = height.toFloat() / cameraHeight

        for (face in faces) {
            val bounds = face.boundingBox

            // Scale the face bounds to match the view size
            val scaledBounds = Rect(
                (bounds.left * scaleX).toInt(),
                (bounds.top * scaleY).toInt(),
                (bounds.right * scaleX).toInt(),
                (bounds.bottom * scaleY).toInt()
            )

            // Check if this face is recognized
            val trackingId = face.trackingId
            val name = if (trackingId != null && recognizedFaces != null) {
                recognizedFaces!![trackingId]
            } else {
                null
            }

            // Draw the face box
            val paint = when {
                name != null -> recognizedFacePaint
                else -> unknownFacePaint
            }

            // Draw background for the box
            canvas.drawRect(scaledBounds, faceBackgroundPaint)

            // Draw the box outline
            canvas.drawRect(scaledBounds, paint)

            // If recognized, draw the name
            if (name != null) {
                canvas.drawText(
                    name,
                    scaledBounds.left.toFloat(),
                    scaledBounds.top.toFloat() - 10f,
                    textPaint
                )
            } else {
                // Draw "Unknown" for unrecognized faces
                canvas.drawText(
                    "Unknown",
                    scaledBounds.left.toFloat(),
                    scaledBounds.top.toFloat() - 10f,
                    textPaint
                )
            }
        }
    }
}
