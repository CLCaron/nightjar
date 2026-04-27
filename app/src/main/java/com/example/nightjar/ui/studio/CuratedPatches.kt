package com.example.nightjar.ui.studio

/**
 * Tabs for the on-brand instrument picker. Eight mega-categories from the
 * mockup plus an OTHER bucket for ethnic / SFX patches that don't fit elsewhere.
 *
 * Bucketing rationale:
 *  - KEYS  = pianos, electric pianos, organs, mallet/chromatic perc
 *  - STR   = orchestral strings, guitars, ensembles, voices
 *  - BRASS = brass, sax, woodwinds, pipes (anything you blow into)
 *  - BASS  = acoustic, electric, synth bass
 *  - PAD   = synth pads
 *  - LEAD  = synth leads
 *  - DRUM  = melodic percussion only (kits live in the drum-track flow)
 *  - FX    = synth atmospheres and effects pads
 *  - OTHER = ethnic instruments + novelty SFX
 */
enum class PatchCategory(val label: String) {
    KEYS("KEYS"),
    STR("STR"),
    BRASS("BRASS"),
    BASS("BASS"),
    PAD("PAD"),
    LEAD("LEAD"),
    DRUM("DRUM"),
    FX("FX"),
    OTHER("OTHER")
}

/**
 * A GM program wrapped with hardware-style branding for the patch grid.
 * [position] is 1-based within [category] and drives the "001" card label.
 */
data class CuratedPatch(
    val program: Int,
    val category: PatchCategory,
    val code: String,
    val descriptor: String,
    val position: Int
)

