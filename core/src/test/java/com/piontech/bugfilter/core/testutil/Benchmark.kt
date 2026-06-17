package com.piontech.bugfilter.core.testutil

import org.junit.Assert.assertTrue

/**
 * Micro-benchmark harness gọn cho unit "performance" test chạy trên JVM (src/test).
 *
 * KHÔNG phải JMH — đây là phép đo throughput đủ dùng để: (1) in ns/op + ops/s + chi phí mỗi frame ra console
 * để con người soi headroom; (2) ASSERT một trần NỚI RỘNG để bắt regression nghiêm trọng (O(n²), cấp phát rác
 * mỗi frame, vòng lặp thừa) mà KHÔNG flaky theo tốc độ máy/CI.
 *
 * Cách dùng:
 *   val r = Benchmark("imageToTex").measure { mapper.imageToTex(...) }
 *   r.assertUnderNsPerOp(3_000.0)        // hoặc assertUnderMsPerOp(...)
 *
 * Tín hiệu THẬT là con số IN RA; assert chỉ là lưới an toàn. Số đo trên CI yếu sẽ to hơn máy dev — đặt trần
 * theo "phần của 16.6ms (60fps)" chứ đừng siết theo ns tuyệt đối.
 */
class Benchmark(
    val name: String,
    val warmup: Int = 10_000,
    val iterations: Int = 100_000,
) {
    /** Kết quả 1 lần đo. */
    class Result(val name: String, val iterations: Int, val totalNs: Long) {
        val nsPerOp: Double get() = totalNs.toDouble() / iterations
        val opsPerSec: Double get() = if (nsPerOp > 0) 1_000_000_000.0 / nsPerOp else Double.POSITIVE_INFINITY
        val msPerOp: Double get() = nsPerOp / 1_000_000.0

        /** Trần ns/op (nới rộng) — bắt regression thuật toán/cấp phát, không siết theo từng máy. */
        fun assertUnderNsPerOp(maxNs: Double): Result = apply {
            assertTrue(
                "[perf][FAIL] $name = ${"%.1f".format(nsPerOp)} ns/op vượt trần $maxNs ns/op " +
                    "(nghi regression: thuật toán xấu đi hoặc cấp phát rác mỗi op)",
                nsPerOp <= maxNs,
            )
        }

        /** Trần ms/op — tiện khi 1 "op" = trọn 1 frame; so trực tiếp với budget 60fps. */
        fun assertUnderMsPerOp(maxMs: Double): Result = apply {
            assertTrue(
                "[perf][FAIL] $name = ${"%.4f".format(msPerOp)} ms/op vượt trần $maxMs ms " +
                    "(budget 60fps = ${"%.2f".format(FRAME_BUDGET_MS)} ms)",
                msPerOp <= maxMs,
            )
        }
    }

    /**
     * Warmup (để JIT compile) rồi đo [iterations] lần. [block] crossinline → không tính phí gọi lambda ảo
     * vào ns/op (tránh thổi phồng số đo của thao tác ~chục ns).
     */
    inline fun measure(crossinline block: () -> Unit): Result {
        repeat(warmup) { block() }
        val start = System.nanoTime()
        repeat(iterations) { block() }
        val total = System.nanoTime() - start
        val r = Result(name, iterations, total)
        println(format(r))
        return r
    }

    companion object {
        /** Ngân sách 1 frame ở 60fps. Toàn bộ compute mỗi frame của :core nên nằm gọn DƯỚI mức này. */
        const val FRAME_BUDGET_MS = 1000.0 / 60.0   // ≈ 16.667 ms

        fun format(r: Result): String = String.format(
            "[perf] %-34s %,12.1f ns/op  %,15.0f ops/s  %9.4f ms/op  (%,d iters)",
            r.name, r.nsPerOp, r.opsPerSec, r.msPerOp, r.iterations,
        )
    }
}
