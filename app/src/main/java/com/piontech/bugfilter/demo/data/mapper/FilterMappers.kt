package com.piontech.bugfilter.demo.data.mapper

import com.piontech.bugfilter.core.model.FilterModel
import com.piontech.bugfilter.demo.data.local.entity.FilterEntity
import com.piontech.bugfilter.demo.domain.model.BugFilter

/** Map giữa 3 tầng: DTO (FilterModel) → Entity (Room) → Domain (BugFilter). */

/** DTO catalog (API) → Entity để seed vào Room. [localPath] mặc định "" (chưa tải). */
fun FilterModel.toEntity(localPath: String = ""): FilterEntity = FilterEntity(
    id = id,
    name = name,
    thumbnail = thumbnail,
    modelZip = modelZip,
    model = model,
    scrip = scrip,
    yawOffset = yawOffset,
    sizeScale = sizeScale,
    localPath = localPath,
)

/** Entity (Room SSOT) → Domain để UI/Intent dùng. */
fun FilterEntity.toDomain(): BugFilter = BugFilter(
    id = id,
    name = name,
    thumbnail = thumbnail,
    modelZip = modelZip,
    model = model,
    scrip = scrip,
    yawOffset = yawOffset,
    sizeScale = sizeScale,
    localPath = localPath,
)
