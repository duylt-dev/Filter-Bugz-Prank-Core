package com.piontech.bugfilter.core.render

import com.google.android.filament.gltfio.FilamentInstance
import com.piontech.bugfilter.core.model.BugScrip

/** Một ĐOẠN path (cạnh đồ thị): dãy index landmark, từ idx[0] → idx[last], + delay 2 đầu. */
class Seg(
    val idx: IntArray,
    val delayStart: Float,
    val delayEnd: Float,
    val startNose: Boolean,   // idx[0]==168 → ẩn bọ lúc rời mũi (hiện dần sau bước đầu)
    val endNose: Boolean      // idx[last]==168 → ẩn bọ khi chui vào mũi (ở bước cuối)
)

/**
 * Một con bọ = 1 [FilamentInstance] (chia sẻ geometry) + 1 [scrip] + trạng thái path/smoothing riêng.
 *
 * Trạng thái WALK ([BugWalker] cập nhật): đi theo ĐỒ THỊ ĐOẠN (như app gốc), mỗi frame ở 1 đoạn, hết đoạn
 * chọn đoạn kế (nối tại landmark chung). Tới landmark 168 (mũi) → ẩn + delay → "chui vào mũi rồi bò ra".
 *
 * Trạng thái SMOOTHING (FilamentRenderer.placeBug cập nhật): làm mượt vị trí + hướng qua thời gian.
 */
class Bug(val scrip: BugScrip, val instance: FilamentInstance) {
    // --- Walk state ---
    var segs: List<Seg> = emptyList()
    var curSeg = 0
    var segDir = 1             // +1 = đi xuôi idx, -1 = đi ngược (đồ thị 2 chiều → không teleport)
    var segPhase = 0f          // 0..1 dọc đoạn hiện tại
    var walkState = 0          // 0=delayStart, 1=crawl, 2=delayEnd
    var delayTimer = 0f        // thời gian delay còn lại (giây)
    var hideFactor = 1f        // 1=hiện rõ, 0=ẩn hẳn (trong mũi) — thu nhỏ DẦN để "chui vào trong"

    // --- Smoothing state ---
    var smX = 0f; var smY = 0f; var smZ = 0f; var smInit = false
    // Làm mượt HƯỚNG (pháp tuyến + tiếp tuyến) qua thời gian → quay đầu không giật ở góc path.
    var smNx = 0f; var smNy = 0f; var smNz = 1f
    var smTx = 1f; var smTy = 0f; var smTz = 0f
    var smOriInit = false
    var animStartNs = 0L
}
