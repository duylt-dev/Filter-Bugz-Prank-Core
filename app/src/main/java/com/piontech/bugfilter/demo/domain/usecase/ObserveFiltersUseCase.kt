package com.piontech.bugfilter.demo.domain.usecase

import com.piontech.bugfilter.demo.domain.model.BugFilter
import com.piontech.bugfilter.demo.domain.repository.FilterRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Luồng danh sách filter từ SSOT (Room). */
class ObserveFiltersUseCase @Inject constructor(
    private val repository: FilterRepository
) {
    operator fun invoke(): Flow<List<BugFilter>> = repository.observeFilters()
}
