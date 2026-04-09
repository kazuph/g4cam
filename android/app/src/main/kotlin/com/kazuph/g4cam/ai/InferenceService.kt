package com.kazuph.g4cam.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.kazuph.g4cam.NotificationHelper

private const val TAG = "InferenceService"

class InferenceService : Service() {

    companion object {
        private var isRunning = false

        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, InferenceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (!isRunning) return
            context.stopService(Intent(context, InferenceService::class.java))
        }

        fun notifyComplete(context: Context, resultText: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(
                NotificationHelper.NOTIFICATION_ID_INFERENCE,
                NotificationHelper.inferenceCompleteNotification(context, resultText).build()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "Inference service started")
        val notification = NotificationHelper.inferenceRunningNotification(this).build()
        startForeground(NotificationHelper.NOTIFICATION_ID_INFERENCE, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(TAG, "Inference service stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
