package com.piontech.bugfilter.core.render

import com.piontech.bugfilter.core.face.FaceResult

/**
 * Mô hình bề mặt 3D của khuôn mặt dựng từ landmark ML Kit: vị trí + pháp tuyến từng đỉnh (hệ texture
 * space + depth) + tâm mặt + bounding box. Con bọ bò trên bề mặt này (path đi theo đỉnh, ốp theo pháp tuyến).
 *
 * Pháp tuyến dùng mô hình TRỤ theo trục thật của mặt (má↔má cong đầy đủ, trán↔cằm nén) thay vì z thô của
 * ML Kit (quá nhiễu), cộng bù theo HƯỚNG ĐẦU (face-forward) khi user xoay mặt.
 *
 * [update] gọi 1 lần mỗi frame; sau đó đọc [vertPos]/[vertNrm]/[centerX]/[centerY]/[bbox].
 */
class FaceSurfaceModel(private val mapper: FaceCoordinateMapper) {

    /** Vị trí từng đỉnh (texture space + depth), 3 float/đỉnh. */
    val vertPos = FloatArray(468 * 3)
    /** Pháp tuyến từng đỉnh, 3 float/đỉnh. */
    val vertNrm = FloatArray(468 * 3)
    var vertCount = 0
        private set
    /** Tâm mặt (texture space) — để giãn bọ ra mép. */
    var centerX = 0f
        private set
    var centerY = 0f
        private set
    /** minX,minY,maxX,maxY của toàn bộ landmark (texture space) — để kẹp vị trí bọ. */
    val bbox = FloatArray(4)

    private val posA = FloatArray(2)  // temp dùng trong bboxTex
    private val tan3 = FloatArray(3)  // temp dùng trong computeMesh

    // Hướng đầu (face-forward) đã làm mượt — bù nghiêng bọ khi user xoay mặt. Frontal ≈ (0,0,1).
    private var smFwdX = 0f; private var smFwdY = 0f; private var smFwdZ = 1f
    private var smFwdInit = false

    /** Dựng mesh (vị trí + pháp tuyến) + bbox cho frame hiện tại. */
    fun update(face: FaceResult, w: Int, h: Int, dt: Float) {
        computeMesh(face, w, h, dt)
        bboxTex(face, w, h)
    }

    /** Dựng vị trí + pháp tuyến cho từng đỉnh mesh (từ allPoints + allTriangles). */
    private fun computeMesh(face: FaceResult, w: Int, h: Int, dt: Float) {
        val pts = face.faceMesh.allPoints
        val n = minOf(pts.size, 468)
        vertCount = n
        for (i in 0 until n) {
            mapper.world3(face, i, w, h, tan3)
            vertPos[i * 3] = tan3[0]; vertPos[i * 3 + 1] = tan3[1]; vertPos[i * 3 + 2] = tan3[2]
        }
        updateHeadForward(n, dt)
        // Pháp tuyến từ z của ML Kit quá nhiễu → dùng mô hình TRỤ (cong theo trục má) ổn định:
        // z (hướng camera) lớn ở giữa mặt = top-down nhìn xuống lưng bọ; ra MÉP MÁ trái-phải z→nhỏ =
        // pháp tuyến nghiêng ngang → bọ cho hình chiếu cạnh (3D). Trán/cằm vẫn top-down (xem dưới).
        var cx = 0f; var cy = 0f
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = vertPos[i*3]; val y = vertPos[i*3+1]
            cx += x; cy += y
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        cx /= n; cy /= n
        centerX = cx; centerY = cy
        // Mô hình TRỤ theo TRỤC THẬT CỦA MẶT (KHÔNG theo trục texture) → đúng ở MỌI tư thế cầm máy:
        //   trục MÁ↔MÁ (cheekAxis, từ landmark 234↔454) → cong ĐẦY ĐỦ → má/mép nghiêng (hình chiếu cạnh);
        //   trục TRÁN↔CẰM (upAxis, từ 10↔152) → NÉN (×VERT_LEAN) → trán/cằm hướng thẳng camera (top-down).
        // (Trước đây hardcode má=texture-Y/trán-cằm=texture-X → cầm ngang mặt xoay trong texture → ngược.)
        var crX = 1f; var crY = 0f; var halfCheek = halfW(maxX, minX)
        var upX = 0f; var upY = 1f; var halfVert = halfW(maxY, minY)
        if (n > 454) {
            crX = vertPos[454*3] - vertPos[234*3]; crY = vertPos[454*3+1] - vertPos[234*3+1]
            var cl = Math.sqrt((crX*crX + crY*crY).toDouble()).toFloat()
            if (cl < 1e-3f) { crX = 1f; crY = 0f; cl = 1f }
            halfCheek = (cl * 0.5f).coerceAtLeast(1f); crX /= cl; crY /= cl
            upX = vertPos[10*3] - vertPos[152*3]; upY = vertPos[10*3+1] - vertPos[152*3+1]
            var ul = Math.sqrt((upX*upX + upY*upY).toDouble()).toFloat()
            if (ul < 1e-3f) { upX = 0f; upY = 1f; ul = 1f }
            halfVert = (ul * 0.5f).coerceAtLeast(1f); upX /= ul; upY /= ul
        }
        // Lệch hướng đầu (head pose): khi user xoay mặt, nghiêng TOÀN BỘ pháp tuyến theo hướng mặt đang quay.
        val hx = HEAD_POSE_K * smFwdX
        val hy = HEAD_POSE_K * smFwdY
        for (i in 0 until n) {
            val dx = vertPos[i*3] - cx; val dy = vertPos[i*3+1] - cy
            val rLat = (dx*crX + dy*crY) / halfCheek          // thành phần theo trục má (0 tâm, ±1 mép má)
            val rVert = ((dx*upX + dy*upY) / halfVert) * VERT_LEAN  // theo trục trán-cằm (đã nén)
            // pháp tuyến (texture) = nghiêng theo trục má (đầy đủ) + trục trán-cằm (nén); z = độ cong vòm.
            var nx = rLat*crX + rVert*upX
            var ny = rLat*crY + rVert*upY
            val rEff2 = (rLat*rLat + rVert*rVert).coerceIn(0f, 1f)
            var nz = (FACE_DEPTH_K * Math.sqrt((1f - rEff2).toDouble()).toFloat()).coerceAtLeast(NZ_MIN)
            nx += hx; ny += hy   // bám hướng đầu
            val l = Math.sqrt((nx*nx+ny*ny+nz*nz).toDouble()).toFloat()
            if (l > 1e-5f) { nx/=l; ny/=l; nz/=l } else { nx=0f; ny=0f; nz=1f }
            vertNrm[i*3]=nx; vertNrm[i*3+1]=ny; vertNrm[i*3+2]=nz
        }
    }

