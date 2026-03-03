package com.example.nightjar.audio

/**
 * Musical position expressed as measure, beat, and sub-beat fraction.
 * Both [measure] and [beat] are 1-based (measure 1, beat 1 is the start).
 */
data class MusicalPosition(
    val measure: Int,
    val beat: Int,
    val subBeatFraction: Double = 0.0
) {
    /** Format as "M.B" (e.g. "3.2" for measure 3, beat 2). */
    fun format(): String = "$measure.$beat"
}

/**
 * Pure stateless utility for converting between milliseconds and musical time
 * (measures, beats) accounting for time signature and BPM.
 *
 * All functions are thread-safe (no shared state). Internal storage remains
 * ms-based throughout the app; this converter handles display and snap logic.
 */
object MusicalTimeConverter {

    /**
     * Duration of one beat in milliseconds.
     * Accounts for time signature denominator: a beat in 6/8 is an eighth note,
     * while a beat in 4/4 is a quarter note.
     *
     * Formula: (60_000 / bpm) * (4.0 / denominator)
     */
    fun msPerBeat(bpm: Double, denominator: Int = 4): Double {
        if (bpm <= 0.0 || denominator <= 0) return 0.0
        return (60_000.0 / bpm) * (4.0 / denominator)
    }

    /**
     * Duration of one full measure in milliseconds.
     * A measure of 4/4 at 120 BPM = 4 * 500ms = 2000ms.
     * A measure of 3/4 at 120 BPM = 3 * 500ms = 1500ms.
     * A measure of 6/8 at 120 BPM = 6 * 250ms = 1500ms.
     */
    fun msPerMeasure(bpm: Double, numerator: Int = 4, denominator: Int = 4): Double {
        return msPerBeat(bpm, denominator) * numerator
    }

    /**
     * Convert a millisecond position to a [MusicalPosition] (1-based measure and beat).
     */
    fun msToPosition(
        ms: Long,
        bpm: Double,
        numerator: Int = 4,
        denominator: Int = 4
    ): MusicalPosition {
        val beatMs = msPerBeat(bpm, denominator)
        if (beatMs <= 0.0) return MusicalPosition(1, 1, 0.0)

        val totalBeats = ms.toDouble() / beatMs
        val wholeBeat = totalBeats.toInt()
        val fraction = totalBeats - wholeBeat

        val measure = (wholeBeat / numerator) + 1
        val beat = (wholeBeat % numerator) + 1

        return MusicalPosition(measure, beat, fraction)
    }

    /**
     * Convert a [MusicalPosition] back to milliseconds.
     */
    fun positionToMs(
        position: MusicalPosition,
        bpm: Double,
        numerator: Int = 4,
        denominator: Int = 4
    ): Long {
        val beatMs = msPerBeat(bpm, denominator)
        if (beatMs <= 0.0) return 0L

        val totalBeats = ((position.measure - 1) * numerator) +
                (position.beat - 1) +
                position.subBeatFraction

        return (totalBeats * beatMs).toLong()
    }

    /**
     * Convert milliseconds to a fractional beat count.
     */
    fun msToBeats(ms: Long, bpm: Double, denominator: Int = 4): Double {
        val beatMs = msPerBeat(bpm, denominator)
        if (beatMs <= 0.0) return 0.0
        return ms.toDouble() / beatMs
    }

    /**
     * Snap a millisecond position to the nearest beat boundary.
     */
    fun snapToBeat(ms: Long, bpm: Double, denominator: Int = 4): Long {
        val beatMs = msPerBeat(bpm, denominator)
        if (beatMs <= 0.0) return ms
        return (Math.round(ms.toDouble() / beatMs) * beatMs).toLong()
    }

    /**
     * Snap a millisecond position to the nearest measure boundary.
     */
    fun snapToMeasure(
        ms: Long,
        bpm: Double,
        numerator: Int = 4,
        denominator: Int = 4
    ): Long {
        val measureMs = msPerMeasure(bpm, numerator, denominator)
        if (measureMs <= 0.0) return ms
        return (Math.round(ms.toDouble() / measureMs) * measureMs).toLong()
    }
}
