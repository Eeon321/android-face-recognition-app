package com.example.facerecognition

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class FaceDetectionService : Service() {
    
    private lateinit var scheduledExecutor: ScheduledExecutorService
    private val faceRecognizer = FaceRecognizer()
    private var isRunning = false
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "FaceDetectionChannel"
        private const val TAG = "FaceDetectionService"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduledExecutor = Executors.newScheduledThreadPool(1)
        faceRecognizer.initialize(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_DETECTION" -> startDetection()
            "STOP_DETECTION" -> stopDetection()
        }
        return START_STICKY
    }
    
    private fun startDetection() {
        if (isRunning) return
        
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Schedule periodic face detection with reduced frequency
        scheduledExecutor.scheduleAtFixedRate({
            try {
                performFaceDetection()
            } catch (e: Exception) {
                Log.e(TAG, "Face detection error", e)
            }
        }, 0, 2, TimeUnit.SECONDS) // Check every 2 seconds instead of continuous
        
        Log.d(TAG, "Face detection service started")
    }
    
    private fun stopDetection() {
        isRunning = false
        scheduledExecutor.shutdown()
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "Face detection service stopped")
    }
    
    private fun performFaceDetection() {
        // This would integrate with camera in a real implementation
        // For now, just log that detection is running
        Log.d(TAG, "Performing face detection cycle")
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Face Detection Active")
            .setContentText("Monitoring for faces in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Face Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background face detection service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
    }
} 