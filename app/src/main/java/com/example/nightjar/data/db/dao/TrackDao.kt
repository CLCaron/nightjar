package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.TrackEntity

/** Data access object for [TrackEntity] — multi-track timeline operations. */
@Dao
interface TrackDao {

    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET displayName = :name WHERE id = :id")
    suspend fun updateDisplayName(id: Long, name: String)

    @Query("UPDATE tracks SET offsetMs = :offsetMs WHERE id = :id")
    suspend fun updateOffset(id: Long, offsetMs: Long)

    @Query("UPDATE tracks SET trimStartMs = :startMs, trimEndMs = :endMs WHERE id = :id")
    suspend fun updateTrim(id: Long, startMs: Long, endMs: Long)

    @Query("UPDATE tracks SET isMuted = :muted WHERE id = :id")
    suspend fun updateMuted(id: Long, muted: Boolean)

    @Query("UPDATE tracks SET volume = :volume WHERE id = :id")
    suspend fun updateVolume(id: Long, volume: Float)

    @Query("UPDATE tracks SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long)

    @Query("UPDATE tracks SET audioFileName = :fileName, durationMs = :durationMs WHERE id = :id")
    suspend fun updateCaptureSource(id: Long, fileName: String, durationMs: Long)

    @Query("UPDATE tracks SET midiProgram = :program WHERE id = :id")
    suspend fun updateMidiProgram(id: Long, program: Int)

    @Query("UPDATE tracks SET midiChannel = :channel WHERE id = :id")
    suspend fun updateMidiChannel(id: Long, channel: Int)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)

    @Query("SELECT * FROM tracks WHERE ideaId = :ideaId ORDER BY sortIndex ASC")
    suspend fun getTracksForIdea(ideaId: Long): List<TrackEntity>

    @Query("""
        SELECT tracks.* FROM tracks
        WHERE tracks.ideaId = :ideaId AND NOT EXISTS (
            SELECT 1 FROM audio_clips JOIN capture_groups ON capture_groups.clipId = audio_clips.id
            WHERE audio_clips.trackId = tracks.id AND capture_groups.inStudio = 0
        )
        ORDER BY tracks.sortIndex ASC
    """)
    suspend fun getStudioTracksForIdea(ideaId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE ideaId = :ideaId")
    suspend fun getTrackCount(ideaId: Long): Int

    /**
     * Returns the total playback duration for every idea in a single query.
     *
     * Each idea's duration is the farthest endpoint across its tracks:
     * `MAX(offsetMs + durationMs - trimStartMs - trimEndMs)`.
     */
    @Query(
        """
        SELECT ideaId, MAX(offsetMs + durationMs - trimStartMs - trimEndMs) AS durationMs
        FROM tracks
        GROUP BY ideaId
        """
    )
    suspend fun getIdeaDurations(): List<IdeaDuration>
}
