package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single active step in a [DrumPatternEntity].
 *
 * Only active (toggled-on) steps are stored -- absence of a row means
 * the step is silent. This is more efficient than storing every cell
 * in the grid, since most patterns are sparse.
 *
 * Uses General MIDI drum map (channel 10, note numbers):
 * | Note | Instrument    |
 * |------|---------------|
 * | 36   | Kick          |
 * | 38   | Snare         |
 * | 42   | Closed Hi-Hat |
 * | 46   | Open Hi-Hat   |
 * | 45   | Low Tom       |
 * | 48   | Mid Tom       |
 * | 50   | High Tom      |
 * | 49   | Crash         |
 * | 51   | Ride          |
 * | 39   | Clap          |
 *
 * @property patternId Foreign key to the parent [DrumPatternEntity].
 * @property stepIndex Zero-based position in the pattern grid (0..stepsPerBar*bars-1).
 * @property drumNote  GM drum note number (e.g. 36 = kick, 38 = snare).
 * @property velocity  Hit velocity (0.0-1.0), mapped to MIDI velocity 0-127.
 */
@Entity(
    tableName = "drum_steps",
    foreignKeys = [ForeignKey(
        entity = DrumPatternEntity::class,
        parentColumns = ["id"],
        childColumns = ["patternId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("patternId")]
)
data class DrumStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val patternId: Long,
    val stepIndex: Int,
    val drumNote: Int,
    val velocity: Float = 0.8f
)
