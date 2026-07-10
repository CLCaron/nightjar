package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single track within a Studio multi-track project.
 *
 * Supports three track types:
 * - **"audio"** (default): WAV audio recorded via Oboe. Has a non-null [audioFileName].
 * - **"drum"**: Drum sequencer track. [audioFileName] is null; pattern data lives
 *   in [DrumPatternEntity] + [DrumStepEntity].
 * - **"midi"**: MIDI instrument track. [audioFileName] is null; note data lives
 *   in [MidiNoteEntity]. Uses [midiProgram] and [midiChannel] for FluidSynth playback.
 *
 * Track 1 is created automatically when the user first opens an idea in
 * Studio mode (promoted from the original [IdeaEntity] recording).
 * Additional tracks are added via overdub recording or the add-track sheet.
 * Deleting the parent idea cascades to all its tracks.
 *
 * @property ideaId        Foreign key to the parent [IdeaEntity].
 * @property trackType     Track type: "audio", "drum", or "midi".
 * @property audioFileName Filename of the audio file (WAV) in the recordings directory.
 *                         Null for drum and MIDI tracks.
 * @property trackRole     Lightweight audio role label. V1 uses it for defaults and labels only.
 * @property displayName   User-visible label shown in the timeline header (e.g. "Track 1").
 * @property sortIndex     Vertical ordering in the timeline (0 = topmost).
 * @property offsetMs      Horizontal offset on the timeline.
 * @property trimStartMs   Milliseconds trimmed from the start of the audio (non-destructive).
 * @property trimEndMs     Milliseconds trimmed from the end of the audio (non-destructive).
 * @property durationMs    Total duration of the underlying audio file (before trim).
 *                         For drum tracks, computed from pattern length and BPM.
 *                         For MIDI tracks, computed from max(startMs + durationMs) of notes.
 * @property isMuted       When true, this track is silenced during playback.
 * @property volume        Playback volume multiplier (0.0-1.0).
 * @property midiProgram   General MIDI program number (0-127) for MIDI tracks.
 * @property midiChannel   MIDI channel (0-15) for FluidSynth routing. Channel 9 = drums (reserved).
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = IdeaEntity::class,
        parentColumns = ["id"],
        childColumns = ["ideaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ideaId")]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ideaId: Long,
    val trackType: String = "audio",
    val audioFileName: String? = null,
    val trackRole: String = TrackRole.RAW,
    val displayName: String,
    val sortIndex: Int,
    val offsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val durationMs: Long,
    val isMuted: Boolean = false,
    val volume: Float = 1.0f,
    val midiProgram: Int = 0,
    val midiChannel: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val isAudio: Boolean get() = trackType == "audio"
    val isDrum: Boolean get() = trackType == "drum"
    val isMidi: Boolean get() = trackType == "midi"
}

/** Lightweight audio-track roles used for labels/default names in v1. */
object TrackRole {
    const val RAW = "raw"
    const val BACKING = "backing"
    const val VOCAL = "vocal"
    const val HARMONY = "harmony"
    const val SAMPLE = "sample"

    val all: List<String> = listOf(RAW, BACKING, VOCAL, HARMONY, SAMPLE)

    fun normalize(role: String): String =
        role.lowercase().takeIf { it in all } ?: RAW

    fun label(role: String): String = when (normalize(role)) {
        BACKING -> "Backing"
        VOCAL -> "Vocal"
        HARMONY -> "Harmony"
        SAMPLE -> "Sample"
        else -> "Raw"
    }
}
