package com.piontech.bugfilter.core.render

import kotlin.math.hypot

/**
 * Máy trạng thái cho con bọ BÒ theo ĐỒ THỊ ĐOẠN (như app gốc): mỗi frame ở 1 đoạn (cạnh), hết đoạn
 * chọn đoạn kế (nối tại landmark chung; ngẫu nhiên), đi 2 chiều nên luôn nối liền — KHÔNG teleport.
 * Tới landmark 168 (mũi) → ẩn + delay → "chui vào mũi rồi bò ra".
 *
 * [advance] mỗi frame cập nhật [segA]/[segB]/[segLocalT] (đoạn nội suy vị trí hiện tại) + bug.hideFactor.
 * Đọc hình học bề mặt từ [FaceSurfaceModel] (vị trí đỉnh, số đỉnh).
 */
class BugWalker {

    // Kết quả lấy mẫu vị trí trên path: nội suy giữa đỉnh segA→segB theo segLocalT (0..1).
    var segA = 0
        private set
    var segB = 0
        private set
    var segLocalT = 0f
        private set

    private val rng = java.util.Random()
    private val segCum = FloatArray(32)  // chiều dài tích lũy đoạn hiện tại (đoạn tối đa ~11 điểm)
    private val segEff = IntArray(32)    // dãy index theo CHIỀU đi (xuôi/ngược) của đoạn hiện tại

    /** Dựng danh sách ĐOẠN (cạnh đồ thị) từ scrip.listPath để bọ đi theo đồ thị (app gốc). */
    fun buildPath(bug: Bug) {
        val segs = ArrayList<Seg>()
        for (p in bug.scrip.listPath) {
            val idx = p.listPathIndex
            if (idx.size < 2) continue
            val arr = idx.toIntArray()
            segs.add(Seg(arr, p.delayTimeStartSecond, p.delayTimeEndSecond,
                arr.first() == NOSE_IDX, arr.last() == NOSE_IDX))
        }
        bug.segs = segs
        bug.curSeg = 0
        bug.segDir = 1
        bug.segPhase = 0f
        bug.walkState = 0
        bug.delayTimer = segs.getOrNull(0)?.delayStart ?: 0f
        bug.hideFactor = 1f
    }

