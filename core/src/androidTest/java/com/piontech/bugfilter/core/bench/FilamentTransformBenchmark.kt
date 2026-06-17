package com.piontech.bugfilter.core.bench

import android.opengl.Matrix
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.utils.Utils
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device: đo phần NATIVE của việc đặt 1 con bọ mỗi frame (BugPlacer.place) mà unit test JVM không chạy được:
 *   1) dựng ma trận transform bằng android.opengl.Matrix (translate/rotate/scale như place()),
 *   2) đẩy xuống Filament qua TransformManager.setTransform (gọi JNI).
 *
 * Không cần file .glb: tự tạo 1 entity + transform component để cô lập đúng chi phí này. Nhân với số bọ trên
 * màn hình để ước lượng tổng/ frame.
 */
@RunWith(AndroidJUnit4::class)
class FilamentTransformBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var engine: Engine
    private var entity = 0
    private var ti = 0
    private val m = FloatArray(16)
    private val basis = FloatArray(16)

    @Before
    fun setUp() {
        Utils.init()                                   // load native filament-jni (như FilamentRenderer production)
        engine = Engine.create()                       // khởi tạo engine (headless, không cần SwapChain)
        entity = EntityManager.get().create()
        engine.transformManager.create(entity)         // tạo transform component cho entity
        ti = engine.transformManager.getInstance(entity)
        assertTrue("transform instance hợp lệ", ti != 0)
    }

    /** Chỉ phần CPU: dựng ma trận transform y như BugPlacer.place (android.opengl.Matrix) — đo trên ARM thật. */
    @Test
    fun buildTransformMatrix_cpuOnly() {
        var f = 0f
        benchmarkRule.measureRepeated {
            f += 0.0017f
            buildBugMatrix(f)
        }
    }

    /** CPU dựng ma trận + JNI setTransform (đúng chi phí native mỗi bọ/frame). */
    @Test
    fun buildAndSetTransform_perBugPerFrame() {
        val tm = engine.transformManager
        var f = 0f
        benchmarkRule.measureRepeated {
            f += 0.0017f
            buildBugMatrix(f)
            tm.setTransform(ti, m)
        }
    }

    /** Mô phỏng đúng chuỗi phép biến đổi trong BugPlacer.place: basis bề mặt → yaw/tilt → scale → bù tâm. */
    private fun buildBugMatrix(f: Float) {
        // basis trực giao giả lập (right/normal/tangent) — như place() set từ pháp tuyến/tiếp tuyến đã mượt.
        basis[0] = 1f; basis[1] = 0f; basis[2] = 0f; basis[3] = 0f
        basis[4] = 0f; basis[5] = 1f; basis[6] = 0f; basis[7] = 0f
        basis[8] = 0f; basis[9] = 0f; basis[10] = 1f; basis[11] = 0f
        basis[12] = 0f; basis[13] = 0f; basis[14] = 0f; basis[15] = 1f
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 300f + f, 400f + f, -50f)
        Matrix.multiplyMM(m, 0, m, 0, basis, 0)
        Matrix.rotateM(m, 0, 180f, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, 0f, 1f, 0f, 0f)
        Matrix.scaleM(m, 0, 2.5f, 2.5f, 2.5f)
        Matrix.translateM(m, 0, -0.1f, -0.2f, -0.05f)
    }

    @After
    fun tearDown() {
        if (this::engine.isInitialized) engine.destroy()
    }
}
