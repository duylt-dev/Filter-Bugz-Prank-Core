package com.piontech.bugfilter.demo.presentation.screen.record

import android.Manifest
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.piontech.bugfilter.core.face.FaceMeshAnalyzer
import com.piontech.bugfilter.core.gl.GlSurfaceProcessor
import com.piontech.bugfilter.core.render.FilamentRenderer
import com.piontech.bugfilter.demo.BuildConfig
import com.piontech.bugfilter.demo.R
import com.piontech.bugfilter.demo.databinding.ActivityRecordBinding
import com.piontech.bugfilter.demo.domain.model.BugFilter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Stage 1: dựng nền tảng.
 *  - CameraX qua [LifecycleCameraController] + PreviewView.
 *  - Gắn [GlSurfaceProcessor] làm CameraEffect (PREVIEW | VIDEO_CAPTURE) → frame camera đi qua GL,
 *    nên video quay ra sẽ chứa đúng nội dung hiển thị (chỗ này Stage 2 sẽ composite bọ 3D).
 *  - ML Kit Face Mesh chạy trên IMAGE_ANALYSIS, vẽ landmark lên overlay để kiểm chứng tracking.
 *  - Quay video lưu vào MediaStore (Movies/BugFilter).
 *
 * Quyền Camera/Mic đã được xin ở [com.piontech.bugfilter.demo.presentation.screen.bugs.BugsActivity] trước khi vào đây; màn này coi như đã có quyền.
 */
