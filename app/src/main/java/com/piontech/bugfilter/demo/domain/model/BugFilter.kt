package com.piontech.bugfilter.demo.domain.model

import android.os.Parcelable
import com.piontech.bugfilter.core.model.BugScrip
import kotlinx.parcelize.Parcelize

@Parcelize
data class BugFilter(
    val id: String,
    val name: String,
    val thumbnail: String,
    val modelZip: String,
    val model: String,
    val scrip: List<BugScrip>,
    val yawOffset: Float?,
    val sizeScale: Float?,
    /** Path File GLB đã tải về máy; "" = chưa tải. */
    val localPath: String,
) : Parcelable
