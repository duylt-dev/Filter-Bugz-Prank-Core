package com.piontech.bugfilter.core.render

import android.opengl.Matrix
import com.google.android.filament.Box
import com.google.android.filament.Engine
import kotlin.math.hypot

/**
 * Ốp MỘT con bọ lên bề mặt mặt: lấy vị trí/pháp tuyến/tiếp tuyến từ [FaceSurfaceModel] tại đoạn path mà
 * [BugWalker] đang chỉ tới, giãn ra mép + clamp bbox, làm mượt VỊ TRÍ và HƯỚNG (thích nghi theo tốc độ di
 * chuyển để vừa chống rung vừa bám sát khi xoay/rung cam), rồi dựng ma trận transform set vào instance.
 *
 * Scratch (matrices + pos) tái dùng giữa các frame; chỉ gọi từ GL thread nên không cần đồng bộ.
 */
class BugPlacer {

    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val headBasis = FloatArray(16)   // ma trận basis bề mặt
    private val pos3 = FloatArray(3)         // temp vị trí bọ

    /**
     * @param box bounding box của asset GLB (center + halfExtent) để quy đổi kích thước theo bề ngang mặt.
     * @param modelYaw/[modelSize] override per-model (null = dùng mặc định global).
     */
    fun place(
        eng: Engine, bug: Bug, dt: Float, faceW: Float,
        surface: FaceSurfaceModel, walker: BugWalker,
        box: Box, modelYaw: Float?, modelSize: Float?
    ) {
        val sc = bug.scrip
        if (surface.vertCount == 0) return
        // Đi theo ĐỒ THỊ ĐOẠN 2 chiều (app gốc) → cập nhật segA/segB/segLocalT + bug.hideFactor (thu nhỏ khi chui mũi).
        walker.advance(bug, dt, faceW, surface)
        var a = walker.segA; var b = walker.segB; var t = walker.segLocalT
        if (DEBUG_PIN_INDEX >= 0) { a = DEBUG_PIN_INDEX; b = DEBUG_PIN_INDEX; t = 0f; bug.hideFactor = 1f }
        val vp = surface.vertPos; val vn = surface.vertNrm
        val ai = a.coerceIn(0, surface.vertCount - 1); val bi = b.coerceIn(0, surface.vertCount - 1)
        // Vị trí 3D = nội suy giữa 2 đỉnh path.
        pos3[0] = vp[ai*3] + (vp[bi*3] - vp[ai*3]) * t
        pos3[1] = vp[ai*3+1] + (vp[bi*3+1] - vp[ai*3+1]) * t
        pos3[2] = vp[ai*3+2] + (vp[bi*3+2] - vp[ai*3+2]) * t
        // Pháp tuyến THÔ = nội suy normal đỉnh (chuẩn hoá).
        var nrx = vn[ai*3] + (vn[bi*3] - vn[ai*3]) * t
        var nry = vn[ai*3+1] + (vn[bi*3+1] - vn[ai*3+1]) * t
        var nrz = vn[ai*3+2] + (vn[bi*3+2] - vn[ai*3+2]) * t
        var nl = Math.sqrt((nrx*nrx+nry*nry+nrz*nrz).toDouble()).toFloat()
        if (nl < 1e-5f) { nrx=0f; nry=0f; nrz=1f; nl=1f }
        nrx/=nl; nry/=nl; nrz/=nl
        // Tiếp tuyến THÔ (hướng bò) = A→B theo MÀN HÌNH (z=0). Đi 1 chiều nên không đảo.
        var trx = (vp[bi*3]-vp[ai*3])
        var tryy = (vp[bi*3+1]-vp[ai*3+1])
        var trz = 0f
        var trl = Math.sqrt((trx*trx+tryy*tryy+trz*trz).toDouble()).toFloat()
        if (trl < 1e-5f) { trx=1f; tryy=0f; trz=0f; trl=1f }
        trx/=trl; tryy/=trl; trz/=trl

        // --- GIÃN vị trí ra mép (bù khoảng trắng asset) + clamp mở rộng. Làm SỚM để tính tốc độ di chuyển. ---
        val expand = 1f + BBOX_OVERFLOW
        pos3[0] = surface.centerX + (pos3[0]-surface.centerX)*expand
        pos3[1] = surface.centerY + (pos3[1]-surface.centerY)*expand
        val bbox = surface.bbox
        val mx = (bbox[2]-bbox[0]) * 0.5f * BBOX_OVERFLOW
        val my = (bbox[3]-bbox[1]) * 0.5f * BBOX_OVERFLOW
        pos3[0] = pos3[0].coerceIn(bbox[0]-mx, bbox[2]+mx)
        pos3[1] = pos3[1].coerceIn(bbox[1]-my, bbox[3]+my)

        // Tốc độ di chuyển mục tiêu (0=đứng yên, 1=nhanh) — DÙNG CHUNG cho mượt VỊ TRÍ lẫn HƯỚNG để khi
        // xoay mặt/rung cam, vị trí và hướng SNAP CÙNG NHỊP (tránh "vị trí đúng nhưng hướng trễ rồi mới đúng").
        val moved = if (bug.smInit) hypot(pos3[0]-bug.smX, pos3[1]-bug.smY) else 0f
        val spd = (moved / (faceW * POS_SNAP_FRAC)).coerceIn(0f, 1f)

        // --- Làm mượt HƯỚNG (thích nghi cùng spd): nhanh → snap; chậm → mượt chống rung & giật góc path. ---
        val oriTau = ORIENT_TAU + (ORIENT_TAU_FAST - ORIENT_TAU) * spd
        val aOri = 1f - Math.exp((-dt / oriTau).toDouble()).toFloat()
        if (!bug.smOriInit) {
            bug.smNx=nrx; bug.smNy=nry; bug.smNz=nrz; bug.smTx=trx; bug.smTy=tryy; bug.smTz=trz
            bug.smOriInit=true
        } else {
            bug.smNx += (nrx-bug.smNx)*aOri; bug.smNy += (nry-bug.smNy)*aOri; bug.smNz += (nrz-bug.smNz)*aOri
            bug.smTx += (trx-bug.smTx)*aOri; bug.smTy += (tryy-bug.smTy)*aOri; bug.smTz += (trz-bug.smTz)*aOri
        }
        // Chuẩn hoá + trực giao hoá lại từ giá trị đã mượt → dựng basis.
        var nx = bug.smNx; var ny = bug.smNy; var nz = bug.smNz
        var snl = Math.sqrt((nx*nx+ny*ny+nz*nz).toDouble()).toFloat()
        if (snl < 1e-5f) { nx=0f; ny=0f; nz=1f; snl=1f }
        nx/=snl; ny/=snl; nz/=snl
        var tx = bug.smTx; var ty = bug.smTy; var tz = bug.smTz
        val tdotn = tx*nx+ty*ny+tz*nz
        tx -= nx*tdotn; ty -= ny*tdotn; tz -= nz*tdotn
        var tl = Math.sqrt((tx*tx+ty*ty+tz*tz).toDouble()).toFloat()
        if (tl < 1e-4f) { tx=trx; ty=tryy; tz=trz; tl=1f }   // qua điểm lật 180° → dùng tiếp tuyến thô
        tx/=tl; ty/=tl; tz/=tl
        // right = N × T
        val rx = ny*tz - nz*ty; val ry = nz*tx - nx*tz; val rz = nx*ty - ny*tx

        // --- Làm mượt VỊ TRÍ (thích nghi cùng spd). ---
        if (!bug.smInit) { bug.smX = pos3[0]; bug.smY = pos3[1]; bug.smZ = pos3[2]; bug.smInit = true }
        else {
            val posTau = POS_TAU_STILL + (POS_TAU_FAST - POS_TAU_STILL) * spd
            val aPos = 1f - Math.exp((-dt / posTau).toDouble()).toFloat()
            bug.smX += (pos3[0]-bug.smX)*aPos; bug.smY += (pos3[1]-bug.smY)*aPos; bug.smZ += (pos3[2]-bug.smZ)*aPos
        }

        val scripScale = sc.scale
        val sizeK = modelSize ?: SIZE_K                 // override per-model nếu có
        val desiredPx = faceW * scripScale * sizeK

        // Bóng tiếp xúc giờ do processor vẽ theo SILHOUETTE bọ (alpha texture), không cần footprint.
        val c = box.center
        val half = box.halfExtent
        val radius = maxOf(half[0], maxOf(half[1], half[2])).coerceAtLeast(1e-3f)
        // Ẩn (chui vào mũi) → scale 0 → bọ + bóng biến mất; vị trí vẫn cập nhật để bò ra mượt.
        val s = (desiredPx / (radius * 2f)) * bug.hideFactor   // hideFactor 1→0 = thu nhỏ dần vào mũi rồi mất

        // Hệ trục cục bộ của bề mặt: col0=right, col1=normal(up), col2=tangent(forward).
        val basis = headBasis
        basis[0]=rx; basis[1]=ry; basis[2]=rz; basis[3]=0f
        basis[4]=nx; basis[5]=ny; basis[6]=nz; basis[7]=0f
        basis[8]=tx; basis[9]=ty; basis[10]=tz; basis[11]=0f
        basis[12]=0f; basis[13]=0f; basis[14]=0f; basis[15]=1f

        val rot = sc.rotate
        val m = tmp
        val m2 = tmp2
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, bug.smX, bug.smY, bug.smZ)
        Matrix.multiplyMM(m2, 0, m, 0, basis, 0)   // ốp theo bề mặt mặt
        Matrix.rotateM(m2, 0, modelYaw ?: YAW_OFFSET_DEG, 0f, 1f, 0f)   // yaw override per-model nếu có
        Matrix.rotateM(m2, 0, TILT_X_DEG, 1f, 0f, 0f)
        Matrix.rotateM(m2, 0, rot.y, 0f, 1f, 0f)
        Matrix.rotateM(m2, 0, rot.x, 1f, 0f, 0f)
        Matrix.rotateM(m2, 0, rot.z, 0f, 0f, 1f)
        Matrix.scaleM(m2, 0, s, s, s)
        Matrix.translateM(m2, 0, -c[0], -c[1], -c[2])

