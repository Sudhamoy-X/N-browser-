package com.example.domain.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 10–14+ compliant ForegroundService (`foregroundServiceType="mediaProjection"`)
 * required before starting a `MediaProjection` screen capture session in NYXORA Connect.
 *
 * Guarantees:
 * - Starts foreground with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` on API 29+.
 * - Displays a clear, ongoing system notification while Live Mirror Mode is active, with a
 *   direct "Stop Live Mirror" action button.
 * - Immediately cancels the notification and stops foreground when Live Mirror Mode ends,
 *   when the Host revokes screen capture, or when the room closes.
 */
class NyxoraLiveMirrorForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_LIVE_MIRROR
        if (action == ACTION_STOP_LIVE_MIRROR) {
            isRunning.set(false)
            onUserRequestedStopListener?.invoke()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty().ifBlank { "NYXORA Room" }
        val qualityLabel = intent?.getStringExtra(EXTRA_QUALITY_LABEL).orEmpty().ifBlank { "720p Smooth" }
        val isAudioShared = intent?.getBooleanExtra(EXTRA_AUDIO_SHARED, true) ?: true

        val notification = buildMirrorNotification(
            context = this,
            roomId = roomId,
            qualityLabel = qualityLabel,
            isAudioShared = isAudioShared
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    MIRROR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(MIRROR_NOTIFICATION_ID, notification)
            }
            isRunning.set(true)
            onForegroundReadyListener?.invoke()
        }.onFailure {
            isRunning.set(false)
            onForegroundStartFailedListener?.invoke(
                it.message ?: "Unable to start MediaProjection foreground service"
            )
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(MIRROR_NOTIFICATION_ID)
    }

    companion object {
        const val MIRROR_CHANNEL_ID = "nyxora_live_mirror_channel"
        const val MIRROR_NOTIFICATION_ID = 42089

        const val ACTION_START_LIVE_MIRROR = "com.example.domain.connect.action.START_LIVE_MIRROR"
        const val ACTION_STOP_LIVE_MIRROR = "com.example.domain.connect.action.STOP_LIVE_MIRROR"

        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_QUALITY_LABEL = "extra_quality_label"
        const val EXTRA_AUDIO_SHARED = "extra_audio_shared"

        private val isRunning = AtomicBoolean(false)

        @Volatile
        var onForegroundReadyListener: (() -> Unit)? = null

        @Volatile
        var onForegroundStartFailedListener: ((String) -> Unit)? = null

        @Volatile
        var onUserRequestedStopListener: (() -> Unit)? = null

        val isForegroundServiceActive: Boolean
            get() = isRunning.get()

        fun startMirrorService(
            context: Context,
            roomId: String,
            qualityLabel: String,
            isAudioShared: Boolean = true,
            onReady: (() -> Unit)? = null,
            onFailed: ((String) -> Unit)? = null
        ) {
            val appContext = context.applicationContext
            onForegroundReadyListener = onReady
            onForegroundStartFailedListener = onFailed
            val intent = Intent(appContext, NyxoraLiveMirrorForegroundService::class.java).apply {
                action = ACTION_START_LIVE_MIRROR
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_QUALITY_LABEL, qualityLabel)
                putExtra(EXTRA_AUDIO_SHARED, isAudioShared)
            }
            runCatching {
                isRunning.set(true)
                ContextCompat.startForegroundService(appContext, intent)
            }.onFailure { err ->
                isRunning.set(false)
                onFailed?.invoke(err.message ?: "Failed to launch Live Mirror foreground service")
            }
        }

        fun stopMirrorService(context: Context) {
            val appContext = context.applicationContext
            isRunning.set(false)
            onForegroundReadyListener = null
            onForegroundStartFailedListener = null
            runCatching {
                val stopIntent = Intent(appContext, NyxoraLiveMirrorForegroundService::class.java)
                appContext.stopService(stopIntent)
            }
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(MIRROR_NOTIFICATION_ID)
        }

        internal fun buildMirrorNotification(
            context: Context,
            roomId: String,
            qualityLabel: String,
            isAudioShared: Boolean
        ): Notification {
            val appContext = context.applicationContext
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(MIRROR_CHANNEL_ID)
                if (existing == null) {
                    val channel = NotificationChannel(
                        MIRROR_CHANNEL_ID,
                        "NYXORA Live Screen Mirroring",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Active while sharing your browser screen in NYXORA Connect Live Mirror mode"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }

            val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val contentPendingIntent = PendingIntent.getActivity(appContext, 101, launchIntent, pendingFlags)

            val stopIntent = Intent(appContext, NyxoraLiveMirrorForegroundService::class.java).apply {
                action = ACTION_STOP_LIVE_MIRROR
            }
            val stopPendingIntent = PendingIntent.getService(appContext, 102, stopIntent, pendingFlags)

            val audioStatus = if (isAudioShared) "Audio On" else "Audio Muted"
            val summary = "Sharing screen in $roomId ($qualityLabel • $audioStatus)"

            return NotificationCompat.Builder(appContext, MIRROR_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("NYXORA Live Mirror • Active")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setSubText(roomId)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(contentPendingIntent)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "Stop Live Mirror",
                    stopPendingIntent
                )
                .build()
        }
    }
}
