package com.piontech.bugfilter.core.render

import com.google.android.filament.gltfio.FilamentInstance
import com.piontech.bugfilter.core.face.FaceResult
import com.piontech.bugfilter.core.testutil.Benchmark
import com.piontech.bugfilter.core.testutil.FakeFace
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Test HIỆU NĂNG TỔNG (headline): mô phỏng toàn bộ phần CPU/toán mà :core làm MỖI FRAME render (trừ phần
 * native Filament/GL không unit-test được):
 *
 *   1) faceWidthPx  — đo bề ngang mặt (1×/frame)
 *   2) surface.update — dựng mesh + pháp tuyến + bbox cho 468 đỉnh (1×/frame, nặng nhất)
 *   3) walker.advance × N bug — tiến trạng thái path từng con bọ
 *
 * Phần CÒN LẠI mỗi frame (BugPlacer.place → ma trận transform + Filament setTransform, GlSurfaceProcessor vẽ)
 * cần native/GPU nên thuộc instrumented test (xem README ở cuối). Ở đây kiểm phần thuần-CPU phải nằm GỌN trong
 * 16.6ms để chừa thời gian cho native render đạt 60fps.
 */
class FramePipelinePerfTest {

    private lateinit var mapper: FaceCoordinateMapper
    private lateinit var surface: FaceSurfaceModel
    private lateinit var walker: BugWalker
    private lateinit var face: FaceResult
    private val w = FakeFace.TEX_W
    private val h = FakeFace.TEX_H
    private val dt = 1f / 60f

    @Before
    fun setUp() {
        mapper = FaceCoordinateMapper().apply { mirror = true }
        surface = FaceSurfaceModel(mapper)
        walker = BugWalker()
        face = FakeFace.faceResult()
    }

    private fun bugs(n: Int): List<Bug> =
        List(n) { Bug(FakeFace.sampleScrip(), mock(FilamentInstance::class.java)).also { walker.buildPath(it) } }

    /** Chi phí CPU 1 frame với [bugCount] bọ; in headroom + FPS lý thuyết; assert dưới 1 phần budget. */
    private fun runFrame(bugCount: Int, ceilingMs: Double) {
        val list = bugs(bugCount)
        val r = Benchmark("FRAME core-CPU ($bugCount bug)", warmup = 1_000, iterations = 10_000)
            .measure {
                val faceW = mapper.faceWidthPx(face, w, h)
                surface.update(face, w, h, dt)
                for (b in list) walker.advance(b, dt, faceW, surface)
            }
        val theoreticalFps = 1000.0 / r.msPerOp
        val headroomPct = (1.0 - r.msPerOp / Benchmark.FRAME_BUDGET_MS) * 100.0
        println(
            String.format(
                "[perf] FRAME ($bugCount bug): %.4f ms/frame core-CPU  ⇒  ~%,.0f fps lý thuyết, " +
                    "dùng %.3f%% / headroom %.3f%% của budget 60fps (16.67ms)",
                r.msPerOp, theoreticalFps, r.msPerOp / Benchmark.FRAME_BUDGET_MS * 100.0, headroomPct,
            ),
        )
        r.assertUnderMsPerOp(ceilingMs)
    }

    @Test
    fun frame_1bug_fitsBudget() = runFrame(bugCount = 1, ceilingMs = 8.0)

    @Test
    fun frame_3bugs_fitsBudget() = runFrame(bugCount = 3, ceilingMs = 8.0)

    @Test
    fun frame_10bugs_fitsBudget() {
        // Kịch bản nặng: 10 bọ cùng lúc. Phần core-CPU vẫn phải gọn trong nửa budget (≤8ms) để native render kịp.
        runFrame(bugCount = 10, ceilingMs = 8.0)
    }

    @Test
    fun surfaceUpdate_dominatesOverWalkers() {
        // Khẳng định hồ sơ chi phí: dựng mesh (1×) nặng hơn nhiều so với advance từng bọ → tối ưu nên nhắm update.
        val faceW = mapper.faceWidthPx(face, w, h)
        val upd = Benchmark("profile: surface.update", warmup = 1_000, iterations = 15_000)
            .measure { surface.update(face, w, h, dt) }
        val bug = bugs(1).first()
        val adv = Benchmark("profile: walker.advance x1", warmup = 5_000, iterations = 200_000)
            .measure { walker.advance(bug, dt, faceW, surface) }
        assertTrue(
            "[perf] kỳ vọng surface.update (${"%.0f".format(upd.nsPerOp)}ns) >> advance (${"%.0f".format(adv.nsPerOp)}ns)",
            upd.nsPerOp > adv.nsPerOp,
        )
    }
}
