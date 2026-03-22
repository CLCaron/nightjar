package com.example.nightjar.audio

/**
 * Music theory helper for scales and diatonic chords.
 *
 * All scales are defined as semitone intervals from the root (0-11).
 * Diatonic chords are built by stacking every-other scale degree,
 * producing triads, 7th chords, and 9th chords whose quality varies
 * naturally by position in the scale.
 */
object MusicalScaleHelper {

    // ── Scale definitions ────────────────────────────────────────────

    enum class ScaleType(
        val displayName: String,
        val group: ScaleGroup,
        val intervals: IntArray
    ) {
        // Everyday
        MAJOR("Major", ScaleGroup.EVERYDAY, intArrayOf(0, 2, 4, 5, 7, 9, 11)),
        NATURAL_MINOR("Natural Minor", ScaleGroup.EVERYDAY, intArrayOf(0, 2, 3, 5, 7, 8, 10)),
        MINOR_PENTATONIC("Minor Penta", ScaleGroup.EVERYDAY, intArrayOf(0, 3, 5, 7, 10)),
        MAJOR_PENTATONIC("Major Penta", ScaleGroup.EVERYDAY, intArrayOf(0, 2, 4, 7, 9)),
        BLUES("Blues", ScaleGroup.EVERYDAY, intArrayOf(0, 3, 5, 6, 7, 10)),

        // Modes
        DORIAN("Dorian", ScaleGroup.MODES, intArrayOf(0, 2, 3, 5, 7, 9, 10)),
        PHRYGIAN("Phrygian", ScaleGroup.MODES, intArrayOf(0, 1, 3, 5, 7, 8, 10)),
        LYDIAN("Lydian", ScaleGroup.MODES, intArrayOf(0, 2, 4, 6, 7, 9, 11)),
        MIXOLYDIAN("Mixolydian", ScaleGroup.MODES, intArrayOf(0, 2, 4, 5, 7, 9, 10)),
        LOCRIAN("Locrian", ScaleGroup.MODES, intArrayOf(0, 1, 3, 5, 6, 8, 10)),

        // Extended
        HARMONIC_MINOR("Harm Minor", ScaleGroup.EXTENDED, intArrayOf(0, 2, 3, 5, 7, 8, 11)),
        MELODIC_MINOR("Melod Minor", ScaleGroup.EXTENDED, intArrayOf(0, 2, 3, 5, 7, 9, 11)),
        FLAMENCO("Flamenco", ScaleGroup.EXTENDED, intArrayOf(0, 1, 4, 5, 7, 8, 11)),
        HUNGARIAN("Hungarian", ScaleGroup.EXTENDED, intArrayOf(0, 2, 3, 6, 7, 8, 11)),
        ROMANIAN("Romanian", ScaleGroup.EXTENDED, intArrayOf(0, 2, 3, 6, 7, 9, 10)),
        PERSIAN("Persian", ScaleGroup.EXTENDED, intArrayOf(0, 1, 4, 5, 6, 8, 11)),
        BEBOP("Bebop", ScaleGroup.EXTENDED, intArrayOf(0, 2, 4, 5, 7, 9, 10, 11)),
        WHOLE_TONE("Whole Tone", ScaleGroup.EXTENDED, intArrayOf(0, 2, 4, 6, 8, 10)),
        CHROMATIC("Chromatic", ScaleGroup.EXTENDED,
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

        companion object {
            fun fromName(name: String): ScaleType =
                entries.firstOrNull { it.name == name } ?: MAJOR
        }
    }

    enum class ScaleGroup(val displayName: String) {
        EVERYDAY("Everyday"),
        MODES("Modes"),
        EXTENDED("Extended")
    }

    // ── Chord definitions ────────────────────────────────────────────

    enum class ChordType(val displayName: String) {
        TRIAD("Triad"),
        SEVENTH("7th"),
        NINTH("9th")
    }

    data class ChordInfo(
        val degree: Int,            // 1-based scale degree
        val romanNumeral: String,   // "I", "ii", "IV", "vii°"
        val name: String,           // "C", "Dm", "Bdim"
        val pitches: List<Int>      // MIDI pitches (rooted at octave 4)
    )

    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    // ── Scale queries ────────────────────────────────────────────────

    /** True if [pitch] (MIDI 0-127) belongs to the scale rooted at [root] (0-11). */
    fun isInScale(pitch: Int, root: Int, scale: ScaleType): Boolean {
        val offset = ((pitch % 12) - root + 12) % 12
        return offset in scale.intervals
    }

    /** True if [pitch] is a root note (any octave) for root [root] (0-11). */
    fun isRoot(pitch: Int, root: Int): Boolean = (pitch % 12) == root

    /**
     * Returns which scale degree (0-based index into [ScaleType.intervals])
     * the given [pitch] occupies, or -1 if not in scale.
     */
    fun scaleDegreeIndex(pitch: Int, root: Int, scale: ScaleType): Int {
        val offset = ((pitch % 12) - root + 12) % 12
        return scale.intervals.indexOf(offset)
    }

    // ── Chord generation ─────────────────────────────────────────────

    /**
     * Build a diatonic chord rooted on [rootPitch] within the given scale.
     *
     * Returns a list of MIDI pitches. If [rootPitch] is not in scale,
     * returns a single-element list containing just [rootPitch].
     *
     * Chords are built by stacking every-other scale degree:
     * - Triad: root + 3rd + 5th
     * - 7th:   root + 3rd + 5th + 7th
     * - 9th:   root + 3rd + 5th + 7th + 9th (2nd + octave)
     */
    fun getChordPitches(
        rootPitch: Int,
        scaleRoot: Int,
        scale: ScaleType,
        chordType: ChordType
    ): List<Int> {
        val degreeIndex = scaleDegreeIndex(rootPitch, scaleRoot, scale)
        if (degreeIndex == -1) return listOf(rootPitch)

        val intervals = scale.intervals
        val n = intervals.size
        val result = mutableListOf(rootPitch)

        // 3rd = 2 scale steps above root
        result.add(rootPitch + intervalAbove(intervals, degreeIndex, 2, n))
        // 5th = 4 scale steps above root
        result.add(rootPitch + intervalAbove(intervals, degreeIndex, 4, n))

        if (chordType == ChordType.SEVENTH || chordType == ChordType.NINTH) {
            // 7th = 6 scale steps above root
            result.add(rootPitch + intervalAbove(intervals, degreeIndex, 6, n))
        }
        if (chordType == ChordType.NINTH) {
            // 9th = 1 scale step above root + octave
            result.add(rootPitch + intervalAbove(intervals, degreeIndex, 1, n) + 12)
        }

        // Clamp to valid MIDI range
        return result.filter { it in 0..127 }
    }

    /**
     * Get all diatonic chords for a scale, useful for the reference strip.
     * Returns one [ChordInfo] per scale degree, rooted at octave 4 (MIDI 60+).
     */
    fun getDiatonicChords(
        scaleRoot: Int,
        scale: ScaleType,
        chordType: ChordType
    ): List<ChordInfo> {
        if (scale == ScaleType.CHROMATIC) return emptyList()

        val intervals = scale.intervals
        val n = intervals.size
        val baseOctavePitch = 60 + scaleRoot // C4 + root offset

        return List(n) { degreeIndex ->
            val rootPitch = baseOctavePitch + intervals[degreeIndex]
            val pitches = getChordPitches(rootPitch, scaleRoot, scale, chordType)

            // Compute intervals from root for quality detection
            val semitonesFromRoot = pitches.drop(1).map { it - rootPitch }
            val quality = detectChordQuality(semitonesFromRoot, chordType)
            val rootName = NOTE_NAMES[(scaleRoot + intervals[degreeIndex]) % 12]

            ChordInfo(
                degree = degreeIndex + 1,
                romanNumeral = romanNumeral(degreeIndex + 1, quality),
                name = "$rootName${qualitySuffix(quality)}",
                pitches = pitches
            )
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /**
     * Semitone interval from scale degree [fromIndex] to the degree
     * [steps] positions higher in the scale. Wraps around octave boundaries.
     */
    private fun intervalAbove(intervals: IntArray, fromIndex: Int, steps: Int, n: Int): Int {
        val toIndex = (fromIndex + steps) % n
        val fromSemitone = intervals[fromIndex]
        val toSemitone = intervals[toIndex]
        val diff = toSemitone - fromSemitone
        return if (diff > 0) diff else diff + 12
    }

    private enum class ChordQuality {
        MAJOR, MINOR, DIMINISHED, AUGMENTED,
        MAJ7, DOM7, MIN7, MIN_MAJ7, HALF_DIM7, DIM7, AUG_MAJ7,
        MAJ9, DOM9, MIN9, // 9th variants
        OTHER
    }

    private fun detectChordQuality(semitonesFromRoot: List<Int>, chordType: ChordType): ChordQuality {
        if (semitonesFromRoot.size < 2) return ChordQuality.OTHER
        val third = semitonesFromRoot[0]
        val fifth = semitonesFromRoot[1]

        val isMaj3 = third == 4
        val isMin3 = third == 3
        val isPerf5 = fifth == 7
        val isDim5 = fifth == 6
        val isAug5 = fifth == 8

        if (chordType == ChordType.TRIAD) {
            return when {
                isMaj3 && isPerf5 -> ChordQuality.MAJOR
                isMin3 && isPerf5 -> ChordQuality.MINOR
                isMin3 && isDim5 -> ChordQuality.DIMINISHED
                isMaj3 && isAug5 -> ChordQuality.AUGMENTED
                else -> ChordQuality.OTHER
            }
        }

        if (semitonesFromRoot.size < 3) return ChordQuality.OTHER
        val seventh = semitonesFromRoot[2]

        val base7 = when {
            isMaj3 && isPerf5 && seventh == 11 -> ChordQuality.MAJ7
            isMaj3 && isPerf5 && seventh == 10 -> ChordQuality.DOM7
            isMin3 && isPerf5 && seventh == 10 -> ChordQuality.MIN7
            isMin3 && isPerf5 && seventh == 11 -> ChordQuality.MIN_MAJ7
            isMin3 && isDim5 && seventh == 10 -> ChordQuality.HALF_DIM7
            isMin3 && isDim5 && seventh == 9 -> ChordQuality.DIM7
            isMaj3 && isAug5 && seventh == 11 -> ChordQuality.AUG_MAJ7
            else -> ChordQuality.OTHER
        }

        if (chordType == ChordType.NINTH && base7 != ChordQuality.OTHER) {
            return when (base7) {
                ChordQuality.MAJ7 -> ChordQuality.MAJ9
                ChordQuality.DOM7 -> ChordQuality.DOM9
                ChordQuality.MIN7 -> ChordQuality.MIN9
                else -> base7 // Fall back to 7th name
            }
        }
        return base7
    }

    private fun romanNumeral(degree: Int, quality: ChordQuality): String {
        val numeral = when (degree) {
            1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"
            5 -> "V"; 6 -> "VI"; 7 -> "VII"; 8 -> "VIII"
            else -> "$degree"
        }
        val isLower = quality in setOf(
            ChordQuality.MINOR, ChordQuality.DIMINISHED,
            ChordQuality.MIN7, ChordQuality.MIN_MAJ7,
            ChordQuality.HALF_DIM7, ChordQuality.DIM7, ChordQuality.MIN9
        )
        val base = if (isLower) numeral.lowercase() else numeral
        val suffix = when (quality) {
            ChordQuality.DIMINISHED, ChordQuality.DIM7 -> "\u00B0"  // °
            ChordQuality.HALF_DIM7 -> "\u00F8"                       // ø
            ChordQuality.AUGMENTED, ChordQuality.AUG_MAJ7 -> "+"
            else -> ""
        }
        return "$base$suffix"
    }

    private fun qualitySuffix(quality: ChordQuality): String = when (quality) {
        ChordQuality.MAJOR -> ""
        ChordQuality.MINOR -> "m"
        ChordQuality.DIMINISHED -> "dim"
        ChordQuality.AUGMENTED -> "aug"
        ChordQuality.MAJ7 -> "maj7"
        ChordQuality.DOM7 -> "7"
        ChordQuality.MIN7 -> "m7"
        ChordQuality.MIN_MAJ7 -> "mMaj7"
        ChordQuality.HALF_DIM7 -> "m7b5"
        ChordQuality.DIM7 -> "dim7"
        ChordQuality.AUG_MAJ7 -> "augMaj7"
        ChordQuality.MAJ9 -> "maj9"
        ChordQuality.DOM9 -> "9"
        ChordQuality.MIN9 -> "m9"
        ChordQuality.OTHER -> ""
    }
}
