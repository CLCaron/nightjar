package com.example.nightjar.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.nightjar.data.db.entity.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Manages multi-track playback for the Studio screen.
 *
 * One [ExoPlayer] per track, coordinated by a monotonic clock
 * ([System.nanoTime]). Each track's trim is handled via
 * [ClippingMediaSource] so the player's internal position maps
 * directly to the audible region.
 *
 * Call [setScope] before [prepare] to bind coroutine work to
 * the owning ViewModel's lifecycle.
 */
class StudioPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private lateinit var scope: CoroutineScope

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _globalPositionMs = MutableStateFlow(0L)
    val globalPositionMs: StateFlow<Long> = _globalPositionMs

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs

    private var slots: List<PlayerSlot> = emptyList()
    private var delayedStartJobs: MutableList<Job> = mutableListOf()
    private var tickJob: Job? = null

    /** True while the ViewModel is recording an overdub. */
    private var isRecording = false

    /** Nanosecond timestamp when [play] was last called. */
    private var clockStartNanos: Long = 0L

    /** The global position (ms) captured at the moment [play] started the clock. */
    private var clockBaseMs: Long = 0L

    // ── Public API ───────────────────────────────────────────────────────

    /** Bind this manager to a [CoroutineScope] (typically viewModelScope). */
    fun setScope(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Inform the playback manager whether an overdub recording is active.
     * When recording, the playhead continues past [totalDurationMs] so the
     * user can keep capturing audio beyond existing tracks.
     */
    fun setRecording(active: Boolean) {
        isRecording = active
    }

    /**
     * Suspends until at least one non-muted ExoPlayer slot reports
     * [ExoPlayer.isPlaying] == true, meaning audio is actually being
     * rendered to the speaker. Returns the global playback position at
     * that moment.
     *
     * Falls through immediately if no eligible slots exist, or after
     * [timeoutMs] to avoid hanging indefinitely.
     */
    suspend fun awaitPlaybackRendering(timeoutMs: Long = 2_000L): Long {
        val hasEligibleSlot = slots.any { !it.track.isMuted }
        if (!hasEligibleSlot) return _globalPositionMs.value

        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (slots.any { !it.track.isMuted && it.player.isPlaying }) {
                return _globalPositionMs.value
            }
            delay(5L)
        }
        // Timed out — return best-effort position.
        return _globalPositionMs.value
    }

    /**
     * Prepare players for the given [tracks]. Call once after loading
     * the project, or again if the track list changes.
     */
    fun prepare(tracks: List<TrackEntity>, getAudioFile: (String) -> File) {
        cancelDelayedJobs()
        tickJob?.cancel()
        tickJob = null
        releaseSlots()

        val mediaSourceFactory = DefaultMediaSourceFactory(context)

        slots = tracks.map { track ->
            val file = getAudioFile(track.audioFileName)
            val player = ExoPlayer.Builder(context).build()

            val effectiveDuration = track.durationMs - track.trimStartMs - track.trimEndMs
            val mediaItem = MediaItem.fromUri(file.toURI().toString())
            val baseSource = mediaSourceFactory.createMediaSource(mediaItem)

            val clippedSource = ClippingMediaSource(
                baseSource,
                track.trimStartMs * 1_000L,                       // startUs
                (track.durationMs - track.trimEndMs) * 1_000L     // endUs
            )

            player.setMediaSource(clippedSource)
            player.volume = track.volume
            player.prepare()
            player.playWhenReady = false

            PlayerSlot(
                player = player,
                track = track,
                effectiveDuration = effectiveDuration.coerceAtLeast(0L),
                audioFile = file
            )
        }

        _totalDurationMs.value = slots.maxOfOrNull { it.track.offsetMs + it.effectiveDuration } ?: 0L
        _globalPositionMs.value = 0L
        _isPlaying.value = false
    }

    fun play() {
        if (slots.isEmpty()) return

        val globalMs = _globalPositionMs.value.coerceIn(0L, _totalDurationMs.value)

        // If we were at the end, restart from 0.
        val startMs = if (globalMs >= _totalDurationMs.value) 0L else globalMs

        clockBaseMs = startMs
        clockStartNanos = System.nanoTime()
        _isPlaying.value = true
        _globalPositionMs.value = startMs

        cancelDelayedJobs()

        for (slot in slots) {
            if (slot.track.isMuted) continue
            startSlotForGlobalPosition(slot, startMs)
        }

        startTick()
    }

    fun pause() {
        if (!_isPlaying.value) return

        cancelDelayedJobs()
        tickJob?.cancel()
        tickJob = null

        // Snapshot position from clock before pausing.
        _globalPositionMs.value = currentClockMs()
        _isPlaying.value = false

        for (slot in slots) {
            slot.player.pause()
        }
    }

    fun seekTo(globalMs: Long) {
        val clamped = globalMs.coerceIn(0L, _totalDurationMs.value)
        _globalPositionMs.value = clamped

        cancelDelayedJobs()

        val wasPlaying = _isPlaying.value

        for (slot in slots) {
            val localMs = clamped - slot.track.offsetMs
            if (localMs in 0 until slot.effectiveDuration) {
                slot.player.seekTo(localMs)
                if (!wasPlaying) slot.player.pause()
            } else {
                slot.player.pause()
                if (localMs < 0) {
                    slot.player.seekTo(0L)
                } else {
                    slot.player.seekTo(slot.effectiveDuration.coerceAtLeast(0L))
                }
            }
        }

        if (wasPlaying) {
            clockBaseMs = clamped
            clockStartNanos = System.nanoTime()
            for (slot in slots) {
                if (slot.track.isMuted) continue
                startSlotForGlobalPosition(slot, clamped)
            }
        }
    }

    fun release() {
        cancelDelayedJobs()
        tickJob?.cancel()
        tickJob = null
        releaseSlots()
        _isPlaying.value = false
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun startSlotForGlobalPosition(slot: PlayerSlot, globalMs: Long) {
        val localMs = globalMs - slot.track.offsetMs
        when {
            localMs >= slot.effectiveDuration -> {
                // Track already finished — do nothing.
            }
            localMs >= 0 -> {
                slot.player.seekTo(localMs)
                slot.player.play()
            }
            else -> {
                // Track hasn't started yet — schedule a delayed start.
                val delayMs = -localMs
                val job = scope.launch {
                    delay(delayMs)
                    if (_isPlaying.value) {
                        slot.player.seekTo(0L)
                        slot.player.play()
                    }
                }
                delayedStartJobs.add(job)
            }
        }
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (_isPlaying.value) {
                val pos = currentClockMs()
                _globalPositionMs.value = pos

                if (pos >= _totalDurationMs.value) {
                    if (isRecording) {
                        // Recording is active — let the playhead keep advancing
                        // past existing tracks. Individual ExoPlayers have
                        // naturally finished; the clock drives the playhead.
                    } else {
                        // Normal playback ended — reset to 0 and stop.
                        _globalPositionMs.value = 0L
                        _isPlaying.value = false
                        cancelDelayedJobs()
                        for (slot in slots) {
                            slot.player.pause()
                        }
                        break
                    }
                }

                delay(32L) // ~30 fps playhead refresh
            }
        }
    }

    private fun currentClockMs(): Long {
        val elapsed = (System.nanoTime() - clockStartNanos) / 1_000_000L
        val raw = clockBaseMs + elapsed
        // When recording, allow the clock to advance past existing tracks.
        return if (isRecording) raw.coerceAtLeast(0L)
               else raw.coerceIn(0L, _totalDurationMs.value)
    }

    private fun cancelDelayedJobs() {
        delayedStartJobs.forEach { it.cancel() }
        delayedStartJobs.clear()
    }

    private fun releaseSlots() {
        for (slot in slots) {
            slot.player.release()
        }
        slots = emptyList()
    }

    private data class PlayerSlot(
        val player: ExoPlayer,
        val track: TrackEntity,
        val effectiveDuration: Long,
        val audioFile: File
    )
}
