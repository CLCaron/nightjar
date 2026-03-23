package com.example.nightjar.ui.studio

import com.example.nightjar.data.db.entity.MidiNoteEntity

/**
 * Represents an undoable/redoable operation on MIDI notes in the piano roll.
 *
 * Place and Delete use `var noteIds` because re-inserted notes get new
 * auto-generated IDs from Room.
 */
sealed interface NoteOperation {

    /** A note (or chord) was placed. Undo = delete IDs. Redo = re-insert from snapshots. */
    data class Place(
        var noteIds: List<Long>,
        val snapshots: List<MidiNoteEntity>
    ) : NoteOperation

    /** One or more notes were moved (time + pitch combined into one undo entry). */
    data class MoveBatch(val entries: List<MoveEntry>) : NoteOperation {
        data class MoveEntry(
            val noteId: Long,
            val oldStartMs: Long,   // clip-relative
            val newStartMs: Long,   // clip-relative
            val oldPitch: Int,
            val newPitch: Int
        )
    }

    /** One or more notes were resized. */
    data class ResizeBatch(val entries: List<ResizeEntry>) : NoteOperation {
        data class ResizeEntry(
            val noteId: Long,
            val clipRelativeStartMs: Long,
            val oldDurationMs: Long,
            val newDurationMs: Long
        )
    }

    /** One or more notes were deleted. Undo = re-insert from snapshots. Redo = delete IDs. */
    data class Delete(
        var noteIds: List<Long>,
        val snapshots: List<MidiNoteEntity>
    ) : NoteOperation
}

/**
 * Manages undo/redo stacks for piano roll note operations.
 * Capacity-limited to avoid unbounded memory growth.
 */
class PianoRollUndoManager(private val capacity: Int = 50) {

    private val undoStack = ArrayDeque<NoteOperation>()
    private val redoStack = ArrayDeque<NoteOperation>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Push a new operation onto the undo stack. Clears the redo stack. */
    fun push(op: NoteOperation) {
        redoStack.clear()
        undoStack.addLast(op)
        if (undoStack.size > capacity) undoStack.removeFirst()
    }

    /** Pop the most recent operation for undoing. Moves it to the redo stack. */
    fun popUndo(): NoteOperation? {
        val op = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(op)
        return op
    }

    /** Pop the most recent redo operation. Moves it back to the undo stack. */
    fun popRedo(): NoteOperation? {
        val op = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(op)
        return op
    }
}
