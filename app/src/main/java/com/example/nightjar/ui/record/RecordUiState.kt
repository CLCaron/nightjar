package com.example.nightjar.ui.record

import java.io.File

/** Snapshot of a just-captured recording, held until the user navigates away. */
data class PostRecordingState(
    val ideaId: Long,
    val audioFile: File
)

/** UI state for the Record screen. */
data class RecordUiState(
    val isRecording: Boolean = false,
    val liveAmplitudes: FloatArray = FloatArray(0),
    val postRecording: PostRecordingState? = null,
    val errorMessage: String? = null,
    val isMetronomeEnabled: Boolean = false,
    val metronomeVolume: Float = 0.7f,
    val metronomeBpm: Double = 120.0,
    val countInBars: Int = 0,
    val isCountingIn: Boolean = false,
    val lastBeatFrame: Long = -1L,
    val isMetronomeSettingsOpen: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordUiState) return false
        return isRecording == other.isRecording &&
                liveAmplitudes.contentEquals(other.liveAmplitudes) &&
                postRecording == other.postRecording &&
                errorMessage == other.errorMessage &&
                isMetronomeEnabled == other.isMetronomeEnabled &&
                metronomeVolume == other.metronomeVolume &&
                metronomeBpm == other.metronomeBpm &&
                countInBars == other.countInBars &&
                isCountingIn == other.isCountingIn &&
                lastBeatFrame == other.lastBeatFrame &&
                isMetronomeSettingsOpen == other.isMetronomeSettingsOpen
    }

    override fun hashCode(): Int {
        var result = isRecording.hashCode()
        result = 31 * result + liveAmplitudes.contentHashCode()
        result = 31 * result + (postRecording?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + isMetronomeEnabled.hashCode()
        result = 31 * result + metronomeVolume.hashCode()
        result = 31 * result + metronomeBpm.hashCode()
        result = 31 * result + countInBars.hashCode()
        result = 31 * result + isCountingIn.hashCode()
        result = 31 * result + lastBeatFrame.hashCode()
        result = 31 * result + isMetronomeSettingsOpen.hashCode()
        return result
    }
}

/** User-initiated actions on the Record screen. */
sealed interface RecordAction {
    data object StartRecording : RecordAction
    data object StopAndSave : RecordAction
    /** Gracefully save if the app is backgrounded mid-recording. */
    data object StopForBackground : RecordAction
    /** Navigate to Overview for the captured idea. */
    data object GoToOverview : RecordAction
    /** Navigate to Studio for the captured idea. */
    data object GoToStudio : RecordAction
    /** Create an empty idea and open Overview for notes/title entry. */
    data object CreateWriteIdea : RecordAction
    /** Create an empty idea and open Studio directly. */
    data object CreateStudioIdea : RecordAction
    /** Toggle metronome on/off. */
    data object ToggleMetronome : RecordAction
    /** Set metronome volume (0.0-1.0). */
    data class SetMetronomeVolume(val volume: Float) : RecordAction
    /** Set metronome BPM. */
    data class SetMetronomeBpm(val bpm: Double) : RecordAction
    /** Set count-in bars (0/1/2/4). */
    data class SetCountInBars(val bars: Int) : RecordAction
    /** Toggle metronome settings drawer. */
    data object ToggleMetronomeSettings : RecordAction
    /** Tap tempo. */
    data object TapTempo : RecordAction
}

/** One-shot side effects emitted by [RecordViewModel]. */
sealed interface RecordEffect {
    data class OpenOverview(val ideaId: Long) : RecordEffect
    data class OpenStudio(val ideaId: Long) : RecordEffect
    data class ShowError(val message: String) : RecordEffect
}
