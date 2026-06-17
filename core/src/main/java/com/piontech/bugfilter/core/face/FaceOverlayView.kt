package com.piontech.bugfilter.core.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * View trong suốt phủ lên PreviewView, vẽ các điểm landmark khuôn mặt để kiểm chứng
 * việc nhận diện hoạt động ở Stage 1. Sang Stage 2 phần render bọ sẽ chuyển vào layer GL/Filament,
 * view này có thể tắt đi (chỉ dùng debug).
 *
 * Quy đổi toạ độ: PreviewView mặc định scale kiểu FILL_CENTER → dùng scale = max(...) + canh giữa.
 * Camera trước cần lật ngang (mirror) để khớp ảnh hiển thị.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var result: FaceResult? = null
    private var mirror: Boolean = true

    fun setResult(result: FaceResult?, mirror: Boolean) {
        this.result = result
        this.mirror = mirror
        postInvalidateOnAnimation()
    }

    private val tmp = FloatArray(2)

    /** Đưa điểm (px,py) ảnh ML Kit về khung PORTRAIT (bù khi cầm máy nghiêng), kết quả vào [tmp]. */
    private fun toPortrait(px: Float, py: Float, iw: Int, ih: Int, dR: Int) {
        when (dR) {
            90  -> { tmp[0] = ih - py; tmp[1] = px }
            180 -> { tmp[0] = iw - px; tmp[1] = ih - py }
            270 -> { tmp[0] = py;      tmp[1] = iw - px }
            else -> { tmp[0] = px;     tmp[1] = py }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        if (r.imageWidth == 0 || r.imageHeight == 0) return

        // Bù xoay: ML Kit upright theo máy nhưng UI khoá portrait → đưa landmark về khung portrait.
        val rot = ((r.rotationDegrees % 360) + 360) % 360
        val dR = ((270 - rot) % 360 + 360) % 360
        val swapImg = dR == 90 || dR == 270
        val iw = if (swapImg) r.imageHeight else r.imageWidth
        val ih = if (swapImg) r.imageWidth else r.imageHeight
        val scale = maxOf(width.toFloat() / iw, height.toFloat() / ih)
        val dx = (width - iw * scale) / 2f
        val dy = (height - ih * scale) / 2f

        val points = r.faceMesh.allPoints
        for (p in points) {
            toPortrait(p.position.x, p.position.y, r.imageWidth, r.imageHeight, dR)
            var vx = tmp[0] * scale + dx
            val vy = tmp[1] * scale + dy
            if (mirror) vx = width - vx
            canvas.drawCircle(vx, vy, 3f, pointPaint)
        }

        // Khung giới hạn khuôn mặt — biến đổi 2 góc rồi lấy min/max (sau xoay vẫn là hình chữ nhật).
        val box = r.faceMesh.boundingBox
        toPortrait(box.left.toFloat(), box.top.toFloat(), r.imageWidth, r.imageHeight, dR)
        val x1 = tmp[0]; val y1 = tmp[1]
        toPortrait(box.right.toFloat(), box.bottom.toFloat(), r.imageWidth, r.imageHeight, dR)
        val x2 = tmp[0]; val y2 = tmp[1]
        var left = minOf(x1, x2) * scale + dx
        var right = maxOf(x1, x2) * scale + dx
        val top = minOf(y1, y2) * scale + dy
        val bottom = maxOf(y1, y2) * scale + dy
        if (mirror) { val l = width - right; val rr = width - left; left = l; right = rr }
        canvas.drawRect(left, top, right, bottom, boxPaint)
    }
}
