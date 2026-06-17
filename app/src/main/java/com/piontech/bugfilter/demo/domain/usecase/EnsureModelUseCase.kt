package com.piontech.bugfilter.demo.domain.usecase

import com.piontech.bugfilter.demo.domain.repository.FilterRepository
import java.io.File
import javax.inject.Inject

/** Đảm bảo model của filter [id] đã tải về (theo local_path) → trả File local. */
class EnsureModelUseCase @Inject constructor(
    private val repository: FilterRepository
) {
    suspend operator fun invoke(id: String): File = repository.ensureModel(id)
}