        val tm = eng.transformManager
        val ti = tm.getInstance(bug.instance.root)
        tm.setTransform(ti, m2)
    }

    companion object {
        /** DEBUG: ghim bọ vào 1 landmark cố định để soi mapping (-1 = tắt, dùng path bình thường). */
        private const val DEBUG_PIN_INDEX = -1
        /** Với basis bề mặt cục bộ, model Y-up đã khớp pháp tuyến → KHÔNG cần tilt thêm.
         *  (Đổi sang 90/-90 nếu model gốc không phải Y-up khiến bọ bị edge-on.) */
        private const val TILT_X_DEG = 0f
        /** Bù góc yaw cho khớp "đầu" model (model hi-fi-spider quay đầu ngược 180°). */
        private const val YAW_OFFSET_DEG = 180f
        /** Hệ số kích thước bọ theo bề ngang khuôn mặt. */
        private const val SIZE_K = 1.2f
        /** Cho bọ tràn RA NGOÀI mép landmark một khoảng (giãn bán kính từ tâm mặt) để bù khoảng trắng
         *  của asset → bọ bò tới sát/vượt nhẹ ranh giới mặt. 0.12 ≈ +12% bán kính ở mép. */
        private const val BBOX_OVERFLOW = 0.12f
        /** Làm mượt VỊ TRÍ THÍCH NGHI (giây): đứng yên → STILL (mượt chống rung); di chuyển nhanh →
         *  FAST (bám sát, gần như snap) → bọ KHÔNG tụt lại khi rung/di chuyển cam. */
        private const val POS_TAU_STILL = 0.08f
        private const val POS_TAU_FAST = 0.012f
        /** Ngưỡng "di chuyển nhanh": mục tiêu nhảy > tỉ lệ này của bề ngang mặt mỗi frame → coi là nhanh. */
        private const val POS_SNAP_FRAC = 0.035f
        /** Làm mượt HƯỚNG: STILL khi đứng yên (mượt); FAST khi xoay/di chuyển nhanh → hướng SNAP cùng nhịp
         *  với vị trí (tránh "vị trí đúng nhưng hướng trễ rồi mới đúng" = giật). */
        private const val ORIENT_TAU = 0.13f
        private const val ORIENT_TAU_FAST = 0.03f
    }
}
