package com.piontech.bugfilter.core.render

import com.piontech.bugfilter.core.face.FaceResult

/**
 * Quy đổi toạ độ landmark ML Kit → hệ pixel của output texture (landscape) của Filament.
 *
 * Vì output texture nằm NGANG còn hiển thị DỌC, phép map gồm: bù xoay khi cầm máy nghiêng (đưa landmark
 * về khung PORTRAIT cố định) + FILL_CENTER + [mirror] (camera trước) + xoay 90° sang texture.
 *
 * [mirror] được set 1 lần mỗi frame trước khi map (lấy từ [FaceTracker]).
 */
class FaceCoordinateMapper {

    /** Lật ngang cho camera trước. */
    var mirror: Boolean = true

    /**
     * Quy đổi 1 điểm ảnh ML Kit (ix,iy ở hệ ảnh dựng đứng imgW×imgH) → toạ độ world của Filament
     * (đúng hệ pixel của output texture landscape w×h).
     */
    fun imageToTex(face: FaceResult, ix: Float, iy: Float, w: Int, h: Int, out: FloatArray) {
        // BÙ xoay khi cầm máy nghiêng: ML Kit upright ảnh theo orientation VẬT LÝ (rotationDegrees đổi theo
        // máy) nhưng UI khoá portrait → output luôn portrait. Đưa landmark về khung PORTRAIT (xoay dR =
        // PORTRAIT_ROT − rotationDegrees) rồi map CỐ ĐỊNH như portrait → bọ luôn bám đúng mặt ở mọi tư thế.
        val rot = ((face.rotationDegrees % 360) + 360) % 360
        val dR = ((PORTRAIT_ROT - rot) % 360 + 360) % 360
        var lx = ix; var ly = iy
        var iw = face.imageWidth.toFloat(); var ih = face.imageHeight.toFloat()
        when (dR) {
            90  -> { val nx = ih - ly; val ny = lx;      lx = nx; ly = ny; val t = iw; iw = ih; ih = t }
            180 -> { lx = iw - lx; ly = ih - ly }
            270 -> { val nx = ly;      val ny = iw - lx; lx = nx; ly = ny; val t = iw; iw = ih; ih = t }
        }
        // Giờ landmark ở khung PORTRAIT (rotationDegrees hiệu dụng = PORTRAIT_ROT=270 → swap). Map như cũ.
        val uw = h.toFloat(); val uh = w.toFloat()
        val scale = maxOf(uw / iw, uh / ih)
        var a = (lx * scale + (uw - iw * scale) / 2f) / uw   // trái-phải (upright)
        val b = (ly * scale + (uh - ih * scale) / 2f) / uh   // trên-dưới (upright)
        if (mirror) a = 1f - a
        val u = 1f - b; val v = a    // case PORTRAIT_ROT=270: upright → texture
        out[0] = u * w
        out[1] = (1f - v) * h        // lật Y theo gốc toạ độ texture GL (bottom-left)
    }

    fun texScale(face: FaceResult, w: Int, h: Int): Float {
        val rot = ((face.rotationDegrees % 360) + 360) % 360
        val dR = ((PORTRAIT_ROT - rot) % 360 + 360) % 360
        val swapImg = dR == 90 || dR == 270
        val iw = (if (swapImg) face.imageHeight else face.imageWidth).toFloat()
        val ih = (if (swapImg) face.imageWidth else face.imageHeight).toFloat()
        return maxOf(h.toFloat() / iw, w.toFloat() / ih)
    }

    fun faceWidthPx(face: FaceResult, w: Int, h: Int): Float {
        val pts = face.faceMesh.allPoints
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        val scale = texScale(face, w, h)
        for (pt in pts) {
            val x = pt.position.x * scale
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        return (maxX - minX).coerceAtLeast(1f)
    }

    /** Toạ độ 3D (world pixel + depth) của một landmark — dùng cùng phép quy đổi như [imageToTex]. */
    fun world3(face: FaceResult, idx: Int, w: Int, h: Int, out: FloatArray) {
        val p = face.faceMesh.allPoints[idx].position
        imageToTex(face, p.x, p.y, w, h, out)
        out[2] = -p.z * texScale(face, w, h) * Z_SIGN
    }

    companion object {
        /** rotationDegrees khi máy ở tư thế PORTRAIT (UI khoá portrait). Front-cam thường = 270.
         *  Dùng để BÙ khi cầm máy nghiêng: đưa landmark về khung portrait rồi map cố định → bọ luôn bám mặt. */
        private const val PORTRAIT_ROT = 270
        /** Dấu của trục z (depth) lấy từ ML Kit; đổi nếu bọ nghiêng ngược. */
        private const val Z_SIGN = 1f
    }
}
