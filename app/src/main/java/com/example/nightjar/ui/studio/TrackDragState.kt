package com.example.nightjar.ui.studio

/** Transient state while the user is long-press-dragging a track to reposition it. */
data class TrackDragState(
    val trackId: Long,
    val originalOffsetMs: Long,
    val previewOffsetMs: Long
)

/** Transient state while the user is dragging a trim handle on a track edge. */
data class TrackTrimState(
    val trackId: Long,
    val edge: TrimEdge,
    val originalTrimStartMs: Long,
    val originalTrimEndMs: Long,
    val previewTrimStartMs: Long,
    val previewTrimEndMs: Long
)

/** Which end of a track is being trimmed. */
enum class TrimEdge { LEFT, RIGHT }
