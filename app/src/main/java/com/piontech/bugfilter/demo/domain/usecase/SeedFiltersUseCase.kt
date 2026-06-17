package com.piontech.bugfilter.demo.domain.usecase

import com.piontech.bugfilter.demo.domain.repository.FilterRepository
import javax.inject.Inject

/** Seed DB lần đầu (rỗng) từ catalog; đã có data thì không làm gì. */
class SeedFiltersUseCase @Inject constructor(
    private val repository: FilterRepository
) {
    suspend operator fun invoke() = repository.seedIfEmpty()
}
