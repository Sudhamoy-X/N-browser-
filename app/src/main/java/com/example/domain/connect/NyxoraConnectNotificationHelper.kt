package com.example.domain.connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Manages the Android system notification for NYXORA Connect (Browse Together / Watch Together).
 *
 * Guarantees:
 * - Reflects real, server-confirmed connection state (`Connecting`, `Connected`, `Reconnecting`, `Disconnected`).
 * - Immediately cancels any active notification when the session transitions to `DISCONNECTED`,
 *   when reconnection retries are exhausted, or when the user leaves/closes the room.
 * - Never leaves a stale "Browse Together • Reconnecting" notification in the status bar.
 */
object NyxoraConnectNotificationHelper {

    const val CHANNEL_ID = "nyxora_connect_session_channel"
    const val NOTIFICATION_ID = 42088

    fun syncNotificationState(
        context: Context,
        roomState: NyxoraConnectRoomState?,
        connectionStatus: SyncConnectionStatus
    ) {
        val appContext = context.applicationContext
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Always cancel the notification if there is no active room or if the session is DISCONNECTED
        if (roomState == null || connectionStatus == SyncConnectionStatus.DISCONNECTED) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                return
            }
        }

        ensureChannel(notificationManager)

        val statusSummary = when (connectionStatus) {
            SyncConnectionStatus.CONNECTING -> "Connecting to room ${roomState.roomId}…"
            SyncConnectionStatus.CONNECTED,
            SyncConnectionStatus.SYNCED -> "Connected • ${roomState.members.size} device(s) in ${roomState.roomId}"
            SyncConnectionStatus.SYNCHRONIZING -> "Synchronizing room ${roomState.roomId}…"
            SyncConnectionStatus.RECONNECTING -> "Reconnecting to ${roomState.roomId}…"
            SyncConnectionStatus.PAUSED_FOLLOWING -> "Sync paused • ${roomState.roomId}"
            SyncConnectionStatus.DISCONNECTED -> {
                notificationManager.cancel(NOTIFICATION_ID)
                return
            }
        }

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPendingIntent = PendingIntent.getActivity(appContext, 0, launchIntent, pendingFlags)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Room Sync • ${connectionStatus.label}")
            .setContentText(statusSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusSummary))
            .setSubText(roomState.roomId)
            .setOngoing(
                connectionStatus == SyncConnectionStatus.CONNECTED ||
                    connectionStatus == SyncConnectionStatus.SYNCED ||
                    connectionStatus == SyncConnectionStatus.SYNCHRONIZING
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentPendingIntent)
            .build()

        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancelNotification(context: Context) {
        val appContext = context.applicationContext
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "NYXORA Connect Synchronization",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows real-time connection status for Browse Together and Watch Together rooms"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
