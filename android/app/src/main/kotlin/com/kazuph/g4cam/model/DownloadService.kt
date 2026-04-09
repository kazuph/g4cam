package com.kazuph.g4cam.model

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.kazuph.g4cam.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "DownloadService"
private const val EXTRA_MODEL_ID = "model_id"

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    companion object {
        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        private var isRunning = false

        fun start(context: Context, modelId: ModelId) {
            if (isRunning) return
            val intent =
                Intent(context, DownloadService::class.java).apply {
                    putExtra(EXTRA_MODEL_ID, modelId.name)
                }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val notification = NotificationHelper.downloadProgressNotification(this, 0, 0, 0).build()
        startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val spec = LocalModelSpecs.fromId(intent?.getStringExtra(EXTRA_MODEL_ID))
        if (spec == null) {
            Log.e(TAG, "Unknown model id in service intent")
            stopSelf()
            return START_NOT_STICKY
        }
        startDownload(spec)
        return START_NOT_STICKY
    }

    private fun startDownload(spec: LocalModelSpec) {
        val downloader = ModelDownloader(applicationContext)

        if (downloader.isModelDownloaded(spec)) {
            Log.i(TAG, "Model already downloaded")
            _downloadState.value = DownloadState.Completed(spec.id)
            showCompleteNotification()
            stopSelf()
            return
        }

        downloadJob = scope.launch {
            downloader.download(spec).collect { state ->
                _downloadState.value = state
                when (state) {
                    is DownloadState.Downloading -> {
                        val mb = state.downloadedBytes / 1_000_000
                        val totalMb = if (state.totalBytes > 0) state.totalBytes / 1_000_000 else 0L
                        val progress = if (state.totalBytes > 0) (state.downloadedBytes * 100 / state.totalBytes).toInt() else 0
                        updateProgressNotification(progress, mb, totalMb)
                    }
                    is DownloadState.Completed -> {
                        Log.i(TAG, "Download completed")
                        showCompleteNotification()
                        stopSelf()
                    }
                    is DownloadState.Error -> {
                        Log.e(TAG, "Download error: ${state.message}")
                        showErrorNotification(state.message)
                        stopSelf()
                    }
                    is DownloadState.Idle -> {}
                }
            }
        }
    }

    private fun updateProgressNotification(progress: Int, mb: Long, totalMb: Long) {
        val notification = NotificationHelper.downloadProgressNotification(this, progress, mb, totalMb).build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
    }

    private fun showCompleteNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, NotificationHelper.downloadCompleteNotification(this).build())
    }

    private fun showErrorNotification(message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, NotificationHelper.downloadErrorNotification(this, message).build())
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        downloadJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
