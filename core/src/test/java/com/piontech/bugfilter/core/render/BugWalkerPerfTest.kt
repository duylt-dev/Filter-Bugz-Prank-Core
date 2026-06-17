package com.piontech.bugfilter.core.render

import com.google.android.filament.gltfio.FilamentInstance
import com.piontech.bugfilter.core.testutil.Benchmark
import com.piontech.bugfilter.core.testutil.FakeFace
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Hiệu năng máy trạng thái BÒ theo đồ thị đoạn — chạy MỖI BUG mỗi frame: advance() dựng dãy index theo chiều,
 * tích luỹ độ dài đoạn, cập nhật phase + hideFactor, lấy mẫu vị trí, và đôi khi pickNext (chọn đoạn kế).
 * Với N bug trên màn hình, chi phí này nhân N → cần rẻ.
 *
 * Bug giữ 1 FilamentInstance nhưng BugWalker KHÔNG đụng tới nó → mock cho qua constructor là đủ.
 */
class BugWalkerPerfTest {

    private lateinit var mapper: FaceCoordinateMapper
    private lateinit var surface: FaceSurfaceModel
    private lateinit var walker: BugWalker
    private lateinit var bug: Bug
    private val w = FakeFace.TEX_W
    private val h = FakeFace.TEX_H
    private val dt = 1f / 60f
    private var faceW = 0f

    @Before
    fun setUp() {
        mapper = FaceCoordinateMapper().apply { mirror = true }
        val face = FakeFace.faceResult()
        surface = FaceSurfaceModel(mapper).apply { update(face, w, h, dt) }
        faceW = mapper.faceWidthPx(face, w, h)

        walker = BugWalker()
        bug = Bug(FakeFace.sampleScrip(), mock(FilamentInstance::class.java))
        walker.buildPath(bug)
    }

    @Test
    fun advance_perBug_isCheap() {
        // Chạy liên tục → bọ đi hết đoạn, pickNext, qua trạng thái mũi... tất cả nằm trong vòng đo.
        Benchmark("BugWalker.advance (1 bug/frame)", warmup = 5_000, iterations = 300_000)
            .measure { walker.advance(bug, dt, faceW, surface) }
            .assertUnderNsPerOp(8_000.0)

        // Sanity: chỉ số lấy mẫu luôn nằm trong [0, vertCount) và t hợp lệ.
        assertTrue(bug.segPhase in 0f..1f)
        assertTrue(walker.segLocalT in 0f..1f)
    }

    @Test
    fun buildPath_isCheap() {
        // buildPath chạy 1 lần/bug lúc khởi tạo (không phải mỗi frame) nhưng vẫn nên rẻ.
        Benchmark("BugWalker.buildPath", warmup = 2_000, iterations = 100_000)
            .measure { walker.buildPath(bug) }
            .assertUnderNsPerOp(20_000.0)
    }

    @Test
    fun advance_manyBugs_scalesLinearly() {
        // 8 bug dùng CHUNG 1 walker (scratch tái dùng, đúng như GL thread). Chi phí ~ tuyến tính theo số bug.
        val bugs = List(8) { Bug(FakeFace.sampleScrip(), mock(FilamentInstance::class.java)).also { walker.buildPath(it) } }
        val r = Benchmark("BugWalker.advance x8 bugs (1 frame)", warmup = 2_000, iterations = 50_000)
            .measure { for (b in bugs) walker.advance(b, dt, faceW, surface) }
        // 8 bug vẫn phải << 1 frame budget.
        r.assertUnderMsPerOp(2.0)
    }
}
