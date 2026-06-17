package com.piontech.bugfilter.demo.presentation.screen.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.piontech.bugfilter.demo.domain.usecase.EnsureIblUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Màn camera: model (GLB) đã tải ở màn list và truyền qua Intent; ViewModel chỉ lo resolve IBL
 * (môi trường) về File local cho core. CameraX/GL nằm ở Activity (đối tượng gắn view/lifecycle).
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val ensureIbl: EnsureIblUseCase
) : ViewModel() {

    private val _ibl = MutableStateFlow<File?>(null)
    val ibl: StateFlow<File?> = _ibl.asStateFlow()

    fun loadIbl() {
        if (_ibl.value != null) return
        viewModelScope.launch {
            runCatching { ensureIbl() }.onSuccess { _ibl.value = it }
        }
    }
}