    /**
     * Tiến trạng thái đi-đồ-thị (2 chiều) 1 bước dt → cập nhật segA/segB/segLocalT + bug.hideFactor.
     * hideFactor co bọ về 0 quanh CỬA MŨI khi vào và lớn lại khi ra → "chui vào mũi / bò ra" mượt.
     */
    fun advance(bug: Bug, dt: Float, faceW: Float, surface: FaceSurfaceModel) {
        if (bug.segs.isEmpty()) { segA = 1; segB = 1; segLocalT = 0f; bug.hideFactor = 1f; return }
        var seg = bug.segs[bug.curSeg]; var dir = bug.segDir
        var n = buildEff(seg, dir); var total = segCumulative(surface, n)
        val lv = leavingNose(seg, dir); val en = enteringNose(seg, dir)
        when (bug.walkState) {
            0 -> {                                  // delay đầu đoạn (ẩn hẳn nếu vừa từ mũi ra)
                bug.hideFactor = if (lv) 0f else 1f
                if (bug.delayTimer > 0f) bug.delayTimer -= dt else bug.walkState = 1
            }
            1 -> {                                  // bò dọc đoạn (tốc độ đều theo độ dài pixel)
                val adv = CRAWL_FRAC_PER_SEC * PATH_SPEED_K * faceW / total.coerceAtLeast(1f)
                bug.segPhase += adv * dt
                val tot = total.coerceAtLeast(1e-3f)
                var hf = 1f
                if (en) {   // VÀO MŨI: full tới gần cửa mũi, co & biến mất tại điểm cách 167/393 một đoạn NOSE_DEPTH LÊN phía 168 (≈ lỗ mũi); phần còn lại →168 vô hình
                    val pNostril = if (n >= 2) (segCum[n-2] / tot).coerceIn(0f, 1f) else 0f
                    val pVanish = pNostril + NOSE_DEPTH * (1f - pNostril)                 // điểm biến mất (đẩy lên phía 168)
                    val win = (NOSE_SCALE_FRAC * (1f - pNostril)).coerceAtLeast(0.02f)     // độ dài cú co (tốc độ)
                    hf = minOf(hf, smooth01((pVanish - bug.segPhase) / win))
                }
                if (lv) {   // RA MŨI: vô hình tới điểm cách 167/393 một đoạn NOSE_DEPTH lên phía 168, rồi HIỆN & lớn dần bò ra (đối xứng lúc vào)
                    val pNostril = if (n >= 2) (segCum[1] / tot).coerceIn(0f, 1f) else 0f
                    val pAppear = pNostril * (1f - NOSE_DEPTH)                            // điểm hiện ra
                    val win = (NOSE_SCALE_FRAC * pNostril).coerceAtLeast(0.02f)
                    hf = minOf(hf, smooth01((bug.segPhase - pAppear) / win))
                }
                bug.hideFactor = hf
                if (bug.segPhase >= 1f) { bug.segPhase = 1f; bug.walkState = 2; bug.delayTimer = travDelayEnd(seg, dir) }
            }
            else -> {                               // delay cuối đoạn (ẩn hẳn nếu vừa chui vào mũi)
                bug.hideFactor = if (en) 0f else 1f
                if (bug.delayTimer > 0f) bug.delayTimer -= dt else pickNext(bug)
            }
        }
        // Lấy mẫu vị trí trên đoạn+chiều HIỆN TẠI (có thể đã đổi ở state 2).
        seg = bug.segs[bug.curSeg]; dir = bug.segDir
        n = buildEff(seg, dir); total = segCumulative(surface, n)
        sampleSeg(surface, n, total, bug.segPhase)
    }

    /** Dựng dãy index theo CHIỀU đi (dir=+1 xuôi, -1 ngược) vào [segEff]; trả về số phần tử. */
    private fun buildEff(seg: Seg, dir: Int): Int {
        val n = minOf(seg.idx.size, segEff.size)
        if (dir > 0) for (i in 0 until n) segEff[i] = seg.idx[i]
        else { val last = seg.idx.size - 1; for (i in 0 until n) segEff[i] = seg.idx[last - i] }
        return n
    }

    /** Chiều dài tích lũy (pixel) của [segEff][0,n) vào [segCum]; trả về tổng. */
    private fun segCumulative(surface: FaceSurfaceModel, n: Int): Float {
        val vp = surface.vertPos; val vc = surface.vertCount
        segCum[0] = 0f
        var acc = 0f
        for (i in 1 until n) {
            val ia = segEff[i-1].coerceIn(0, vc-1); val ib = segEff[i].coerceIn(0, vc-1)
            acc += hypot(vp[ib*3]-vp[ia*3], vp[ib*3+1]-vp[ia*3+1])
            segCum[i] = acc
        }
        return acc
    }

    /** Đặt segA/segB/segLocalT theo phase (0..1) dọc [segEff][0,n) (đã có [segCum] + total). */
    private fun sampleSeg(surface: FaceSurfaceModel, n: Int, total: Float, phase: Float) {
        val vc = surface.vertCount
        if (n < 2) { val v = (if (n == 1) segEff[0] else NOSE_IDX).coerceIn(0, vc-1); segA=v; segB=v; segLocalT=0f; return }
        if (total <= 1e-3f) { segA=segEff[0].coerceIn(0,vc-1); segB=segEff[1].coerceIn(0,vc-1); segLocalT=0f; return }
        val target = phase.coerceIn(0f, 1f) * total
        var i = 0
        while (i < n - 2 && segCum[i+1] < target) i++
        val segLen = segCum[i+1] - segCum[i]
        segA = segEff[i].coerceIn(0, vc-1); segB = segEff[i+1].coerceIn(0, vc-1)
        segLocalT = if (segLen > 1e-4f) ((target - segCum[i]) / segLen).coerceIn(0f, 1f) else 0f
    }

