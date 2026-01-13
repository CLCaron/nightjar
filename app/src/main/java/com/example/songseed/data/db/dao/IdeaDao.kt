package com.example.songseed.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.songseed.data.db.entity.IdeaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdeaDao {

    @Insert(onConflict = OnConflictStrategy.Companion.ABORT)
    suspend fun insertIdea(idea: IdeaEntity): Long

    @Query("SELECT * FROM ideas ORDER BY createdAtEpochMs DESC")
    fun observeIdeas(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas WHERE id = :id LIMIT 1")
    suspend fun getIdeaById(id: Long): IdeaEntity?

    @Query("UPDATE ideas SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE ideas SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("UPDATE ideas SET isFavorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM ideas WHERE id = :id")
    suspend fun deleteIdeaById(id: Long)

    @Query("""
    SELECT i.* FROM ideas i
    INNER JOIN idea_tags it ON it.ideaId = i.id
    INNER JOIN tags t ON t.id = it.tagId
    WHERE t.nameNormalized = :tagNormalized
    ORDER BY i.createdAtEpochMs DESC
""")
    fun observeIdeasForTag(tagNormalized: String): Flow<List<IdeaEntity>>

    @Query("""
    SELECT * FROM ideas
    ORDER BY isFavorite DESC, createdAtEpochMs DESC
""")
    fun observeIdeasFavoritesFirst(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas ORDER BY createdAtEpochMs ASC")
    fun observeIdeasOldestFirst(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas ORDER BY createdAtEpochMs DESC")
    suspend fun getIdeasNewest(): List<IdeaEntity>

    @Query("SELECT * FROM ideas ORDER BY createdAtEpochMs ASC")
    suspend fun getIdeasOldestFirst(): List<IdeaEntity>

    @Query("SELECT * FROM ideas ORDER BY isFavorite DESC, createdAtEpochMs DESC")
    suspend fun getIdeasFavoritesFirst(): List<IdeaEntity>

    @Query("""
    SELECT i.* FROM ideas i
    INNER JOIN idea_tags it ON it.ideaId = i.id
    INNER JOIN tags t ON t.id = it.tagId
    WHERE t.nameNormalized = :tagNormalized
    ORDER BY i.createdAtEpochMs DESC
""")
    suspend fun getIdeasForTag(tagNormalized: String): List<IdeaEntity>

}