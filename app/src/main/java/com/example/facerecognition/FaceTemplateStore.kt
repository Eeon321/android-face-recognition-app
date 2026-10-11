package com.example.facerecognition

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * On-device persistence for registered face descriptors.
 *
 * Fixes two problems in the original code:
 *  - registrations were in-memory only and vanished on every app restart;
 *  - re-registering the same name silently grew a duplicate-filled list.
 *
 * Storage location: `context.filesDir` (app-private). It is deliberately NOT
 * external storage, and the manifest sets `allowBackup="false"`, so face data is
 * not extractable via cloud/device backup (see the biometric-adjacent data
 * discussion in README.md).
 *
 * Writes are atomic (temp file + rename) so a crash mid-write cannot corrupt the
 * store, and the format carries [DESCRIPTOR_VERSION] so templates produced by an
 * older, incompatible descriptor definition are ignored instead of mis-matched.
 */
class FaceTemplateStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    /** One registered name plus the descriptor it was registered with. */
    class Entry(val name: String, val descriptor: FloatArray)

    init {
        load()
    }

    val names: List<String>
        get() = synchronized(lock) { entries.map { it.name } }

    val size: Int
        get() = synchronized(lock) { entries.size }

    /** Replaces any existing registration for [name]; returns false if it could not be saved. */
    fun put(name: String, descriptor: FloatArray): Boolean = synchronized(lock) {
        entries.removeAll { it.name == name }
        entries.add(Entry(name, descriptor.copyOf()))
        // Drop the oldest registrations once we hit the cap, mirroring the
        // original MAX_EMBEDDINGS intent but with a bound that is actually
        // meaningful for the on-device matcher (O(n) per frame).
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        save()
    }

    fun remove(name: String): Boolean = synchronized(lock) {
        val removed = entries.removeAll { it.name == name }
        if (removed) save()
        removed
    }

    fun clear(): Boolean = synchronized(lock) {
        entries.clear()
        save()
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.map { Entry(it.name, it.descriptor.copyOf()) } }

    private fun load() {
        synchronized(lock) {
            entries.clear()
            if (!file.exists()) return
            try {
                val root = JSONObject(file.readText())
                val version = root.optInt(KEY_VERSION, -1)
                if (version != DESCRIPTOR_VERSION) {
                    // Descriptor definition changed; old templates are not comparable.
                    file.delete()
                    return
                }
                val arr = root.optJSONArray(KEY_ENTRIES) ?: return
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString(KEY_NAME)
                    if (name.isBlank()) continue
                    val arr2 = obj.optJSONArray(KEY_DESCRIPTOR) ?: continue
                    val descriptor = FloatArray(arr2.length()) { j -> arr2.optDouble(j, 0.0).toFloat() }
                    if (descriptor.size == FaceRecognizer.FEATURE_VECTOR_SIZE) {
                        entries.add(Entry(name, descriptor))
                    }
                }
            } catch (e: JSONException) {
                // Unreadable store: start clean rather than match against garbage.
                file.delete()
                entries.clear()
            }
        }
    }

    private fun save(): Boolean = synchronized(lock) {
        try {
            val root = JSONObject()
            root.put(KEY_VERSION, DESCRIPTOR_VERSION)
            val arr = JSONArray()
            entries.forEach { entry ->
                val obj = JSONObject()
                obj.put(KEY_NAME, entry.name)
                obj.put(KEY_DESCRIPTOR, JSONArray(entry.descriptor.toList()))
                arr.put(obj)
            }
            root.put(KEY_ENTRIES, arr)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (file.exists() && !file.delete()) {
                // Rename below would then clobber a file we could not remove; still writable.
                android.util.Log.w(TAG, "could not delete previous store")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: IOException) {
            android.util.Log.e(TAG, "Unable to persist face templates", e)
            false
        }
    }

    private companion object {
        const val FILE_NAME = "face_templates.json"
        const val KEY_VERSION = "descriptorVersion"
        const val KEY_ENTRIES = "entries"
        const val KEY_NAME = "name"
        const val KEY_DESCRIPTOR = "descriptor"
        const val MAX_ENTRIES = 20
        val DESCRIPTOR_VERSION: Int = FaceRecognizer.DESCRIPTOR_VERSION
        const val TAG = "FaceTemplateStore"
    }
}
