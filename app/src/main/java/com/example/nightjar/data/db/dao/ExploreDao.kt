package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.nightjar.data.db.entity.ExploreCandidateEntity
import com.example.nightjar.data.db.entity.ExploreCaptureEntity
import com.example.nightjar.data.db.entity.ExploreSegmentEntity
import com.example.nightjar.data.db.entity.ExploreSketchEntity
import com.example.nightjar.data.db.entity.IdeaSectionEntity

/** Persistence for section-focused Explore sketches. */
@Dao
interface ExploreDao {

    @Insert
    suspend fun insertSection(section: IdeaSectionEntity): Long

    @Update
    suspend fun updateSection(section: IdeaSectionEntity)

    @Query("SELECT * FROM idea_sections WHERE ideaId = :ideaId ORDER BY sortIndex, startMs")
    suspend fun getSectionsForIdea(ideaId: Long): List<IdeaSectionEntity>

    @Query("SELECT * FROM idea_sections WHERE id = :sectionId")
    suspend fun getSection(sectionId: Long): IdeaSectionEntity?

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM idea_sections WHERE ideaId = :ideaId")
    suspend fun getMaxSectionSortIndex(ideaId: Long): Int

    @Insert
    suspend fun insertSketch(sketch: ExploreSketchEntity): Long

    @Update
    suspend fun updateSketch(sketch: ExploreSketchEntity)

    @Query("SELECT * FROM explore_sketches WHERE id = :sketchId")
    suspend fun getSketch(sketchId: Long): ExploreSketchEntity?

    @Query("SELECT * FROM explore_sketches WHERE ideaId = :ideaId ORDER BY createdAtEpochMs")
    suspend fun getSketchesForIdea(ideaId: Long): List<ExploreSketchEntity>

    @Query("SELECT * FROM explore_sketches WHERE sectionId = :sectionId ORDER BY createdAtEpochMs")
    suspend fun getSketchesForSection(sectionId: Long): List<ExploreSketchEntity>

    @Query("SELECT * FROM explore_sketches WHERE sectionId = :sectionId AND trackId = :trackId LIMIT 1")
    suspend fun getSketchForSectionTrack(sectionId: Long, trackId: Long): ExploreSketchEntity?

    @Insert
    suspend fun insertCapture(capture: ExploreCaptureEntity): Long

    @Query("SELECT * FROM explore_captures WHERE id = :captureId")
    suspend fun getCapture(captureId: Long): ExploreCaptureEntity?

    @Query("SELECT * FROM explore_captures WHERE id IN (:captureIds)")
    suspend fun getCaptures(captureIds: List<Long>): List<ExploreCaptureEntity>

    @Query("SELECT * FROM explore_captures WHERE ideaId = :ideaId")
    suspend fun getCapturesForIdea(ideaId: Long): List<ExploreCaptureEntity>

    @Insert
    suspend fun insertSegment(segment: ExploreSegmentEntity): Long

    @Update
    suspend fun updateSegment(segment: ExploreSegmentEntity)

    @Query("SELECT * FROM explore_segments WHERE id = :segmentId")
    suspend fun getSegment(segmentId: Long): ExploreSegmentEntity?

    @Query("SELECT * FROM explore_segments WHERE sketchId = :sketchId ORDER BY startMs, sortIndex")
    suspend fun getSegmentsForSketch(sketchId: Long): List<ExploreSegmentEntity>

    @Query("SELECT * FROM explore_segments WHERE sketchId IN (:sketchIds) ORDER BY sketchId, startMs, sortIndex")
    suspend fun getSegmentsForSketches(sketchIds: List<Long>): List<ExploreSegmentEntity>

    @Query("DELETE FROM explore_segments WHERE id = :segmentId")
    suspend fun deleteSegment(segmentId: Long)

    @Query("DELETE FROM explore_segments WHERE sketchId = :sketchId")
    suspend fun deleteSegmentsForSketch(sketchId: Long)

    @Query("UPDATE explore_segments SET status = :status WHERE id = :segmentId")
    suspend fun updateSegmentStatus(segmentId: Long, status: String)

    @Query("UPDATE explore_segments SET selectedCandidateId = :candidateId WHERE id = :segmentId")
    suspend fun selectCandidate(segmentId: Long, candidateId: Long?)

    @Insert
    suspend fun insertCandidate(candidate: ExploreCandidateEntity): Long

    @Query("SELECT * FROM explore_candidates WHERE id = :candidateId")
    suspend fun getCandidate(candidateId: Long): ExploreCandidateEntity?

    @Query("SELECT * FROM explore_candidates WHERE segmentId = :segmentId ORDER BY sortIndex, createdAtEpochMs")
    suspend fun getCandidatesForSegment(segmentId: Long): List<ExploreCandidateEntity>

    @Query("SELECT * FROM explore_candidates WHERE segmentId IN (:segmentIds) ORDER BY segmentId, sortIndex, createdAtEpochMs")
    suspend fun getCandidatesForSegments(segmentIds: List<Long>): List<ExploreCandidateEntity>

    @Query("SELECT * FROM explore_candidates WHERE id IN (:candidateIds)")
    suspend fun getCandidates(candidateIds: List<Long>): List<ExploreCandidateEntity>

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM explore_candidates WHERE segmentId = :segmentId")
    suspend fun getMaxCandidateSortIndex(segmentId: Long): Int
}
