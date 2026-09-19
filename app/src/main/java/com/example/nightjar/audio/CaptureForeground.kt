package com.example.nightjar.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Android service launch stays outside the ViewModel and capture state machine. */
@Singleton
class CaptureForeground @Inject constructor(@ApplicationContext private val context: Context) {
    fun start(token: String) {
        ContextCompat.startForegroundService(context,
            Intent(context, CaptureService::class.java)
                .setAction(CaptureService.START)
                .putExtra(CaptureService.TOKEN, token))
    }
}
