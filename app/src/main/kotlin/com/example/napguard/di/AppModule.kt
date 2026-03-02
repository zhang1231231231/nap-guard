package com.example.napguard.di

import android.content.Context
import androidx.room.Room
import com.example.napguard.audio.SnoreDetector
import com.example.napguard.data.NapDao
import com.example.napguard.data.NapDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNapDatabase(@ApplicationContext context: Context): NapDatabase =
        Room.databaseBuilder(context, NapDatabase::class.java, "nap_guard.db").build()

    @Provides
    fun provideNapDao(db: NapDatabase): NapDao = db.napDao()

    @Provides
    @Singleton
    fun provideSnoreDetector(): SnoreDetector = SnoreDetector()
}