    private fun halfW(hi: Float, lo: Float) = ((hi - lo) * 0.5f).coerceAtLeast(1f)

    /**
     * Ước lượng HƯỚNG ĐẦU (face-forward) từ 4 landmark cách xa nhau (dùng z thật của ML Kit) rồi làm
     * mượt theo thời gian. Vì lấy từ các điểm cách xa + trung bình + smooth → ổn định hơn nhiều so với
     * z per-vertex (vốn nhiễu). Frontal ≈ (0,0,1); xoay mặt → (x,y) lệch theo hướng quay.
     */
    private fun updateHeadForward(n: Int, dt: Float) {
        if (n <= 454) return
        val iL = 234; val iR = 454; val iT = 10; val iB = 152   // má trái/phải, trán, cằm
        // right = R - L ; up = T - B (đều 3D, có z thật)
        val rX = vertPos[iR*3]-vertPos[iL*3]; val rY = vertPos[iR*3+1]-vertPos[iL*3+1]; val rZ = vertPos[iR*3+2]-vertPos[iL*3+2]
        val uX = vertPos[iT*3]-vertPos[iB*3]; val uY = vertPos[iT*3+1]-vertPos[iB*3+1]; val uZ = vertPos[iT*3+2]-vertPos[iB*3+2]
        // forward = right × up
        var fX = rY*uZ - rZ*uY; var fY = rZ*uX - rX*uZ; var fZ = rX*uY - rY*uX
        val fl = Math.sqrt((fX*fX+fY*fY+fZ*fZ).toDouble()).toFloat()
        if (fl < 1e-3f) return
        fX/=fl; fY/=fl; fZ/=fl
        if (fZ < 0f) { fX=-fX; fY=-fY; fZ=-fZ }   // luôn hướng về camera (+z)
        val a = 1f - Math.exp((-dt / HEAD_POSE_TAU).toDouble()).toFloat()
        if (!smFwdInit) { smFwdX=fX; smFwdY=fY; smFwdZ=fZ; smFwdInit=true }
        else { smFwdX += (fX-smFwdX)*a; smFwdY += (fY-smFwdY)*a; smFwdZ += (fZ-smFwdZ)*a }
    }

    /** Bounding box của toàn bộ landmark trong hệ texture (để kẹp vị trí bọ). */
    private fun bboxTex(face: FaceResult, w: Int, h: Int) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (pt in face.faceMesh.allPoints) {
            mapper.imageToTex(face, pt.position.x, pt.position.y, w, h, posA)
            if (posA[0] < minX) minX = posA[0]
            if (posA[0] > maxX) maxX = posA[0]
            if (posA[1] < minY) minY = posA[1]
            if (posA[1] > maxY) maxY = posA[1]
        }
        bbox[0] = minX; bbox[1] = minY; bbox[2] = maxX; bbox[3] = maxY
    }

    companion object {
        /** Độ "phẳng" của vòm mặt: lớn → vùng trong giữ top-down lâu hơn, chỉ nghiêng sát mép;
         *  nhỏ → nghiêng dần từ giữa. (z của pháp tuyến vòm = hằng này × sqrt(1 − r²); ở rìa r→1 luôn = 0
         *  nên mép luôn cho hình chiếu ngang bất kể hằng này.) */
        private const val FACE_DEPTH_K = 0.85f
        /** Sàn của thành phần z pháp tuyến vòm: chặn bọ nghiêng tới 90° (edge-on → mỏng dính, biến mất).
         *  ~0.18 ⇒ nghiêng tối đa ≈ 80°, vẫn thấy hình chiếu cạnh rõ mà bọ không mất. */
        private const val NZ_MIN = 0.18f
        /** Độ cong theo CHIỀU DỌC (trán↔cằm). 0 = trụ thuần (trán/cằm top-down hẳn); lớn → cong như vòm.
         *  Để nhỏ vì mặt người nhìn trước gần phẳng theo dọc (trán/cằm hướng thẳng camera). */
        private const val VERT_LEAN = 0.15f
        /** Mức bám HƯỚNG ĐẦU (khi user xoay mặt trái/phải/lên/xuống): nghiêng bọ theo hướng mặt đang quay.
         *  0 = bỏ qua (luôn theo mặt phẳng cam); 1 = bám mạnh. Mô phỏng app gốc neo bọ vào mesh 3D. */
        private const val HEAD_POSE_K = 0.9f
        /** Làm mượt vector hướng đầu (giây) — lớn để khử nhiễu z của ML Kit, vẫn theo kịp khi xoay mặt. */
        private const val HEAD_POSE_TAU = 0.12f
    }
}
