package com.piontech.bugfilter.core.face

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import java.util.concurrent.Executor

/**
 * Kết quả nhận diện 1 khuôn mặt, kèm kích thước ảnh nguồn (đã xoay đứng) để overlay/render
 * có thể quy đổi toạ độ landmark sang toạ độ view/scene.
 */
data class FaceResult(
    val faceMesh: FaceMesh,
    /** Width/height của ảnh ở hệ toạ độ mà ML Kit trả landmark (đã áp rotationDegrees). */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Góc xoay cảm biến (0/90/180/270) — dùng để suy ra phép quay sang output texture. */
    val rotationDegrees: Int,
    /** Timestamp (ns) của frame camera mà mặt này được tính từ đó — để KHỚP với frame đang render
     *  (như app gốc buffer mesh theo timestamp) → bọ đồng bộ đúng khoảnh khắc, không lệch khi rung cam. */
    val timestampNs: Long
)

/**
 * Analyzer gắn vào ImageAnalysis của CameraX. Chạy ML Kit Face Mesh trên từng frame
 * (throttle bằng STRATEGY_KEEP_ONLY_LATEST ở phía CameraX) và đẩy [FaceResult] mới nhất ra callback.
 *
 * useCase = FACE_MESH → trả về 468 điểm landmark + tam giác lưới mặt.
 */
class FaceMeshAnalyzer(
    /** Executor chạy các listener kết quả ML Kit (cùng luồng nền analysis) → không nghẽn main. */
    private val listenerExecutor: Executor,
    private val onResult: (FaceResult?) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        // Sau khi xoay, nếu 90/270 thì width/height đảo nhau.
        val upright90 = rotation == 90 || rotation == 270
        val imgW = if (upright90) imageProxy.height else imageProxy.width
        val imgH = if (upright90) imageProxy.width else imageProxy.height
        // Timestamp của frame (cùng hệ với SurfaceTexture.timestamp ở processor) → để khớp khi render.
        val tsNs = imageProxy.imageInfo.timestamp

        val input = InputImage.fromMediaImage(mediaImage, rotation)
        // Listener chạy trên listenerExecutor (luồng nền) → kết quả về nhanh, không chờ main.
        detector.process(input)
            .addOnSuccessListener(listenerExecutor) { meshes ->
                val mesh = meshes.firstOrNull()
                onResult(if (mesh != null) FaceResult(mesh, imgW, imgH, rotation, tsNs) else null)
            }
            .addOnFailureListener(listenerExecutor) { onResult(null) }
            .addOnCompleteListener(listenerExecutor) { imageProxy.close() }
    }

    fun close() = detector.close()
}