@AndroidEntryPoint
class RecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordBinding

    private val viewModel: RecordViewModel by viewModels()

    private var controller: LifecycleCameraController? = null
    private var processor: GlSurfaceProcessor? = null
    private var renderer: FilamentRenderer? = null
    private var faceAnalyzer: FaceMeshAnalyzer? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    /** Luồng nền cho ML Kit analysis — TẠO LẠI mỗi [onStart], shutdown ở [onStop] để không giữ thread khi nền. */
    private var analysisExecutor: ExecutorService? = null

    /** Con bọ user đã chọn ở [com.piontech.bugfilter.demo.presentation.screen.bugs.BugsActivity] + File GLB local đã tải về (do app truyền sang). */
    private var selectedFilter: BugFilter? = null
    private var modelFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KHOÁ PORTRAIT (giống app gốc — MainActivity của gốc screenOrientation=portrait). Pipeline GL/mapping
        // landmark→texture chỉ đúng ở dọc; khoá bằng code (kèm khoá manifest) để chắc trên mọi máy/ROM.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedFilter =
            IntentCompat.getParcelableExtra(intent, EXTRA_FILTER, BugFilter::class.java)
        modelFile = intent.getStringExtra(EXTRA_MODEL_PATH)?.let { File(it) }

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnFlip.setOnClickListener { flipCamera() }

        // Quyền đã xin ở màn list; nếu vì lý do nào đó vẫn thiếu thì thoát an toàn.
        if (!hasCameraPermission()) {
            Toast.makeText(this, getString(R.string.permission_camera_missing), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // IBL (môi trường) do ViewModel resolve về File local. Collector gắn 1 LẦN ở scope activity (KHÔNG
        // đặt trong startCamera để khỏi chồng nhiều collector qua mỗi onStart) → set vào renderer hiện hành.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ibl.collect { file -> file?.let { renderer?.setIbl(it) } }
            }
        }
        viewModel.loadIbl()
    }

    /** Tạo camera + GL/Filament khi vào foreground. */
    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) startCamera()
    }

    /**
     * Giải phóng MỌI tài nguyên nặng (camera + GL + Filament engine/model + ML Kit + executor) khi rời
     * foreground → KHÔNG giữ native memory lúc ở nền (tránh OOM / bị hệ thống kill). Tạo lại ở [onStart] kế.
     */
    override fun onStop() {
        super.onStop()
        releaseCamera()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (controller != null) return            // đã chạy (tránh tạo trùng nếu onStart gọi 2 lần)

        val exec = Executors.newSingleThreadExecutor()
        analysisExecutor = exec

        val proc = GlSurfaceProcessor()
        processor = proc

        // Filament render model bọ, composite vào pipeline GL. proc giữ renderer qua glListener;
        // dọn dẹp khi processor.release() (onStop) → engine bị destroy ở onGlRelease.
        val rdr = FilamentRenderer(BuildConfig.isDebug)
        renderer = rdr
        proc.glListener = rdr

        // Đặt con bọ đã chọn: GLB là File local (app đã tải ở BugsActivity) + IBL resolve về cache.
        // Core chỉ đọc File — không chạm asset/URL.
        val filter = selectedFilter
        val glb = modelFile
        if (filter != null && glb != null) {
            rdr.setModel(glb, filter.scrip, filter.yawOffset, filter.sizeScale)
        }
        // IBL: nếu đã resolve sẵn (lần resume sau) thì áp ngay; lần đầu collector ở onCreate sẽ áp khi xong.
        viewModel.ibl.value?.let { rdr.setIbl(it) }

        // Effect áp cho cả preview và video → cùng một luồng GL.
        val effect = object : CameraEffect(
            PREVIEW or VIDEO_CAPTURE,
            ContextCompat.getMainExecutor(this),
            proc,
            Consumer { t -> Log.e(TAG, "CameraEffect error", t) }
        ) {}

        // Analyzer chạy trên luồng nền (exec) → KHÔNG nghẽn main → mặt cập nhật nhanh.
        // setFace là thread-safe (@Volatile); cập nhật UI phải post về main.
        val analyzer = FaceMeshAnalyzer(exec) { result ->
            val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
            rdr.setFace(result, mirror = isFront)
            runOnUiThread {
                // Chấm landmark + bounding box chỉ vẽ khi bật debug.
                binding.faceOverlay.setResult(
                    if (BuildConfig.isDebug) result else null,
                    mirror = isFront
                )
                binding.tvStatus.text = if (result != null)
                    getString(R.string.status_face_detected, result.faceMesh.allPoints.size)
                else getString(R.string.status_no_face)
            }
        }
        faceAnalyzer = analyzer

        val ctrl = LifecycleCameraController(this).apply {
            cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            setEnabledUseCases(
                LifecycleCameraController.IMAGE_ANALYSIS or LifecycleCameraController.VIDEO_CAPTURE
            )
            // Phân giải analysis THẤP (640×360) như app gốc → ML Kit chạy nhanh → mặt cập nhật gần bằng
            // tốc độ camera → bọ bám mặt khi RUNG/DI CHUYỂN cam (không "nhảy" do cập nhật thưa).
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 360),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                ).build()
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(exec, analyzer)
            setEffects(setOf(effect))
            bindToLifecycle(this@RecordActivity)
        }
        controller = ctrl
        binding.previewView.controller = ctrl
        binding.tvStatus.text = getString(R.string.status_camera_ready)
    }

    /** Nhả toàn bộ tài nguyên camera/GL/Filament/ML Kit/executor. Idempotent (gọi lại an toàn). */
    private fun releaseCamera() {
        recording?.stop()
        recording = null
        controller?.unbind()
        controller = null
        binding.previewView.controller = null
        faceAnalyzer?.close()                 // đóng ML Kit detector (giải phóng model native)
        faceAnalyzer = null
        processor?.release()                  // → onGlRelease destroy Filament engine + xóa GL/EGL trên GL thread
        processor = null
        renderer = null
        analysisExecutor?.shutdown()          // dừng & nhả luồng analysis nền
        analysisExecutor = null
    }

    private fun flipCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        controller?.cameraSelector =
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
    }

    private fun toggleRecording() {
        val ctrl = controller ?: return
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }
        val name = "bug_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BugFilter")
            }
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val audioConfig =
            if (hasAudioPermission()) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED

        recording = ctrl.startRecording(
            outputOptions, audioConfig, ContextCompat.getMainExecutor(this)
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.btnRecord.isSelected = true
                    binding.tvStatus.text = getString(R.string.status_recording)
                }

                is VideoRecordEvent.Finalize -> {
                    binding.btnRecord.isSelected = false
                    if (event.hasError()) {
                        Toast.makeText(
                            this,
                            getString(R.string.record_error, event.error),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.record_saved, event.outputResults.outputUri.toString()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    binding.tvStatus.text = getString(R.string.status_camera_ready)
                }
            }
        }
    }

    // Không cần dọn ở onDestroy: onStop LUÔN chạy trước onDestroy và đã releaseCamera() (idempotent).

    companion object {
        private const val TAG = "BugFilter"

        /** Intent extra: con bọ đã chọn (Parcelable) — để lấy scrip/tinh chỉnh, khỏi parse lại catalog. */
        const val EXTRA_FILTER = "extra_filter"

        /** Intent extra: đường dẫn File GLB local đã tải về cache (BugsActivity truyền sang). */
        const val EXTRA_MODEL_PATH = "extra_model_path"
    }
}
