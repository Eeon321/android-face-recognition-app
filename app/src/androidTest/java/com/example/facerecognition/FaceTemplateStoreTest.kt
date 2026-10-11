package com.example.facerecognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.runner.RunWith

/**
 * Verifies the parts of the rewrite that the emulator UI cannot reach: the on-device
 * template store and the descriptor matcher. The emulator's fake camera renders a
 * synthetic scene with no face in it, so "Register Face" can never be exercised
 * through taps — these tests cover that gap on the real Android runtime (real
 * filesDir I/O, real JSON, real process lifecycle).
 */
@RunWith(AndroidJUnit4::class)
class FaceTemplateStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storeFile get() = File(context.filesDir, "face_templates.json")

    private fun descriptor(seed: Float): FloatArray =
        FloatArray(FaceRecognizer.FEATURE_VECTOR_SIZE) { i -> ((i + 1) * seed % 1f) }

    @Before
    @After
    fun cleanUp() {
        storeFile.delete()
        File(context.filesDir, "face_templates.json.tmp").delete()
    }

    @Test
    fun registrationSurvivesProcessRestart() {
        val store = FaceTemplateStore(context)
        val recognizer = FaceRecognizer(store)
        assertTrue(recognizer.register("Alex", descriptor(0.31f)))

        // A brand-new store simulates a fresh process reading only what is on disk.
        // This is the fix for "registrations vanished on every app restart".
        val reloaded = FaceTemplateStore(context)
        assertEquals(1, reloaded.size)
        assertEquals(listOf("Alex"), reloaded.names)

        // The reloaded template must still match its own descriptor.
        assertEquals("Alex", FaceRecognizer(reloaded).match(descriptor(0.31f)))
    }

    @Test
    fun registeringSameNameReplacesInsteadOfDuplicating() {
        val store = FaceTemplateStore(context)
        val recognizer = FaceRecognizer(store)
        recognizer.register("Sam", descriptor(0.11f))
        recognizer.register("Sam", descriptor(0.73f))
        assertEquals("re-registration must not create duplicates", 1, FaceTemplateStore(context).size)
    }

    @Test
    fun entryCountIsCapped() {
        val store = FaceTemplateStore(context)
        val recognizer = FaceRecognizer(store)
        for (i in 1..40) recognizer.register("Person$i", descriptor(0.017f * i + 0.05f))
        assertTrue("store exceeded its cap", FaceTemplateStore(context).size <= 20)
    }

    @Test
    fun templatesFromAnIncompatibleDescriptorVersionAreDiscarded() {
        // Simulates templates written by an older descriptor definition: matching
        // against them would produce confident nonsense, so they must be dropped.
        val stale = JSONObject().apply {
            put("descriptorVersion", FaceRecognizer.DESCRIPTOR_VERSION - 1)
            put(
                "entries",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Stale")
                        .put("descriptor", JSONArray(FloatArray(FaceRecognizer.FEATURE_VECTOR_SIZE) { 0.5f }.toList()))
                )
            )
        }
        storeFile.writeText(stale.toString())

        val store = FaceTemplateStore(context)
        assertEquals(0, store.size)
        assertNull(FaceRecognizer(store).match(FloatArray(FaceRecognizer.FEATURE_VECTOR_SIZE) { 0.5f }))
    }

    @Test
    fun corruptStoreStartsCleanInsteadOfThrowing() {
        storeFile.writeText("{ this is not json")
        val store = FaceTemplateStore(context)
        assertEquals(0, store.size)
        assertTrue(FaceRecognizer(store).register("Zoe", descriptor(0.27f)))
    }

    @Test
    fun matcherRejectsDissimilarDescriptorsAndBlankNames() {
        val store = FaceTemplateStore(context)
        val recognizer = FaceRecognizer(store)
        assertTrue(recognizer.register("Ada", descriptor(0.19f)))

        assertFalse(recognizer.register("   ", descriptor(0.19f)))
        assertFalse(recognizer.register("Bob", FloatArray(3)))

        // An unrelated descriptor must not be reported as a match.
        val unrelated = FloatArray(FaceRecognizer.FEATURE_VECTOR_SIZE) { i -> if (i % 3 == 0) -0.9f else 0.02f }
        assertNull(recognizer.match(unrelated))
        assertNull(recognizer.match(FloatArray(2)))
    }

    @Test
    fun unregisterAndClearRemovePersistedEntries() {
        val store = FaceTemplateStore(context)
        val recognizer = FaceRecognizer(store)
        recognizer.register("Ada", descriptor(0.19f))
        recognizer.register("Grace", descriptor(0.53f))

        assertTrue(recognizer.unregister("Ada"))
        assertFalse(recognizer.unregister("Nobody"))
        assertEquals(1, FaceTemplateStore(context).size)

        recognizer.clearAll()
        assertEquals(0, FaceTemplateStore(context).size)
    }

    /**
     * The enrolment dialog's layout was restructured, and its only production inflation
     * site is behind a "a face is currently visible" check - which the emulator's synthetic
     * camera never satisfies. Without this test a broken layout would ship as a crash that
     * only appears the first time a real user registers a face. Verifies the inflated view
     * still exposes the Save/Cancel buttons the code now wires up by hand.
     */
    @Test
    fun registerFaceDialogInflatesAndExposesItsButtons() {
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            context,
            com.example.facerecognition.R.style.Theme_FaceRecognitionApp
        )
        val inflater = android.view.LayoutInflater.from(themed)
        val binding = com.example.facerecognition.databinding.DialogRegisterFaceBinding.inflate(inflater)
        assertNotNull("name field missing", binding.etPersonName)
        assertNotNull("in-dialog Save button missing", binding.btnSave)
        assertNotNull("in-dialog Cancel button missing", binding.btnCancel)
    }
}
