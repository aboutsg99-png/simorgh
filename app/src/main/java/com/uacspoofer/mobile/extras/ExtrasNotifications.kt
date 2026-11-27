package com.uacspoofer.mobile.extras

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.ui.MainActivity

/** Notifications for the 1.0.1 extras. Kept separate from the VPN service channel. */
internal object ExtrasNotifications {
    private const val CHANNEL_ALERTS = "simorgh_extras_alerts"
    const val ID_CAP_80 = 41080
    const val ID_CAP_100 = 41100
    const val ID_WIFI_CONNECT = 41200

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ALERTS) != null) return
        val channel = NotificationChannel(
            CHANNEL_ALERTS,
            "هشدارهای بلک دی",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "هشدار حجم ماهانه و اتصال خودکار در وای‌فای ناامن"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Opens the app; when [connect] is true the app starts the connection (same path as the Quick Settings tile). */
    private fun openAppIntent(context: Context, requestCode: Int, connect: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (connect) intent.putExtra(MainActivity.EXTRA_CONNECT_FROM_QUICK_TILE, true)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun post(context: Context, id: Int, title: String, body: String, connectOnTap: Boolean = false) {
        if (!canPost(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(context, id, connectOnTap))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }
}
