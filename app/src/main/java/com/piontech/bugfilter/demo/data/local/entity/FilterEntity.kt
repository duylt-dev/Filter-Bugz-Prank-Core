package com.piontech.bugfilter.demo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.piontech.bugfilter.core.model.BugScrip

/**
 * Bản ghi Room cho 1 filter bọ (SSOT). Lưu tất cả trường của catalog (kể cả [scrip] dạng JSON qua
 * TypeConverter) + cột app-only [localPath].
 *
 * [localPath]: đường dẫn File GLB đã tải về máy. "" = chưa tải → là điều kiện để quyết định download.
 */
@Entity(tableName = "filters")
data class FilterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnail: String,
    @ColumnInfo(name = "model_zip") val modelZip: String,
    val model: String,
    val scrip: List<BugScrip>,
    @ColumnInfo(name = "yaw_offset") val yawOffset: Float?,
    @ColumnInfo(name = "size_scale") val sizeScale: Float?,
    @ColumnInfo(name = "local_path") val localPath: String = "",
)
