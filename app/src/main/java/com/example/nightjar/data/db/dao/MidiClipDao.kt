package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.MidiClipEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for [MidiClipEntity] -- MIDI clip CRUD and observation. */
@Dao
interface MidiClipDao {

    @Insert
    suspend fun insertClip(clip: MidiClipEntity): Long

    @Query("SELECT * FROM midi_clips WHERE trackId = :trackId ORDER BY sortIndex, offsetMs")
    suspend fun getClipsForTrack(trackId: Long): List<MidiClipEntity>

    @Query("SELECT * FROM midi_clips WHERE trackId = :trackId ORDER BY sortIndex, offsetMs")
    fun observeClipsForTrack(trackId: Long): Flow<List<MidiClipEntity>>

    @Query("SELECT * FROM midi_clips WHERE id = :clipId")
    suspend fun getClipById(clipId: Long): MidiClipEntity?

    @Query("UPDATE midi_clips SET offsetMs = :offsetMs WHERE id = :clipId")
    suspend fun updateClipOffset(clipId: Long, offsetMs: Long)

    @Query("UPDATE midi_clips SET lengthMs = :lengthMs WHERE id = :clipId")
    suspend fun updateClipLength(clipId: Long, lengthMs: Long?)

    @Query("DELETE FROM midi_clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("SELECT MAX(sortIndex) FROM midi_clips WHERE trackId = :trackId")
    suspend fun getMaxSortIndex(trackId: Long): Int?

    @Query("SELECT COUNT(*) FROM midi_clips WHERE trackId = :trackId")
    suspend fun getClipCount(trackId: Long): Int

    // -- Linked-clip queries --

    /** All instances (non-source clips) that point to [sourceId]. */
    @Query("SELECT * FROM midi_clips WHERE sourceClipId = :sourceId ORDER BY id ASC")
    suspend fun getInstancesOf(sourceId: Long): List<MidiClipEntity>

    /** Every member of a linked group: source plus all instances. */
    @Query("""
        SELECT * FROM midi_clips
        WHERE id = (SELECT COALESCE(sourceClipId, id) FROM midi_clips WHERE id = :clipId)
           OR sourceClipId = (SELECT COALESCE(sourceClipId, id) FROM midi_clips WHERE id = :clipId)
        ORDER BY id ASC
    """)
    suspend fun findSiblingGroup(clipId: Long): List<MidiClipEntity>

    /** Count of members in a clip's linked group (1 if standalone). */
    @Query("""
        SELECT COUNT(*) FROM midi_clips
        WHERE id = (SELECT COALESCE(sourceClipId, id) FROM midi_clips WHERE id = :clipId)
           OR sourceClipId = (SELECT COALESCE(sourceClipId, id) FROM midi_clips WHERE id = :clipId)
    """)
    suspend fun getGroupSize(clipId: Long): Int

    /** Resolve a clip id to its source's id. */
    @Query("SELECT COALESCE(sourceClipId, id) FROM midi_clips WHERE id = :clipId")
    suspend fun resolveSourceId(clipId: Long): Long?

    @Query("UPDATE midi_clips SET sourceClipId = :sourceId WHERE id = :clipId")
    suspend fun updateSourceClipId(clipId: Long, sourceId: Long?)

    @Query("UPDATE midi_clips SET sourceClipId = :newSourceId WHERE sourceClipId = :oldSourceId")
    suspend fun rewriteSourceClipId(oldSourceId: Long, newSourceId: Long)
}
