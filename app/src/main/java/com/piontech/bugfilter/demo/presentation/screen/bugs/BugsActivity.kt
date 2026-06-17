package com.piontech.bugfilter.demo.presentation.screen.bugs

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.piontech.bugfilter.demo.R
import com.piontech.bugfilter.demo.databinding.ActivityBugsBinding
import com.piontech.bugfilter.demo.domain.model.BugFilter
import com.piontech.bugfilter.demo.presentation.adapter.BugGridAdapter
import com.piontech.bugfilter.demo.presentation.screen.record.RecordActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn 1 (launcher): lưới các con bọ từ catalog (MVVM — [BugsViewModel]).
 *
 * Chọn 1 con → XIN QUYỀN Camera/Mic (trước khi vào camera) → tải GLB về cache → mở [RecordActivity]
 * kèm [FilterModel] (Parcelable) + đường dẫn File local.
 */
@AndroidEntryPoint
class BugsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBugsBinding
    private val viewModel: BugsViewModel by viewModels()
    private val adapter = BugGridAdapter { filter -> onPick(filter) }

    /** Con bọ đang chờ (sau khi xin quyền xong mới tải). */
    private var pendingFilter: BugFilter? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val filter = pendingFilter
        // Camera bắt buộc; mic chỉ cần khi quay có tiếng (MainActivity tự xử lý nếu thiếu).
        if (filter != null && result[Manifest.permission.CAMERA] == true) {
            viewModel.download(filter)
        } else {
            pendingFilter = null
            Toast.makeText(this, getString(R.string.permission_camera_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding = ActivityBugsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvBugs.adapter = adapter
        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.tvEmpty.visibility =
                            if (state is BugsUiState.Empty || state is BugsUiState.Error) View.VISIBLE else View.GONE
                        if (state is BugsUiState.Content) adapter.submit(state.filters)
                        if (state is BugsUiState.Error) {
                            val msg = state.message ?: getString(R.string.error_load_catalog)
                            Toast.makeText(this@BugsActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                launch {
                    viewModel.download.collect { state -> onDownloadState(state) }
                }
            }
        }
    }

    private fun onDownloadState(state: DownloadState) {
        binding.loadingOverlay.visibility =
            if (state is DownloadState.Loading) View.VISIBLE else View.GONE
        when (state) {
            is DownloadState.Success -> {
                pendingFilter = null
                startActivity(
                    Intent(this, RecordActivity::class.java)
                        .putExtra(RecordActivity.EXTRA_FILTER, state.filter)
                        .putExtra(RecordActivity.EXTRA_MODEL_PATH, state.modelPath)
                )
                viewModel.consumeDownload()
            }

            is DownloadState.Error -> {
                pendingFilter = null
                val detail = state.message ?: getString(R.string.error_unknown)
                Toast.makeText(this, getString(R.string.download_failed, detail), Toast.LENGTH_LONG)
                    .show()
                viewModel.consumeDownload()
            }

            else -> Unit
        }
    }

    /** Chọn 1 con bọ: xin quyền trước, đủ quyền thì tải luôn. */
    private fun onPick(filter: BugFilter) {
        if (viewModel.download.value is DownloadState.Loading) return
        pendingFilter = filter
        if (hasCameraPermission()) {
            viewModel.download(filter)
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}
