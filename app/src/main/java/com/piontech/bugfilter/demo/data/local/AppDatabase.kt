package com.piontech.bugfilter.demo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.piontech.bugfilter.demo.data.local.converter.ScripConverter
import com.piontech.bugfilter.demo.data.local.dao.FilterDao
import com.piontech.bugfilter.demo.data.local.entity.FilterEntity

@Database(entities = [FilterEntity::class], version = 1, exportSchema = false)
@TypeConverters(ScripConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun filterDao(): FilterDao
}
