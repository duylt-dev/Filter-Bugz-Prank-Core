package com.piontech.bugfilter.core.render

import com.piontech.bugfilter.core.face.FaceResult
import com.piontech.bugfilter.core.testutil.Benchmark
import com.piontech.bugfilter.core.testutil.FakeFace
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hiệu năng dựng MESH bề mặt mặt — bước NẶNG NHẤT mỗi frame: update() = computeMesh (world3 ×468 +
 * tâm/bbox + pháp tuyến trụ ×468) + bboxTex (imageToTex ×468). Tức ~2× toàn bộ chi phí mapper cộng toán
 * vector cho từng đỉnh. Đây là thành phần chi phối FPS, nên test cả cost/frame lẫn "bao nhiêu frame trong 1ms".
 */
class FaceSurfaceModelPerfTest {

    private lateinit var mapper: FaceCoordinateMapper
    private lateinit var model: FaceSurfaceModel
    private lateinit var face: FaceResult
    private val w = FakeFace.TEX_W
    private val h = FakeFace.TEX_H
    private val dt = 1f / 60f

    @Before
    fun setUp() {
        mapper = FaceCoordinateMapper().apply { mirror = true }
        model = FaceSurfaceModel(mapper)
        face = FakeFace.faceResult()
    }

    @Test
    fun update_perFrame_underBudget() {
        // 1 op = 1 frame dựng mesh. Trần 5ms = nới rộng (~30% budget 60fps); thực tế nhỏ hơn nhiều.
        val r = Benchmark("FaceSurfaceModel.update (1 frame)", warmup = 1_000, iterations = 15_000)
            .measure { model.update(face, w, h, dt) }
        r.assertUnderMsPerOp(5.0)

        // Sanity: mesh dựng ra phải hợp lệ → số đo phản ánh code thật, không phải đo nhánh rỗng.
        assertTrue("vertCount kỳ vọng 468", model.vertCount == FakeFace.LANDMARK_COUNT)
        assertTrue("centerX hữu hạn", model.centerX.isFinite() && model.centerY.isFinite())
        assertTrue("bbox hợp lệ (max>min)", model.bbox[2] > model.bbox[0] && model.bbox[3] > model.bbox[1])
        val nrmFinite = (0 until model.vertCount).all {
            model.vertNrm[it * 3].isFinite() && model.vertNrm[it * 3 + 1].isFinite() && model.vertNrm[it * 3 + 2].isFinite()
        }
        assertTrue("mọi pháp tuyến hữu hạn (không NaN)", nrmFinite)

        // In headroom 60fps để dễ soi.
        val framesPerBudget = Benchmark.FRAME_BUDGET_MS / r.msPerOp
        println(String.format("[perf] FaceSurfaceModel.update ⇒ ~%.0f frame mesh vừa trong budget 60fps", framesPerBudget))
    }

    @Test
    fun update_isStableAcrossFrames_noDriftCost() {
        // Gọi liên tiếp nhiều frame (smoothing hướng đầu tích luỹ) — chi phí phải ỔN ĐỊNH, không tăng dần
        // (tăng dần = dấu hiệu cấp phát/giữ tham chiếu mỗi frame).
        val firstHalf = Benchmark("update frames 1..5000", warmup = 1_000, iterations = 5_000)
            .measure { model.update(face, w, h, dt) }
        val secondHalf = Benchmark("update frames 5001..10000", warmup = 0, iterations = 5_000)
            .measure { model.update(face, w, h, dt) }
        assertTrue(
            "[perf] chi phí update KHÔNG được phình theo thời gian (nửa sau ${"%.0f".format(secondHalf.nsPerOp)}ns " +
                "vs nửa đầu ${"%.0f".format(firstHalf.nsPerOp)}ns)",
            secondHalf.nsPerOp <= firstHalf.nsPerOp * 2.0 + 50_000.0,
        )
    }
}
