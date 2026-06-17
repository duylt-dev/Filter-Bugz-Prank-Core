package com.piontech.bugfilter.demo.presentation.screen.bugs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.piontech.bugfilter.demo.domain.model.BugFilter
import com.piontech.bugfilter.demo.domain.usecase.EnsureModelUseCase
import com.piontech.bugfilter.demo.domain.usecase.ObserveFiltersUseCase
import com.piontech.bugfilter.demo.domain.usecase.SeedFiltersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State của lưới chọn bọ. */
sealed interface BugsUiState {
    data object Loading : BugsUiState
    data object Empty : BugsUiState
    data class Content(val filters: List<BugFilter>) : BugsUiState

    /** [message] = chi tiết lỗi gốc (có thể null); copy hiển thị do Activity quyết định qua string res. */
    data class Error(val message: String?) : BugsUiState
}

/** State tải model của con bọ đang chọn. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Loading : DownloadState
    data class Success(val filter: BugFilter, val modelPath: String) : DownloadState

    /** [message] = chi tiết lỗi gốc (có thể null); copy hiển thị do Activity quyết định. */
    data class Error(val message: String?) : DownloadState
}

@HiltViewModel
class BugsViewModel @Inject constructor(
    private val seedFilters: SeedFiltersUseCase,
    private val observeFilters: ObserveFiltersUseCase,
    private val ensureModel: EnsureModelUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BugsUiState>(BugsUiState.Loading)
    val uiState: StateFlow<BugsUiState> = _uiState.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    init {
        seedThenObserve()
    }

    /** Seed lần đầu (DB rỗng) rồi quan sát SSOT (Room). Đã có data → seed no-op, vẫn chạy offline. */
    private fun seedThenObserve() {
        viewModelScope.launch {
            _uiState.value = BugsUiState.Loading
            val seeded = runCatching { seedFilters() }
            if (seeded.isFailure) {
                _uiState.value = BugsUiState.Error(seeded.exceptionOrNull()?.message)
                return@launch
            }
            observeFilters().collect { list ->
                _uiState.value =
                    if (list.isEmpty()) BugsUiState.Empty else BugsUiState.Content(list)
            }
        }
    }

    /** Tải GLB của [filter] (theo local_path); phát [DownloadState] để Activity điều hướng. */
    fun download(filter: BugFilter) {
        if (_download.value is DownloadState.Loading) return
        viewModelScope.launch {
            _download.value = DownloadState.Loading
            runCatching { ensureModel(filter.id) }
                .onSuccess { _download.value = DownloadState.Success(filter, it.absolutePath) }
                .onFailure { _download.value = DownloadState.Error(it.message) }
        }
    }

    /** Đánh dấu đã xử lý xong 1 sự kiện download (tránh điều hướng lại khi xoay màn). */
    fun consumeDownload() {
        _download.value = DownloadState.Idle
    }
}
