package com.example.nightjar.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.nightjar.MainActivity
import com.example.nightjar.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class StudioRecordingService : Service() {
    @Inject lateinit var foreground: StudioRecordingForeground
    private var token: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(TOKEN)
        val phase = requested?.let(foreground::phase)
        if (requested == null || phase == null) {
            if (token == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent.action == STOP) {
            foreground.requestStop(requested)
            return START_NOT_STICKY
        }
        if (intent.action == UPDATE) {
            if (requested == token &&
                (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION, notification(requested, phase))
            }
            return START_NOT_STICKY
        }
        if (intent.action != START) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.capture_notification_channel),
                    NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, NOTIFICATION, notification(requested, phase),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            token = requested
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nightjar:StudioRecording")
                .apply { setReferenceCounted(false); acquire() }
            foreground.ready(requested)
        } catch (e: Exception) {
            Log.e("StudioRecordingService", "Cannot start Studio foreground recording", e)
            foreground.failed(requested, e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(token: String, phase: StudioRecordingForeground.Phase): Notification {
        val stop = PendingIntent.getService(this, 2,
            Intent(this, StudioRecordingService::class.java).setAction(STOP)
                .setData(Uri.parse("nightjar://studio-recording/$token")).putExtra(TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording_notification)
            .setContentTitle(getString(when (phase) {
                StudioRecordingForeground.Phase.STARTING -> R.string.capture_notification_starting
                StudioRecordingForeground.Phase.RECORDING -> R.string.studio_notification_recording
                StudioRecordingForeground.Phase.SAVING -> R.string.capture_notification_saving
            }))
            .setContentText(getString(if (phase == StudioRecordingForeground.Phase.SAVING)
                R.string.capture_notification_finishing else R.string.capture_notification_text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_recording_notification, getString(R.string.capture_stop), stop)
            .build()
    }

    override fun onDestroy() {
        token?.let { foreground.requestStop(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val START = "com.example.nightjar.studio.START"
        const val STOP = "com.example.nightjar.studio.STOP"
        const val UPDATE = "com.example.nightjar.studio.UPDATE"
        const val TOKEN = "studio_recording_token"
        private const val CHANNEL = "active_studio_recording"
        private const val NOTIFICATION = 1002
    }
}
