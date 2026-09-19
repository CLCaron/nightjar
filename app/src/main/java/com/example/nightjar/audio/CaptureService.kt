package com.example.nightjar.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.net.Uri
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.nightjar.MainActivity
import com.example.nightjar.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** No sticky restart: a killed recording must never silently re-open the microphone. */
@AndroidEntryPoint
class CaptureService : Service() {
    @Inject lateinit var session: CaptureSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var token: String? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    // Capture has no arbitrary duration limit; release on stop, failure, and destruction.
    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedToken = intent?.getStringExtra(TOKEN)
        if (requestedToken == null || requestedToken != session.state.value.token) {
            if (!session.state.value.busy) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent.action == STOP) {
            session.stop()
            if (!session.state.value.busy) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent.action != START || !session.state.value.recording) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        token = requestedToken
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL,
                    getString(R.string.capture_notification_channel), NotificationManager.IMPORTANCE_LOW))
            }
            val notification = notification(requestedToken, session.state.value.phase, session.state.value.countingIn)
            ServiceCompat.startForeground(this, NOTIFICATION, notification,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nightjar:Capture")
                    .apply { setReferenceCounted(false); acquire() }
            }
            session.foregroundReady(requestedToken)
            if (!observing) {
                observing = true
                scope.launch {
                    session.state.map { Triple(it.token, it.phase, it.countingIn) }
                        .distinctUntilChanged().collect { (currentToken, phase, countingIn) ->
                        val busy = phase == CapturePhase.STARTING || phase == CapturePhase.RECORDING || phase == CapturePhase.SAVING
                        if (!busy) {
                            releaseWakeLock()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else if (currentToken == token) {
                            // Permission denial hides the notification but must not stop capture.
                            if ((Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED) &&
                                androidx.core.app.NotificationManagerCompat.from(this@CaptureService).areNotificationsEnabled()) {
                                manager.notify(NOTIFICATION, notification(currentToken, phase, countingIn))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CaptureService", "Cannot enter microphone foreground service", e)
            session.foregroundFailed(requestedToken, "Could not keep recording active. Please return to Nightjar and try again.")
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(token: String, phase: CapturePhase, countingIn: Boolean): Notification {
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CaptureService::class.java).setAction(STOP).setData(Uri.parse("nightjar://capture/$token")).putExtra(TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording_notification)
            .setContentTitle(getString(when {
                phase == CapturePhase.SAVING -> R.string.capture_notification_saving
                countingIn -> R.string.capture_notification_count_in
                phase == CapturePhase.STARTING -> R.string.capture_notification_starting
                else -> R.string.capture_notification_title
            }))
            .setContentText(getString(if (phase == CapturePhase.SAVING) R.string.capture_notification_finishing else R.string.capture_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_recording_notification, getString(R.string.capture_stop), stop)
            .build()
    }

    override fun onDestroy() {
        // The session owns saving; canceling the service observer cannot cancel that save.
        if (token == session.state.value.token && session.state.value.recording) {
            session.stop("Recording was interrupted. Any captured audio has been kept.")
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val START = "com.example.nightjar.capture.START"
        const val STOP = "com.example.nightjar.capture.STOP"
        const val TOKEN = "capture_token"
        const val OPEN = "com.example.nightjar.capture.OPEN"
        private const val CHANNEL = "active_capture"
        private const val NOTIFICATION = 1001
    }
}
