package com.example.nightjar.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Keeps Studio's existing recording pipeline alive while its screen is stopped. */
@Singleton
class StudioRecordingForeground @Inject constructor(@ApplicationContext private val context: Context) {
    enum class Phase { STARTING, RECORDING, SAVING }

    private data class Session(
        val token: String,
        val ready: CompletableDeferred<Unit>,
        val onStop: () -> Unit,
        var phase: Phase = Phase.STARTING
    )

    private var session: Session? = null

    suspend fun start(onStop: () -> Unit): String {
        check(session == null) { "Studio recording is already active" }
        val next = Session(UUID.randomUUID().toString(), CompletableDeferred(), onStop)
        session = next
        try {
            ContextCompat.startForegroundService(context, Intent(context, StudioRecordingService::class.java)
                .setAction(StudioRecordingService.START).putExtra(StudioRecordingService.TOKEN, next.token))
            withTimeout(5_000L) { next.ready.await() }
            return next.token
        } catch (e: Exception) {
            finish(next.token)
            throw e
        }
    }

    fun phase(token: String): Phase? = session?.takeIf { it.token == token }?.phase

    fun update(token: String, phase: Phase) {
        val current = session?.takeIf { it.token == token } ?: return
        current.phase = phase
        try {
            context.startService(Intent(context, StudioRecordingService::class.java)
                .setAction(StudioRecordingService.UPDATE).putExtra(StudioRecordingService.TOKEN, token))
        } catch (e: Exception) {
            // A notification refresh cannot be allowed to interrupt audio finalization.
            Log.w("StudioRecordingForeground", "Could not update recording notification", e)
        }
    }

    fun ready(token: String) {
        session?.takeIf { it.token == token }?.ready?.complete(Unit)
    }

    fun failed(token: String, cause: Exception) {
        session?.takeIf { it.token == token }?.ready?.completeExceptionally(cause)
    }

    fun requestStop(token: String) {
        session?.takeIf { it.token == token }?.onStop?.invoke()
    }

    fun finish(token: String) {
        if (session?.token != token) return
        session = null
        context.stopService(Intent(context, StudioRecordingService::class.java))
    }
}