    // Đầu/cuối CHIỀU đi (có hướng) + cờ mũi + delay tương ứng. App gốc đi 2 chiều nên luôn nối liền.
    private fun endNodeIdx(seg: Seg, dir: Int) = if (dir > 0) seg.idx.last() else seg.idx.first()
    private fun leavingNose(seg: Seg, dir: Int) = if (dir > 0) seg.startNose else seg.endNose
    private fun enteringNose(seg: Seg, dir: Int) = if (dir > 0) seg.endNose else seg.startNose
    private fun travDelayStart(seg: Seg, dir: Int) = if (dir > 0) seg.delayStart else seg.delayEnd
    private fun travDelayEnd(seg: Seg, dir: Int) = if (dir > 0) seg.delayEnd else seg.delayStart

    /** Chọn (đoạn, chiều) kế tiếp: bất kỳ đoạn CHẠM node hiện tại (2 chiều) → luôn nối liền, KHÔNG teleport.
     *  Tránh quay-ngược-ngay đoạn vừa đi nếu còn lựa chọn khác (đỡ đi tới lui 1 đoạn). */
    private fun pickNext(bug: Bug) {
        val segs = bug.segs
        val node = endNodeIdx(segs[bug.curSeg], bug.segDir)
        // đếm ứng viên (loại trừ quay-ngược-ngay), nếu rỗng thì cho phép quay ngược.
        var count = 0
        for (i in segs.indices) {
            val s = segs[i]
            val backtrack = (i == bug.curSeg)
            if (s.idx.first() == node && !(backtrack && bug.segDir < 0)) count++
            if (s.idx.last() == node && !(backtrack && bug.segDir > 0)) count++
        }
        val allowBack = count == 0
        if (allowBack) {   // chỉ còn cách quay ngược đoạn hiện tại
            bug.segDir = -bug.segDir
        } else {
            var pick = rng.nextInt(count)
            outer@ for (i in segs.indices) {
                val s = segs[i]; val backtrack = (i == bug.curSeg)
                if (s.idx.first() == node && !(backtrack && bug.segDir < 0)) { if (pick == 0) { bug.curSeg = i; bug.segDir = 1; break@outer }; pick-- }
                if (s.idx.last() == node && !(backtrack && bug.segDir > 0)) { if (pick == 0) { bug.curSeg = i; bug.segDir = -1; break@outer }; pick-- }
            }
        }
        bug.segPhase = 0f; bug.walkState = 0
        bug.delayTimer = travDelayStart(bug.segs[bug.curSeg], bug.segDir)
    }

    private fun smooth01(x: Float): Float { val c = x.coerceIn(0f, 1f); return c*c*(3f - 2f*c) }

    companion object {
        /** Landmark "mũi" (app gốc check ==168): đoạn path bắt đầu/kết thúc ở đây → bọ ẩn (chui vào mũi). */
        private const val NOSE_IDX = 168
        /** Tốc độ bò cơ bản theo BỀ NGANG MẶT mỗi giây (×PATH_SPEED_K). Đoạn dài → đi lâu hơn (tốc độ đều). */
        private const val CRAWL_FRAC_PER_SEC = 0.28f
        /** Hệ số tốc độ bò toàn cục (nhân với progressPerSecond của scrip). <1 → chậm lại, đỡ "vội". */
        private const val PATH_SPEED_K = 0.85f
        /** Điểm bọ biến mất/hiện ra: cách CỬA MŨI (167/393) một đoạn NOSE_DEPTH HƯỚNG LÊN 168 (giữa 2 mắt).
         *  0 = ngay cửa mũi (thấp, ~nhân trung); lớn hơn = đẩy CAO dần lên sống mũi. ~0.2–0.35 ≈ lỗ mũi. */
        private const val NOSE_DEPTH = 0.3f
        /** Tốc độ co/lớn ở vùng mũi: nhỏ = nhanh gọn; lớn = trải dài chậm (luôn mượt nhờ smooth01). */
        private const val NOSE_SCALE_FRAC = 0.4f
    }
}
