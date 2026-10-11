package com.example.facerecognition

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-scoped executor for camera frame analysis.
 *
 * WHY IT IS NEVER SHUT DOWN (this is a crash fix, not a style choice):
 * ML Kit hands back a `com.google.android.gms.tasks.Task`, and the Tasks library
 * posts its own internal continuations (`zzj.zzd` -> `ThreadPoolExecutor.execute`)
 * to whichever executor a listener was registered on. Those posts can happen while
 * an Activity is tearing down — after `shutdown()` — and `ThreadPoolExecutor`'s
 * abort policy then throws `RejectedExecutionException` on the main thread, killing
 * the process. A `try/catch` around `detector.process()` cannot prevent it, because
 * the rejection happens later, inside Tasks, on the completion path. Observed on
 * Android 16 while rotating the device with detection running.
 *
 * So the analysis executor outlives any single Activity. It is a daemon thread, so
 * it never delays process exit, and it is a fixed single thread, so it cannot grow
 * per rotation (the per-rotation leak was the *detector client*, closed in
 * `MainActivity.onDestroy`, not the thread).
 */
object AnalysisExecutor {

    val instance: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "face-analysis").apply { isDaemon = true }
        }
    }
}
