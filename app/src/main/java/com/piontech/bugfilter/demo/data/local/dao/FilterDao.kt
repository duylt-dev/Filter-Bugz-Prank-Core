package com.piontech.bugfilter.demo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.piontech.bugfilter.demo.data.local.entity.FilterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterDao {

    /** SSOT: phát danh sách filter mỗi khi DB đổi (vd update local_path). */
    @Query("SELECT * FROM filters")
    fun observeAll(): Flow<List<FilterEntity>>

    @Query("SELECT * FROM filters WHERE id = :id")
    suspend fun getById(id: String): FilterEntity?

    @Query("SELECT COUNT(*) FROM filters")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FilterEntity>)

    /** Cập nhật path file đã tải về cho 1 filter. */
    @Query("UPDATE filters SET local_path = :path WHERE id = :id")
    suspend fun updateLocalPath(id: String, path: String)
}
