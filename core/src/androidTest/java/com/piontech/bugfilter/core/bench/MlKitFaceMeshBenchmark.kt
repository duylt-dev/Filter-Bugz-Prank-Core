package com.piontech.bugfilter.core.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetector
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * On-device: đo ĐỘ TRỄ nhận diện ML Kit Face Mesh — đây thường là chi phí NẶNG NHẤT thực sự mỗi frame
 * (chạy mạng neural), thứ mà unit test JVM không chạm tới được.
 *
 * Lưu ý: độ trễ inference gần như KHÔNG phụ thuộc nội dung ảnh (model nhận input cố định), nên ảnh tổng hợp
 * 480×640 cho con số latency đại diện. Nếu ML Kit nhận ra mặt thì còn dùng được FaceMesh thật cho
 * [CpuPipelineOnDeviceBenchmark]; nếu không, latency vẫn đúng.
 */
@RunWith(AndroidJUnit4::class)
class MlKitFaceMeshBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var detector: FaceMeshDetector
    private lateinit var image: InputImage

    @Before
    fun setUp() {
        detector = FaceMeshDetection.getClient(
            FaceMeshDetectorOptions.Builder()
                .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
                .build(),
        )
        image = InputImage.fromBitmap(syntheticFace(480, 640), 0)
        // Lần đầu phải tải/khởi tạo model (chậm, có thể cần Play Services) → nuốt ngoài vòng đo.
        val meshes = Tasks.await(detector.process(image), 60, TimeUnit.SECONDS)
        Log.i(TAG, "warmup detect: faces=${meshes.size} (model đã sẵn sàng)")
    }

    @Test
    fun faceMeshDetectionLatency() {
        benchmarkRule.measureRepeated {
            // Tasks.await = đo trọn độ trễ end-to-end của 1 lần detect (như mỗi frame analysis gọi).
            Tasks.await(detector.process(image))
        }
    }

    @After
    fun tearDown() {
        if (this::detector.isInitialized) detector.close()
    }

    companion object {
        private const val TAG = "MlKitBench"

        /** Vẽ một khuôn mặt thô (oval da + 2 mắt + mũi + miệng) để tăng khả năng ML Kit nhận diện. */
        fun syntheticFace(w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(Color.rgb(40, 50, 70))                 // nền tối
            p.color = Color.rgb(232, 190, 160)                 // da
            val cx = w / 2f; val cy = h / 2f
            c.drawOval(cx - w * 0.28f, cy - h * 0.30f, cx + w * 0.28f, cy + h * 0.32f, p)
            p.color = Color.WHITE
            val eyeY = cy - h * 0.07f; val eyeDx = w * 0.13f; val eyeR = w * 0.05f
            c.drawCircle(cx - eyeDx, eyeY, eyeR, p); c.drawCircle(cx + eyeDx, eyeY, eyeR, p)
            p.color = Color.rgb(60, 40, 30)
            c.drawCircle(cx - eyeDx, eyeY, eyeR * 0.45f, p); c.drawCircle(cx + eyeDx, eyeY, eyeR * 0.45f, p)
            p.color = Color.rgb(200, 150, 120)                 // mũi
            c.drawCircle(cx, cy + h * 0.04f, w * 0.035f, p)
            p.color = Color.rgb(150, 70, 70)                   // miệng
            p.strokeWidth = h * 0.012f
            c.drawLine(cx - w * 0.10f, cy + h * 0.16f, cx + w * 0.10f, cy + h * 0.16f, p)
            return bmp
        }
    }
}
