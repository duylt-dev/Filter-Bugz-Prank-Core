package com.piontech.bugfilter.demo.di

import android.content.Context
import androidx.room.Room
import com.piontech.bugfilter.demo.data.local.AppDatabase
import com.piontech.bugfilter.demo.data.local.dao.FilterDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Cung cấp Room database + DAO. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "bugfilter.db").build()

    @Provides
    fun provideFilterDao(database: AppDatabase): FilterDao = database.filterDao()
}
