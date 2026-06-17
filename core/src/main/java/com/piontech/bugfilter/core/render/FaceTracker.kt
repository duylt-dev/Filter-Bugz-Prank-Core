package com.piontech.bugfilter.core.render

import com.piontech.bugfilter.core.face.FaceResult

/**
 * Buffer vòng các face mesh gần nhất (mỗi cái có timestamp) — như app gốc giữ ~8 — để khi render
 * chọn mesh KHỚP timestamp frame đang vẽ → bọ không lệch khi rung cam.
 *
 * Thread-safe: [push] gọi từ luồng analysis nền; [pick]/[isStale] gọi từ GL thread.
 */
class FaceTracker(capacity: Int = 10) {

    private val faceBuf = arrayOfNulls<FaceResult>(capacity)
    private var faceBufPos = 0
    private val lock = Any()

    /** Lật ngang cho camera trước (đặt cùng lúc với [push]). */
    @Volatile var mirror: Boolean = true
        private set

    @Volatile private var faceTimeNs: Long = 0L

    /** Đẩy 1 mặt mới vào buffer (gọi từ luồng analysis nền).
     *  Khi ML Kit rớt 1 frame (result=null) thì GIỮ buffer cũ — [isStale] tự bỏ sau ~300ms. */
    fun push(result: FaceResult?, mirror: Boolean) {
        this.mirror = mirror
        if (result != null) {
            synchronized(lock) {
                faceBuf[faceBufPos] = result
                faceBufPos = (faceBufPos + 1) % faceBuf.size
            }
            this.faceTimeNs = System.nanoTime()
        }
    }

    /** Chọn mesh trong buffer có timestamp GẦN NHẤT với frame đang render (đồng bộ như app gốc). */
    fun pick(frameTs: Long): FaceResult? {
        synchronized(lock) {
            var best: FaceResult? = null
            var bestDiff = Long.MAX_VALUE
            for (f in faceBuf) {
                if (f == null) continue
                val d = Math.abs(f.timestampNs - frameTs)
                if (d < bestDiff) { bestDiff = d; best = f }
            }
            return best
        }
    }

    /** Mặt đã quá cũ (mất nhận diện thật sự) — nhưng giữ ~300ms để không nháy. */
    fun isStale(nowNs: Long): Boolean = nowNs - faceTimeNs > 300_000_000L
}
