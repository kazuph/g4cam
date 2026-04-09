package com.kazuph.g4cam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_DOWNLOAD = "g4cam_download"
    const val CHANNEL_INFERENCE = "g4cam_inference"
    const val NOTIFICATION_ID_DOWNLOAD = 1001
    const val NOTIFICATION_ID_INFERENCE = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOAD, "モデルダウンロード", NotificationManager.IMPORTANCE_LOW).apply {
                description = "モデルのダウンロード進捗を表示します"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFERENCE, "AI推論", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "AI推論の完了を通知します"
            }
        )
    }

    fun downloadProgressNotification(context: Context, progress: Int, mb: Long, totalMb: Long): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("モデルをダウンロード中")
            .setContentText("${mb}MB / ${totalMb}MB")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(launchIntent(context))
    }

    fun downloadCompleteNotification(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ダウンロード完了")
            .setContentText("モデルの準備ができました。タップして初期化を開始。")
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
    }

    fun downloadErrorNotification(context: Context, message: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("ダウンロードエラー")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
    }

    fun inferenceRunningNotification(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_INFERENCE)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("AI解析中...")
            .setContentText("バックグラウンドで推論を実行しています")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(launchIntent(context))
    }

    fun inferenceCompleteNotification(context: Context, resultText: String): NotificationCompat.Builder {
        val shortText = if (resultText.length > 100) resultText.take(100) + "..." else resultText
        return NotificationCompat.Builder(context, CHANNEL_INFERENCE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("解析完了")
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(resultText))
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
    }

    private fun launchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
