package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.AudioClipEntity

/** Data access object for [AudioClipEntity] -- audio clip CRUD. */
@Dao
interface AudioClipDao {

    @Insert
    suspend fun insertClip(clip: AudioClipEntity): Long

    @Query("SELECT * FROM audio_clips WHERE trackId = :trackId ORDER BY sortIndex, offsetMs")
    suspend fun getClipsForTrack(trackId: Long): List<AudioClipEntity>

    @Query("SELECT * FROM audio_clips WHERE trackId IN (:trackIds) ORDER BY trackId, sortIndex, offsetMs")
    suspend fun getClipsForTracks(trackIds: List<Long>): List<AudioClipEntity>

    @Query("SELECT * FROM audio_clips WHERE id = :clipId")
    suspend fun getClipById(clipId: Long): AudioClipEntity?

    @Query("UPDATE audio_clips SET offsetMs = :offsetMs WHERE id = :clipId")
    suspend fun updateClipOffset(clipId: Long, offsetMs: Long)

    @Query("UPDATE audio_clips SET displayName = :name WHERE id = :clipId")
    suspend fun updateDisplayName(clipId: Long, name: String)

    @Query("UPDATE audio_clips SET isMuted = :muted WHERE id = :clipId")
    suspend fun updateMuted(clipId: Long, muted: Boolean)

    @Query("DELETE FROM audio_clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("SELECT MAX(sortIndex) FROM audio_clips WHERE trackId = :trackId")
    suspend fun getMaxSortIndex(trackId: Long): Int?

    @Query("SELECT COUNT(*) FROM audio_clips WHERE trackId = :trackId")
    suspend fun getClipCount(trackId: Long): Int

    // -- Linked-clip queries --

    /** All instances (non-source clips) that point to [sourceId]. */
    @Query("SELECT * FROM audio_clips WHERE sourceClipId = :sourceId ORDER BY createdAtEpochMs ASC")
    suspend fun getInstancesOf(sourceId: Long): List<AudioClipEntity>

    /**
     * Every member of a linked group: the source itself plus all its
     * instances. Input may be either a source or an instance id.
     */
    @Query("""
        SELECT * FROM audio_clips
        WHERE id = (SELECT COALESCE(sourceClipId, id) FROM audio_clips WHERE id = :clipId)
           OR sourceClipId = (SELECT COALESCE(sourceClipId, id) FROM audio_clips WHERE id = :clipId)
        ORDER BY createdAtEpochMs ASC
    """)
    suspend fun findSiblingGroup(clipId: Long): List<AudioClipEntity>

    /** Count of members in a clip's linked group (1 if standalone). */
    @Query("""
        SELECT COUNT(*) FROM audio_clips
        WHERE id = (SELECT COALESCE(sourceClipId, id) FROM audio_clips WHERE id = :clipId)
           OR sourceClipId = (SELECT COALESCE(sourceClipId, id) FROM audio_clips WHERE id = :clipId)
    """)
    suspend fun getGroupSize(clipId: Long): Int

    /** Resolve a clip id to its source's id. Returns the clip's own id if it is a source. */
    @Query("SELECT COALESCE(sourceClipId, id) FROM audio_clips WHERE id = :clipId")
    suspend fun resolveSourceId(clipId: Long): Long?

    /**
     * Update a clip's [sourceClipId]. Pass null to mark the clip as a source,
     * or a source id to mark it as an instance of that source.
     */
    @Query("UPDATE audio_clips SET sourceClipId = :sourceId WHERE id = :clipId")
    suspend fun updateSourceClipId(clipId: Long, sourceId: Long?)

    /** Re-parent every instance of [oldSourceId] to [newSourceId]. */
    @Query("UPDATE audio_clips SET sourceClipId = :newSourceId WHERE sourceClipId = :oldSourceId")
    suspend fun rewriteSourceClipId(oldSourceId: Long, newSourceId: Long)
}
