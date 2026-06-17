package com.piontech.bugfilter.core.render

import com.piontech.bugfilter.core.face.FaceResult
import com.piontech.bugfilter.core.testutil.Benchmark
import com.piontech.bugfilter.core.testutil.FakeFace
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hiệu năng quy đổi toạ độ — chạy DÀY ĐẶC nhất trong pipeline: imageToTex gọi cho TỪNG landmark, và
 * FaceSurfaceModel còn gọi ~2× (world3 + bboxTex) mỗi frame ⇒ ~936 lần/frame. Nên hằng số ở đây quyết định
 * trần FPS. Test bắt regression nếu ai đó nhét cấp phát / nhánh đắt vào hàm hot này.
 *
 * Trần assert NỚI RỘNG (gấp nhiều lần số thực ~vài chục ns) để không flaky trên CI; tín hiệu thật là dòng [perf].
 */
class FaceCoordinateMapperPerfTest {

    private lateinit var mapper: FaceCoordinateMapper
    private lateinit var face: FaceResult
    private val out = FloatArray(3)
    private val w = FakeFace.TEX_W
    private val h = FakeFace.TEX_H

    @Before
    fun setUp() {
        mapper = FaceCoordinateMapper().apply { mirror = true }
        face = FakeFace.faceResult()
    }

    @Test
    fun imageToTex_singleCall_isCheap() {
        // imageToTex KHÔNG đụng faceMesh — chỉ rotation/imageW/H + vài phép số học → phải sub-microsecond.
        Benchmark("imageToTex (1 landmark)", iterations = 500_000)
            .measure { mapper.imageToTex(face, 123.4f, 567.8f, w, h, out) }
            .assertUnderNsPerOp(2_000.0)
        assertTrue("out phải hữu hạn", out[0].isFinite() && out[1].isFinite())
    }

    @Test
    fun imageToTex_fullFrame_468landmarks_underBudget() {
        val pts = FakeFace.landmarkPoints()
        // Map TRỌN một frame landmark: đây là chi phí mapping thuần mỗi frame, phải << 16.6ms.
        Benchmark("imageToTex x468 (1 frame)", warmup = 2_000, iterations = 20_000)
            .measure {
                for (p in pts) {
                    val pos = p.position
                    mapper.imageToTex(face, pos.x, pos.y, w, h, out)
                }
            }
            .assertUnderMsPerOp(1.5)
    }

    @Test
    fun faceWidthPx_iteratesAllPoints_isCheap() {
        var sink = 0f
        Benchmark("faceWidthPx (x468)", warmup = 2_000, iterations = 50_000)
            .measure { sink += mapper.faceWidthPx(face, w, h) }
            .assertUnderMsPerOp(0.5)
        assertTrue(sink > 0f)
    }

    @Test
    fun world3_perLandmark_isCheap() {
        Benchmark("world3 (1 landmark)", iterations = 300_000)
            .measure { mapper.world3(face, 234, w, h, out) }
            .assertUnderNsPerOp(3_000.0)
        assertTrue(out[2].isFinite())
    }

    @Test
    fun imageToTex_allRotationBranches_staySimilar() {
        // Bù xoay (dR = 90/180/270) thêm vài phép gán — không được đắt hơn case 0 quá nhiều.
        val base = Benchmark("imageToTex rot=270 (dR=0)", iterations = 300_000)
            .measure { mapper.imageToTex(face, 100f, 200f, w, h, out) }
        for (rot in intArrayOf(0, 90, 180)) {
            val f = FakeFace.faceResult(rotationDegrees = rot)
            Benchmark("imageToTex rot=$rot", iterations = 300_000)
                .measure { mapper.imageToTex(f, 100f, 200f, w, h, out) }
                .assertUnderNsPerOp(base.nsPerOp * 4.0 + 500.0)
        }
    }
}
