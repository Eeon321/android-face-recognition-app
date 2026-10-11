package com.example.facerecognition

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Draws face boxes and candidate names on top of the camera preview.
 *
 * ── D-5: why the geometry changed ───────────────────────────────────────────
 * The old code mapped image coordinates to the view with
 * `scaleX = viewWidth / cameraWidth` and `scaleY = viewHeight / cameraHeight`,
 * i.e. a non-uniform stretch. That is wrong twice over:
 *   - `PreviewView` defaults to [PreviewView.ScaleType.FILL_CENTER], which crops
 *     to preserve aspect ratio, so the overlay must apply the *same* uniform
 *     scale plus a centring offset;
 *   - ML Kit returns boxes in the space of the image *after* the frame's rotation
 *     has been applied, so the widths/heights handed to the old code (raw analysis
 *     width/height, and default 1280x720 when the callback used its defaults) did
 *     not describe that space at all.
 * The visible symptom was boxes that drift with aspect ratio, which looks like a
 * GPU artifact but is a transform bug. Boxes are now computed from the frame's real
 * output dimensions carried in [FaceSnapshot].
 *
 * ML Kit `Face` objects are no longer accepted here for exactly the reason
 * documented on [FaceSnapshot] — they must not outlive their frame.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private fun dp(value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)

    private fun sp(value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), resources.displayMetrics)

    private val boxStrokeWidth = dp(2)

    private val faceBackgroundPaint = Paint().apply {
        color = context.getColor(R.color.face_box_background)
        style = Paint.Style.FILL
    }

    private val recognizedFacePaint = Paint().apply {
        color = context.getColor(R.color.recognized_face_box_stroke)
        style = Paint.Style.STROKE
        strokeWidth = boxStrokeWidth
    }

    private val unknownFacePaint = Paint().apply {
        color = context.getColor(R.color.unknown_face_box_stroke)
        style = Paint.Style.STROKE
        strokeWidth = boxStrokeWidth
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = sp(14)
        isFakeBoldText = true
    }

    private val labelBackgroundPaint = Paint().apply {
        color = context.getColor(R.color.face_box_background)
        style = Paint.Style.FILL
    }

    private var snapshots: List<FaceSnapshot> = emptyList()
    private var detectionEnabled = false
    private val unknownLabel = context.getString(R.string.unknown_face_label)

    /**
     * Set true if the preview surface mirrors the front camera. `PreviewView` in
     * CameraX 1.3.0 does not mirror, so the default false keeps boxes aligned with
     * what is on screen; flip this together with `PreviewView.setMirroringEnabled`
     * if the CameraX version is ever upgraded (mirroring landed in 1.4.x).
     */
    private var previewMirrored = false

    fun updateSnapshots(snapshots: List<FaceSnapshot>) {
        this.snapshots = snapshots
        invalidate()
    }

    fun setDetectionEnabled(enabled: Boolean) {
        detectionEnabled = enabled
        invalidate()
    }

    fun setPreviewMirrored(mirrored: Boolean) {
        previewMirrored = mirrored
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!detectionEnabled) return
        // Drop references as soon as we are done with them so a stopped camera does
        // not keep descriptors pinned in memory.
        val current = snapshots
        if (current.isEmpty()) return

        val first = current.first()
        val imageWidth = first.imageWidth
        val imageHeight = first.imageHeight
        if (imageWidth <= 0 || imageHeight <= 0) return
        val mirrored = previewMirrored

        // Uniform scale + centring offset: must mirror PreviewView's FILL_CENTER.
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        for (snapshot in current) {
            // Per-snapshot dimensions guard against a frame boundary mid-draw.
            if (snapshot.imageWidth != imageWidth || snapshot.imageHeight != imageHeight) continue

            val left = snapshot.bounds.left * scale + offsetX
            val top = snapshot.bounds.top * scale + offsetY
            val right = snapshot.bounds.right * scale + offsetX
            val bottom = snapshot.bounds.bottom * scale + offsetY
            val scaled = RectF(left, top, right, bottom)

            val name = snapshot.matchedName
            val drawn = if (mirrored) {
                // Mirror horizontally about the view centre so boxes track a mirrored
                // preview surface instead of landing on the opposite side.
                RectF(width - right, top, width - left, bottom)
            } else {
                scaled
            }
            canvas.drawRect(drawn, faceBackgroundPaint)
            canvas.drawRect(drawn, if (name != null) recognizedFacePaint else unknownFacePaint)

            drawLabel(canvas, name ?: unknownLabel, drawn)
        }
    }

    /**
     * Labels are drawn on a filled background rect instead of using
     * Paint.setShadowLayer: shadow layers are silently ignored on a
     * hardware-accelerated canvas unless the whole view is forced to a software
     * layer, which is an expensive way to get readable text.
     */
    private fun drawLabel(canvas: Canvas, label: String, box: RectF) {
        val padding = dp(4)
        val textWidth = textPaint.measureText(label)
        val textHeight = textPaint.textSize
        val labelTop = (box.top - textHeight - padding * 2).coerceAtLeast(0f)
        val labelLeft = box.left.coerceAtLeast(0f)
        val labelRight = (labelLeft + textWidth + padding * 2).coerceAtMost(width.toFloat())

        canvas.drawRect(
            labelLeft,
            labelTop,
            labelRight,
            labelTop + textHeight + padding * 2,
            labelBackgroundPaint
        )
        canvas.drawText(label, labelLeft + padding, labelTop + padding + textPaint.textSize - textPaint.descent(), textPaint)
    }
}