/** All 128 GM programs, hand-curated. Codes capped at ~7 chars to fit the card. */
val CURATED_PATCHES: List<CuratedPatch> = listOf(
    // KEYS
    CuratedPatch(  0, PatchCategory.KEYS,  "GRAND",   "Acoustic",        1),
    CuratedPatch(  1, PatchCategory.KEYS,  "UPRGT",   "Mellow",          2),
    CuratedPatch(  4, PatchCategory.KEYS,  "RHODES",  "Warm electric",   3),
    CuratedPatch(  5, PatchCategory.KEYS,  "WURLI",   "Bright electric", 4),
    CuratedPatch(  7, PatchCategory.KEYS,  "CLAVI",   "Funky",           5),
    CuratedPatch(  8, PatchCategory.KEYS,  "CELESTE", "Soft bell",       6),
    CuratedPatch(  6, PatchCategory.KEYS,  "HARPS",   "Plucked",         7),
    CuratedPatch( 16, PatchCategory.KEYS,  "ORGAN",   "Jazz organ",      8),
    CuratedPatch( 18, PatchCategory.KEYS,  "ROCK",    "Distorted organ", 9),
    CuratedPatch( 19, PatchCategory.KEYS,  "CHRCH",   "Pipe organ",     10),
    CuratedPatch( 21, PatchCategory.KEYS,  "ACCRD",   "Bellows",        11),
    CuratedPatch(  3, PatchCategory.KEYS,  "HONKY",   "Saloon upright", 12),
    CuratedPatch(  2, PatchCategory.KEYS,  "EGRAND",  "Stage piano",    13),
    CuratedPatch( 17, PatchCategory.KEYS,  "CLICK",   "Perc organ",     14),
    CuratedPatch( 20, PatchCategory.KEYS,  "REED",    "Soft pipe",      15),
    CuratedPatch( 22, PatchCategory.KEYS,  "HARMO",   "Blues harp",     16),
    CuratedPatch( 23, PatchCategory.KEYS,  "TANGO",   "Bandoneon",      17),
    CuratedPatch( 11, PatchCategory.KEYS,  "VIBES",   "Soft mallets",   18),
    CuratedPatch( 12, PatchCategory.KEYS,  "MARIM",   "Wood mallets",   19),
    CuratedPatch(  9, PatchCategory.KEYS,  "GLOCK",   "Bright bell",    20),
    CuratedPatch( 13, PatchCategory.KEYS,  "XYLO",    "Hard mallets",   21),
    CuratedPatch( 14, PatchCategory.KEYS,  "BELLS",   "Tubular",        22),
    CuratedPatch( 10, PatchCategory.KEYS,  "BOX",     "Music box",      23),
    CuratedPatch( 15, PatchCategory.KEYS,  "DULC",    "Hammered",       24),

    // STR
    CuratedPatch( 40, PatchCategory.STR,   "VIOLIN",  "High bowed",      1),
    CuratedPatch( 42, PatchCategory.STR,   "CELLO",   "Low bowed",       2),
    CuratedPatch( 24, PatchCategory.STR,   "NYLON",   "Classical gtr",   3),
    CuratedPatch( 25, PatchCategory.STR,   "STEEL",   "Acoustic gtr",    4),
    CuratedPatch( 27, PatchCategory.STR,   "CLEAN",   "Clean tone",      5),
    CuratedPatch( 26, PatchCategory.STR,   "JAZZ",    "Jazz tone",       6),
    CuratedPatch( 29, PatchCategory.STR,   "DRIVE",   "Overdrive",       7),
    CuratedPatch( 30, PatchCategory.STR,   "DIST",    "Distorted",       8),
    CuratedPatch( 28, PatchCategory.STR,   "MUTE",    "Palm mute",       9),
    CuratedPatch( 31, PatchCategory.STR,   "HARM",    "Harmonics",      10),
    CuratedPatch( 48, PatchCategory.STR,   "ENS 1",   "Section",        11),
    CuratedPatch( 49, PatchCategory.STR,   "ENS 2",   "Lush hall",      12),
    CuratedPatch( 45, PatchCategory.STR,   "PIZZ",    "Plucked",        13),
    CuratedPatch( 44, PatchCategory.STR,   "TREM",    "Bowed trem",     14),
    CuratedPatch( 41, PatchCategory.STR,   "VIOLA",   "Mid bowed",      15),
    CuratedPatch( 43, PatchCategory.STR,   "CONTRA",  "Lowest bowed",   16),
    CuratedPatch( 46, PatchCategory.STR,   "HARP",    "Concert",        17),
    CuratedPatch( 50, PatchCategory.STR,   "SYN STR", "Bowed pad",      18),
    CuratedPatch( 51, PatchCategory.STR,   "STR PAD", "Soft synth",     19),
    CuratedPatch( 52, PatchCategory.STR,   "CHOIR",   "Aahs",           20),
    CuratedPatch( 53, PatchCategory.STR,   "VOICE",   "Oohs",           21),
    CuratedPatch( 54, PatchCategory.STR,   "SYN VOX", "Choir pad",      22),
    CuratedPatch( 55, PatchCategory.STR,   "HIT",     "Orchestra stab", 23),

    // BRASS
    CuratedPatch( 56, PatchCategory.BRASS, "TRMPT",   "Solo bright",     1),
    CuratedPatch( 57, PatchCategory.BRASS, "TRMBN",   "Slide brass",     2),
    CuratedPatch( 65, PatchCategory.BRASS, "ALTO",    "Alto sax",        3),
    CuratedPatch( 60, PatchCategory.BRASS, "FRENCH",  "French horn",     4),
    CuratedPatch( 61, PatchCategory.BRASS, "SECTION", "Brass section",   5),
    CuratedPatch( 58, PatchCategory.BRASS, "TUBA",    "Low brass",       6),
    CuratedPatch( 59, PatchCategory.BRASS, "MUTED",   "Muted trumpet",   7),
    CuratedPatch( 64, PatchCategory.BRASS, "SOPRAN",  "Soprano sax",     8),
    CuratedPatch( 66, PatchCategory.BRASS, "TENOR",   "Tenor sax",       9),
    CuratedPatch( 67, PatchCategory.BRASS, "BARI",    "Baritone sax",   10),
    CuratedPatch( 62, PatchCategory.BRASS, "SYN BRS", "Synth brass",    11),
    CuratedPatch( 63, PatchCategory.BRASS, "BRS PAD", "Soft brass",     12),
    CuratedPatch( 73, PatchCategory.BRASS, "FLUTE",   "Concert flute",  13),
    CuratedPatch( 72, PatchCategory.BRASS, "PICC",    "High flute",     14),
    CuratedPatch( 71, PatchCategory.BRASS, "CLRNT",   "Clarinet",       15),
    CuratedPatch( 68, PatchCategory.BRASS, "OBOE",    "Solo reed",      16),
    CuratedPatch( 69, PatchCategory.BRASS, "ENG HRN", "Mellow reed",    17),
    CuratedPatch( 70, PatchCategory.BRASS, "BSSON",   "Low reed",       18),
    CuratedPatch( 74, PatchCategory.BRASS, "RECRD",   "Wooden flute",   19),
    CuratedPatch( 75, PatchCategory.BRASS, "PAN",     "Pan flute",      20),
    CuratedPatch( 77, PatchCategory.BRASS, "SHAKU",   "Bamboo flute",   21),
    CuratedPatch( 78, PatchCategory.BRASS, "WHIST",   "Whistle",        22),
    CuratedPatch( 79, PatchCategory.BRASS, "OCRNA",   "Folk flute",     23),
    CuratedPatch( 76, PatchCategory.BRASS, "BOTTLE",  "Blown",          24),

    // BASS
    CuratedPatch( 33, PatchCategory.BASS,  "PBASS",   "Finger style",    1),
    CuratedPatch( 32, PatchCategory.BASS,  "STAND",   "Acoustic upright", 2),
    CuratedPatch( 34, PatchCategory.BASS,  "PICK",    "Pick attack",     3),
    CuratedPatch( 35, PatchCategory.BASS,  "FRTLS",   "Fretless",        4),
    CuratedPatch( 36, PatchCategory.BASS,  "SLAP",    "Slap attack",     5),
    CuratedPatch( 37, PatchCategory.BASS,  "POP",     "Slap pop",        6),
    CuratedPatch( 38, PatchCategory.BASS,  "MOOG",    "Square wave",     7),
    CuratedPatch( 39, PatchCategory.BASS,  "SUB",     "Saw wave",        8),

    // PAD
    CuratedPatch( 89, PatchCategory.PAD,   "WARM",    "Soft pad",        1),
    CuratedPatch( 88, PatchCategory.PAD,   "NEW AGE", "Crystal",         2),
    CuratedPatch( 90, PatchCategory.PAD,   "POLY",    "Polysynth",       3),
    CuratedPatch( 91, PatchCategory.PAD,   "VOX",     "Choir pad",       4),
    CuratedPatch( 92, PatchCategory.PAD,   "BOWED",   "Bowed pad",       5),
    CuratedPatch( 93, PatchCategory.PAD,   "METAL",   "Metallic",        6),
    CuratedPatch( 94, PatchCategory.PAD,   "HALO",    "Halo",            7),
    CuratedPatch( 95, PatchCategory.PAD,   "SWEEP",   "Sweeping",        8),

    // LEAD
    CuratedPatch( 80, PatchCategory.LEAD,  "SQUARE",  "Square wave",     1),
    CuratedPatch( 81, PatchCategory.LEAD,  "SAW",     "Sawtooth",        2),
    CuratedPatch( 82, PatchCategory.LEAD,  "CALL",    "Calliope",        3),
    CuratedPatch( 83, PatchCategory.LEAD,  "CHIFF",   "Soft chiff",      4),
    CuratedPatch( 84, PatchCategory.LEAD,  "CHARNG",  "Chorus lead",     5),
    CuratedPatch( 85, PatchCategory.LEAD,  "VOWEL",   "Voice lead",      6),
    CuratedPatch( 86, PatchCategory.LEAD,  "FIFTH",   "Fifths",          7),
    CuratedPatch( 87, PatchCategory.LEAD,  "BASS LD", "Bass + lead",     8),

    // DRUM — melodic percussion (drum kits handled by drum tracks)
    CuratedPatch(116, PatchCategory.DRUM,  "TAIKO",   "Big drum",        1),
    CuratedPatch( 47, PatchCategory.DRUM,  "TIMP",    "Concert drum",    2),
    CuratedPatch(114, PatchCategory.DRUM,  "PANS",    "Steel drums",     3),
    CuratedPatch(117, PatchCategory.DRUM,  "TOM",     "Melodic tom",     4),
    CuratedPatch(118, PatchCategory.DRUM,  "SYNDRM",  "Synth drum",      5),
    CuratedPatch(115, PatchCategory.DRUM,  "WOOD",    "Block",           6),
    CuratedPatch(113, PatchCategory.DRUM,  "AGOGO",   "Latin bell",      7),
    CuratedPatch(112, PatchCategory.DRUM,  "TINK",    "Tinkle bell",     8),
    CuratedPatch(119, PatchCategory.DRUM,  "RVRS",    "Reverse cym",     9),

    // FX — synth atmospheres and effects pads
    CuratedPatch( 96, PatchCategory.FX,    "RAIN",    "Rain pad",        1),
    CuratedPatch( 97, PatchCategory.FX,    "SOUND",   "Soundtrack",      2),
    CuratedPatch( 98, PatchCategory.FX,    "CRYST",   "Crystal",         3),
    CuratedPatch( 99, PatchCategory.FX,    "ATMOS",   "Atmosphere",      4),
    CuratedPatch(100, PatchCategory.FX,    "BRIGHT",  "Bright pad",      5),
    CuratedPatch(101, PatchCategory.FX,    "GOBLIN",  "Goblin pad",      6),
    CuratedPatch(102, PatchCategory.FX,    "ECHOES",  "Echo drops",      7),
    CuratedPatch(103, PatchCategory.FX,    "SCIFI",   "Sci-fi pad",      8),

    // OTHER — ethnic, novelty, sound effects
    CuratedPatch(104, PatchCategory.OTHER, "SITAR",   "Indian",          1),
    CuratedPatch(105, PatchCategory.OTHER, "BANJO",   "Bluegrass",       2),
    CuratedPatch(106, PatchCategory.OTHER, "SHAMI",   "Japanese",        3),
    CuratedPatch(107, PatchCategory.OTHER, "KOTO",    "Japanese harp",   4),
    CuratedPatch(108, PatchCategory.OTHER, "KALIM",   "Thumb piano",     5),
    CuratedPatch(109, PatchCategory.OTHER, "BAGPIPE", "Highland",        6),
    CuratedPatch(110, PatchCategory.OTHER, "FIDDLE",  "Folk",            7),
    CuratedPatch(111, PatchCategory.OTHER, "SHANAI",  "Indian reed",     8),
    CuratedPatch(120, PatchCategory.OTHER, "FRET",    "Guitar slide",    9),
    CuratedPatch(121, PatchCategory.OTHER, "BREATH",  "Air",            10),
    CuratedPatch(122, PatchCategory.OTHER, "SHORE",   "Seashore",       11),
    CuratedPatch(123, PatchCategory.OTHER, "BIRD",    "Tweet",          12),
    CuratedPatch(124, PatchCategory.OTHER, "PHONE",   "Ring",           13),
    CuratedPatch(125, PatchCategory.OTHER, "HELI",    "Helicopter",     14),
    CuratedPatch(126, PatchCategory.OTHER, "APPL",    "Applause",       15),
    CuratedPatch(127, PatchCategory.OTHER, "GUN",     "Gunshot",        16)
)

/** Look up a curated patch by GM program number. */
fun curatedPatchFor(program: Int): CuratedPatch? =
    CURATED_PATCHES.firstOrNull { it.program == program }

/** All curated patches in [category], ordered by position. */
fun curatedPatchesIn(category: PatchCategory): List<CuratedPatch> =
    CURATED_PATCHES.filter { it.category == category }.sortedBy { it.position }
