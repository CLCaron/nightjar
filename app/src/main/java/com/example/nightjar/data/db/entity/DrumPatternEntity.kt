package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A drum pattern belonging to a drum [TrackEntity].
 *
 * Each drum track can have multiple patterns (one per clip). The pattern
 * defines the grid dimensions (steps per bar, number of bars). Individual
 * steps are stored in [DrumStepEntity].
 *
 * BPM is NOT stored here -- it lives on [IdeaEntity.bpm] (project-level).
 * This future-proofs for metronome, snap-to-grid, and tempo-synced effects.
 *
 * @property trackId     Foreign key to the parent drum [TrackEntity].
 * @property stepsPerBar Number of step subdivisions per bar (default 16 = sixteenth notes).
 * @property bars        Deprecated. Kept in schema for the v14 transition;
 *                       runtime reads `lengthSteps` instead. Removed in a
 *                       later cleanup migration.
 * @property lengthSteps Authoritative pattern length in step units. Drives
 *                       timeline width and playback ceiling. Allows sub-bar
 *                       and non-bar-aligned clips (what `bars` could not).
 */
@Entity(
    tableName = "drum_patterns",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("trackId")]
)
data class DrumPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val stepsPerBar: Int = 16,
    val bars: Int = 1,
    val lengthSteps: Int = 16
)
