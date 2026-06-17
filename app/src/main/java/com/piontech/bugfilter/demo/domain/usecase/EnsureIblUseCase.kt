package com.piontech.bugfilter.demo.domain.usecase

import com.piontech.bugfilter.demo.domain.repository.FilterRepository
import java.io.File
import javax.inject.Inject

/** Đảm bảo IBL môi trường có local → trả File local cho core. */
class EnsureIblUseCase @Inject constructor(
    private val repository: FilterRepository
) {
    suspend operator fun invoke(): File = repository.ensureIbl()
}
