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
import android.util.Log
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

    /** Loop region boundaries — null when no loop is set. */
    private var loopStartMs: Long? = null
    private var loopEndMs: Long? = null

    /** Nanosecond timestamp when [play] was last called. */
    private var clockStartNanos: Long = 0L

    /** The global position (ms) captured at the moment [play] started the clock. */
    private var clockBaseMs: Long = 0L

    /** False until we sync the clock to ExoPlayer's actual position after startup. */
    private var clockSynced = false

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

    /** Set the loop region boundaries. */
    fun setLoopRegion(startMs: Long, endMs: Long) {
        loopStartMs = startMs
        loopEndMs = endMs
    }

    /** Clear the loop region — playback will no longer loop. */
    fun clearLoopRegion() {
        loopStartMs = null
        loopEndMs = null
    }

    /**
     * Suspends until at least one non-muted ExoPlayer slot reports
     * [ExoPlayer.isPlaying] == true, meaning audio is actually being
     * rendered to the speaker.
     *
     * Returns a [RenderingResult] with the global position and whether
     * rendering was confirmed. When no slot would start immediately at
     * the current clock position (e.g., all tracks have offsetMs > playhead),
     * returns early with [RenderingResult.renderingConfirmed] = false instead
     * of waiting for the full timeout — this fixes the ~2s offset bug.
     */
    suspend fun awaitPlaybackRendering(timeoutMs: Long = 2_000L): RenderingResult {
        val hasEligibleSlot = slots.any { !it.track.isMuted }
        if (!hasEligibleSlot) {
            return RenderingResult(_globalPositionMs.value, renderingConfirmed = false)
        }

        // Check if any non-muted slot would be actively playing right now.
        // If all tracks start later (offsetMs > clockBaseMs), no ExoPlayer
        // will report isPlaying — waiting would just burn the full timeout.
        val hasImmediateSlot = slots.any { slot ->
            if (slot.track.isMuted) return@any false
            val localMs = clockBaseMs - slot.track.offsetMs
            localMs in 0 until slot.effectiveDuration
        }
        if (!hasImmediateSlot) {
            Log.d(TAG, "awaitPlaybackRendering: no immediate slot at ${clockBaseMs}ms, skipping wait")
            return RenderingResult(clockBaseMs, renderingConfirmed = false)
        }

        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (slots.any { !it.track.isMuted && it.player.isPlaying }) {
                // Reset the monotonic clock to eliminate ExoPlayer startup
                // latency from the timeline position. Without this, the clock
                // advances during the rendering delay, causing overdub tracks
                // to be offset from their intended start position.
                clockStartNanos = System.nanoTime()
                _globalPositionMs.value = clockBaseMs
                return RenderingResult(clockBaseMs, renderingConfirmed = true)
            }
            delay(5L)
        }
        // Timed out — return best-effort position.
        Log.w(TAG, "awaitPlaybackRendering: timed out after ${timeoutMs}ms")
        return RenderingResult(_globalPositionMs.value, renderingConfirmed = false)
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

        // If we were at the end, restart from loop start (if looping) or 0.
        val startMs = when {
            globalMs >= _totalDurationMs.value && loopStartMs != null -> loopStartMs!!
            globalMs >= _totalDurationMs.value -> 0L
            else -> globalMs
        }

        clockBaseMs = startMs
        clockStartNanos = System.nanoTime()
        clockSynced = false
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
            clockSynced = false
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

                // Loop check — jump back before the end-of-timeline check
                val lEnd = loopEndMs
                val lStart = loopStartMs
                if (lEnd != null && lStart != null && pos >= lEnd) {
                    performLoopSeek(lStart)
                    _globalPositionMs.value = lStart
                    delay(16L)
                    continue
                }

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

                delay(16L) // ~60 fps playhead refresh
            }
        }
    }

    /**
     * Seek all players to [globalMs] without stopping playback.
     * Resets the monotonic clock and repositions every non-muted slot
     * so the loop transition is tight (~16ms max latency).
     */
    private fun performLoopSeek(globalMs: Long) {
        cancelDelayedJobs()
        clockBaseMs = globalMs
        clockStartNanos = System.nanoTime()
        clockSynced = false

        for (slot in slots) {
            if (slot.track.isMuted) continue
            val localMs = globalMs - slot.track.offsetMs
            when {
                localMs >= slot.effectiveDuration -> slot.player.pause()
                localMs >= 0 -> {
                    slot.player.seekTo(localMs)
                    slot.player.play()
                }
                else -> {
                    slot.player.pause()
                    slot.player.seekTo(0L)
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
    }

    private fun currentClockMs(): Long {
        // One-time sync: once ExoPlayer confirms it's rendering audio,
        // correct the clock base to eliminate startup buffering delay.
        // After this point the monotonic clock tracks real time accurately.
        if (!clockSynced) {
            val activeSlot = slots.firstOrNull { !it.track.isMuted && it.player.isPlaying }
            if (activeSlot != null) {
                clockBaseMs = activeSlot.track.offsetMs + activeSlot.player.currentPosition
                clockStartNanos = System.nanoTime()
                clockSynced = true
            }
        }

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

    companion object {
        private const val TAG = "StudioPlaybackMgr"
    }
}

/** Result of [StudioPlaybackManager.awaitPlaybackRendering]. */
data class RenderingResult(
    val globalPositionMs: Long,
    val renderingConfirmed: Boolean
)
