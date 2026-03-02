package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single track within a Studio multi-track project.
 *
 * Supports two track types:
 * - **"audio"** (default): WAV audio recorded via Oboe. Has a non-null [audioFileName].
 * - **"drum"**: Drum sequencer track. [audioFileName] is null; pattern data lives
 *   in [DrumPatternEntity] + [DrumStepEntity].
 *
 * Track 1 is created automatically when the user first opens an idea in
 * Studio mode (promoted from the original [IdeaEntity] recording).
 * Additional tracks are added via overdub recording or the add-track sheet.
 * Deleting the parent idea cascades to all its tracks.
 *
 * @property ideaId        Foreign key to the parent [IdeaEntity].
 * @property trackType     Track type: "audio" or "drum".
 * @property audioFileName Filename of the audio file (WAV) in the recordings directory.
 *                         Null for drum tracks.
 * @property displayName   User-visible label shown in the timeline header (e.g. "Track 1").
 * @property sortIndex     Vertical ordering in the timeline (0 = topmost).
 * @property offsetMs      Horizontal offset on the timeline.
 * @property trimStartMs   Milliseconds trimmed from the start of the audio (non-destructive).
 * @property trimEndMs     Milliseconds trimmed from the end of the audio (non-destructive).
 * @property durationMs    Total duration of the underlying audio file (before trim).
 *                         For drum tracks, computed from pattern length and BPM.
 * @property isMuted       When true, this track is silenced during playback.
 * @property volume        Playback volume multiplier (0.0-1.0).
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
    val displayName: String,
    val sortIndex: Int,
    val offsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val durationMs: Long,
    val isMuted: Boolean = false,
    val volume: Float = 1.0f,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val isAudio: Boolean get() = trackType == "audio"
    val isDrum: Boolean get() = trackType == "drum"
}
