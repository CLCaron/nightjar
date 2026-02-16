package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.nightjar.data.db.IdeaTagCrossRef
import com.example.nightjar.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE nameNormalized = :normalized LIMIT 1")
    suspend fun getTagByNormalized(normalized: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToIdea(crossRef: IdeaTagCrossRef)

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN idea_tags it ON it.tagId = t.id
        WHERE it.ideaId = :ideaId
        ORDER BY t.nameNormalized ASC
    """)
    fun observeTagsForIdea(ideaId: Long): Flow<List<TagEntity>>

    // ✅ NEW: non-Flow version for WorkspaceViewModel’s imperative refresh
    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN idea_tags it ON it.tagId = t.id
        WHERE it.ideaId = :ideaId
        ORDER BY t.nameNormalized ASC
    """)
    suspend fun getTagsForIdea(ideaId: Long): List<TagEntity>

    @Query("""
        DELETE FROM idea_tags
        WHERE ideaId = :ideaId AND tagId = :tagId
    """)
    suspend fun removeTagFromIdea(ideaId: Long, tagId: Long)

    @Query("""
        SELECT DISTINCT t.* FROM tags t
        INNER JOIN idea_tags it ON it.tagId = t.id
        ORDER BY t.nameNormalized ASC
    """)
    fun observeAllUsedTags(): Flow<List<TagEntity>>

    @Query("""
    SELECT DISTINCT t.* FROM tags t
    INNER JOIN idea_tags it ON it.tagId = t.id
    ORDER BY t.nameNormalized ASC
""")
    suspend fun getAllUsedTags(): List<TagEntity>

}
