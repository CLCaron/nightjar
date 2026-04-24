package com.example.nightjar.audio

/**
 * Resolves the effective length of a MIDI clip.
 *
 * `MidiClipEntity.lengthMs` is nullable because the v14 migration is
 * deliberately shallow: existing clips keep NULL until their first explicit
 * length mutation (split, trim, resize). This keeps the migration a single
 * ALTER TABLE — no JOIN onto tracks/ideas required to compute an initial
 * value per row.
 *
 * Legacy fallback (matches the pre-v14 rendering rule):
 * `max(maxNoteEndMs, msPerMeasure(bpm, timeSig))`
 *
 * Call this helper from every read site — rendering, playback schedule
 * building, drag/trim math. Reading `clip.lengthMs` directly will render
 * legacy clips at zero width.
 */
object MidiClipLength {
    fun resolve(
        storedLengthMs: Long?,
        maxNoteEndMs: Long,
        bpm: Double,
        timeSignatureNumerator: Int,
        timeSignatureDenominator: Int
    ): Long {
        storedLengthMs?.let { return it }
        val measureMs = MusicalTimeConverter.msPerMeasure(
            bpm, timeSignatureNumerator, timeSignatureDenominator
        ).toLong()
        return maxOf(maxNoteEndMs, measureMs)
    }
}
