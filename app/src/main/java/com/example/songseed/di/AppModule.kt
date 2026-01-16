package com.example.songseed.di

import android.content.Context
import com.example.songseed.audio.AudioRecorder
import com.example.songseed.data.db.SongSeedDatabase
import com.example.songseed.data.db.dao.IdeaDao
import com.example.songseed.data.db.dao.TagDao
import com.example.songseed.data.repository.IdeaRepository
import com.example.songseed.data.storage.RecordingStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    companion object {
        @Provides
        @JvmStatic
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): SongSeedDatabase =
            SongSeedDatabase.getInstance(context)

        @Provides
        @JvmStatic
        fun provideIdeaDao(db: SongSeedDatabase): IdeaDao = db.ideaDao()

        @Provides
        @JvmStatic
        fun provideTagDao(db: SongSeedDatabase): TagDao = db.tagDao()

        @Provides
        @JvmStatic
        fun provideRecordingStorage(@ApplicationContext context: Context): RecordingStorage =
            RecordingStorage(context)

        @Provides
        @JvmStatic
        @Singleton
        fun provideIdeaRepository(
            ideaDao: IdeaDao,
            tagDao: TagDao,
            storage: RecordingStorage
        ): IdeaRepository = IdeaRepository(ideaDao, tagDao, storage)

        @Provides
        @JvmStatic
        fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder =
            AudioRecorder(context)
    }
}