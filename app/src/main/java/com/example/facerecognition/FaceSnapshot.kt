package com.example.facerecognition

import android.graphics.Rect

/**
 * An immutable, self-contained description of one detected face.
 *
 * WHY THIS CLASS EXISTS (fixes the use-after-free in the original code):
 * ML Kit's `com.google.mlkit.vision.face.Face` is only a *view* over the native
 * image buffer owned by the `ImageProxy`. The old code kept a `Face` alive in
 * `FaceRecognizer.currentFace` and later read its contours/landmarks from a
 * button click — i.e. after `imageProxy.close()` had already released the
 * underlying planes. That reads freed native memory (stale data or a native
 * crash). Every value we ever need is therefore copied out here, on the analysis
 * thread, *while the proxy is still open*, and no `Face` reference escapes.
 *
 * Coordinate space note: `bounds` is in ML Kit's output space, which is the
 * image AFTER `InputImage`'s rotation has been applied. So for a portrait phone
 * with a 90-degree sensor rotation, `imageWidth`/`imageHeight` are already
 * portrait-oriented and no further rotation is needed when mapping to the screen.
 *
 * `descriptor` is a coarse pose/geometry vector — see [FaceRecognizer] for why it
 * must never be presented as an identity verification.
 */
class FaceSnapshot(
    val bounds: Rect,
    val imageWidth: Int,
    val imageHeight: Int,
    val trackingId: Int?,
    val descriptor: FloatArray,
    val matchedName: String?
) {
    val area: Long
        get() = bounds.width().toLong() * bounds.height().toLong()
}
