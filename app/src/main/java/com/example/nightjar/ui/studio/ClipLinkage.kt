package com.example.nightjar.ui.studio

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocals for linkage data + sibling-pulse triggers. Provided at
 * the Timeline root so any nested clip composable can read them without
 * threading them through every intermediate function.
 */
val LocalAudioClipLinkage = staticCompositionLocalOf<Map<Long, ClipLinkage.Audio>> { emptyMap() }
val LocalMidiClipLinkage = staticCompositionLocalOf<Map<Long, ClipLinkage.Midi>> { emptyMap() }
val LocalDrumClipLinkage = staticCompositionLocalOf<Map<Long, ClipLinkage.Drum>> { emptyMap() }
val LocalPulseTicks = staticCompositionLocalOf<Map<GroupKey, Long>> { emptyMap() }

/** Split-mode state, provided from StudioScreen. */
data class SplitModeUiState(
    val clipId: Long? = null,
    val positionMs: Long? = null,
    val valid: Boolean = false
)
val LocalSplitMode = staticCompositionLocalOf { SplitModeUiState() }

/**
 * Unified clip-linkage abstraction covering all three clip types.
 *
 * Hides the audio/MIDI (`sourceClipId`) vs drum (shared `patternId`)
 * asymmetry behind a single equality-stable [groupKey]. UI code reads
 * [groupKey] + [isLinked] only; it never branches on the clip type.
 */
sealed class ClipLinkage {
    abstract val groupKey: GroupKey
    abstract val groupSize: Int

    /** True when at least one sibling exists (i.e. group size >= 2). */
    val isLinked: Boolean get() = groupSize >= 2

    data class Audio(
        override val groupKey: GroupKey.Audio,
        override val groupSize: Int
    ) : ClipLinkage()

    data class Midi(
        override val groupKey: GroupKey.Midi,
        override val groupSize: Int
    ) : ClipLinkage()

    data class Drum(
        override val groupKey: GroupKey.Drum,
        override val groupSize: Int
    ) : ClipLinkage()
}

/**
 * Stable identity for a linked-clip group. Siblings share the same key.
 *
 * Audio and MIDI instance clips resolve to their source's id. Drum clips
 * use their shared [patternId] directly -- drums don't carry a
 * `sourceClipId` column because shared-pattern semantics already exist.
 */
sealed class GroupKey {
    data class Audio(val sourceId: Long) : GroupKey()
    data class Midi(val sourceId: Long) : GroupKey()
    data class Drum(val patternId: Long) : GroupKey()
}
