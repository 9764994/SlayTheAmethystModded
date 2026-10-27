package io.stamethyst.backend.easytier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.backend.notify.ForegroundNotificationIds

/** Shared notification surface while foreground-service ownership moves to the VPN. */
internal object EasyTierForegroundNotification {
    const val CHANNEL_ID = "easytier_virtual_network"
    const val CONNECT_NOTIFICATION_ID = ForegroundNotificationIds.EASY_TIER_CONNECT
    const val VPN_NOTIFICATION_ID = ForegroundNotificationIds.EASY_TIER_VPN

    fun build(context: Context, message: String): Notification {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.main_easytier_notification_title))
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notify(context: Context, notificationId: Int, message: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, build(context, message))
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.main_easytier_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }
}
