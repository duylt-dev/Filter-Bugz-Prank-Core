package com.piontech.bugfilter.demo.di

import com.piontech.bugfilter.demo.domain.repository.FilterRepository
import com.piontech.bugfilter.demo.data.repository.FilterRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFilterRepository(impl: FilterRepositoryImpl): FilterRepository
}
