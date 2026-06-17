package com.piontech.bugfilter.core.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * SurfaceProcessor tự cài bằng OpenGL ES: nhận frame camera (texture OES) từ CameraX và composite
 * lớp bọ 3D (Filament) lên trên, vẽ ra TẤT CẢ surface đầu ra (PreviewView + VideoCapture). Vì cả
 * preview và video cùng đi qua đây nên video quay ra sẽ chứa đúng những gì hiển thị.
 *
 * Toàn bộ lệnh GL chạy trên một GL thread riêng (HandlerThread).
 *
 * Compositing (tái hiện app gốc `f2/i.java`):
 *   1. Vẽ camera (OES) vào [scene FBO][OutputTarget.sceneTex] (full-res).
 *   2. BLUR của scene: downscale ~0.12x + gaussian tách trục (2 pass) → da mềm cho "blend mép mềm".
 *   3. BÓNG theo hình bọ: trích ALPHA của texture bọ (silhouette) → FBO nhỏ → gaussian 2 pass →
 *      bóng đen mềm ĐÚNG HÌNH con bọ (giống `crop_shadow` + blur-on-alpha của app gốc), không phải đốm tròn.
 *   4. Composite 1 pass ra màn hình: bóng làm tối da, rồi mix(camera, blur, bọ) theo alpha.
 * [glListener] (Filament) render bọ ra 1 texture 2D có alpha (premultiplied), trả về cho bước (3)/(4).
 */
class GlSurfaceProcessor : SurfaceProcessor {

    interface GlListener {
        /** Gọi 1 lần trên GL thread khi EGL context sẵn sàng (đang current pbuffer). */
        fun onGlReady(eglCore: EglCore)

        /**
         * Render lớp bọ. Được gọi mỗi frame khi context của ta đang current. Hàm này CÓ THỂ chuyển
         * EGL context (Filament tự makeCurrent) — processor sẽ makeCurrent lại surface đầu ra sau khi
         * hàm trả về.
         * @param frameTimestampNs timestamp (ns) của frame camera đang render → để khớp face mesh đúng
         *        khoảnh khắc (như app gốc), tránh bọ lệch khi rung cam.
         * @return id texture 2D (RGBA, premultiplied alpha) để composite, hoặc -1 nếu không có gì vẽ.
         */
        fun onDrawOverlay(width: Int, height: Int, frameTimestampNs: Long): Int

        /** Dọn dẹp trên GL thread. */
        fun onGlRelease()
    }

    var glListener: GlListener? = null

    private val glThread = HandlerThread("GLProcessor").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { glHandler.post(it) }

    private var eglCore: EglCore? = null

    // Đầu vào (camera)
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var oesTexId: Int = -1
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    // Đầu ra (preview + video). Mỗi target có FBO scene/blur/shadow riêng theo đúng kích thước của nó.
    private class OutputTarget(
        val surfaceOutput: SurfaceOutput,
        val eglSurface: EGLSurface,
        val size: Size
    ) {
        var sceneTex = 0; var sceneFbo = 0
        var blurTexA = 0; var blurFboA = 0
        var blurTexB = 0; var blurFboB = 0
        var shadowTexA = 0; var shadowFboA = 0
        var shadowTexB = 0; var shadowFboB = 0
        var smallW = 0; var smallH = 0
    }
    private val outputs = mutableListOf<OutputTarget>()

    // Chương trình GL
    private var cameraProgram = 0       // OES camera -> scene FBO
    private var camPosLoc = 0
    private var camTexLoc = 0
    private var camMatrixLoc = 0

    private var blitProgram = 0         // 2D passthrough (fallback không có bọ)
    private var blitPosLoc = 0
    private var blitTexLoc = 0

    private var blurProgram = 0         // gaussian tách trục (dùng cho cả scene-blur và shadow-blur)
    private var blurPosLoc = 0
    private var blurTexLoc = 0
    private var blurDirLoc = 0

    private var shadowExtractProgram = 0 // lấy alpha bọ -> đen (silhouette)
    private var sePosLoc = 0
    private var seTexLoc = 0
    private var seBugLoc = 0

    private var compositeProgram = 0    // bóng + mix(camera, blur, bọ)
    private var coPosLoc = 0
    private var coTexLoc = 0
    private var coSceneLoc = 0
    private var coBlurLoc = 0
    private var coBugLoc = 0
    private var coShadowLoc = 0
    private var coShadowOffLoc = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer

    private var released = false

    // ---- SurfaceProcessor callbacks ----

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            if (released) {
                request.willNotProvideSurface()
                return@post
            }
            ensureEgl()
            val res = request.resolution
            val texId = GlUtil.createOesTexture()
            oesTexId = texId
            val st = SurfaceTexture(texId).apply {
                setDefaultBufferSize(res.width, res.height)
                setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
            }
            inputSurfaceTexture = st
            val surface = Surface(st)
            inputSurface = surface
            request.provideSurface(surface, glExecutor) {
                st.setOnFrameAvailableListener(null)
                st.release()
                surface.release()
                // Xóa OES texture CỦA RIÊNG request này (chạy trên GL thread). Trước đây onInputSurface ghi
                // đè oesTexId khi đổi camera mà không xóa cái cũ → leak 1 texture mỗi lần flip. texId capture
                // theo từng request nên luôn xóa đúng cái đã tạo cho nó.
                if (!released) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                if (inputSurfaceTexture === st) {
                    inputSurfaceTexture = null
                    inputSurface = null
                }
                if (oesTexId == texId) oesTexId = -1
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            if (released) {
                surfaceOutput.close()
                return@post
            }
            ensureEgl()
            val surface = surfaceOutput.getSurface(glExecutor) {
                glHandler.post {
                    val it = outputs.iterator()
                    while (it.hasNext()) {
                        val t = it.next()
                        if (t.surfaceOutput === surfaceOutput) {
                            destroyTargetFbos(t)
                            eglCore?.releaseSurface(t.eglSurface)
                            it.remove()
                        }
                    }
                    surfaceOutput.close()
                }
            }
            val eglSurface = eglCore!!.createWindowSurface(surface)
            outputs.add(OutputTarget(surfaceOutput, eglSurface, surfaceOutput.size))
        }
    }

    // ---- Render ----

    private fun onFrameAvailable() {
        val st = inputSurfaceTexture ?: return
        if (released) return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        st.getTransformMatrix(stMatrix)
        val timestampNs = st.timestamp
        val core = eglCore ?: return

        for (target in outputs) {
            core.makeCurrent(target.eglSurface)
            ensureTargetFbos(target)
            val w = target.size.width
            val h = target.size.height

            // 1. Camera (OES) -> scene FBO (full-res).
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.sceneFbo)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            target.surfaceOutput.updateTransformMatrix(texMatrix, stMatrix)
            drawCamera(texMatrix)

            // 2. Filament render bọ ra 1 texture 2D (premultiplied). Có thể đổi context.
            val overlayTex = glListener?.onDrawOverlay(w, h, timestampNs) ?: -1

            // Filament có thể đã đổi context/surface → khôi phục context + window surface của ta.
            core.makeCurrent(target.eglSurface)

            if (overlayTex >= 0) {
                // 3a. Bản blur của scene (cho blend mép mềm).
                buildBlur(target)
                // 3b. Bóng theo hình bọ (silhouette alpha đã blur).
                buildShadow(target, overlayTex)
                // 4. Composite ra màn hình (FBO 0 = window surface).
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glDisable(GLES20.GL_BLEND)
                composite(target.sceneTex, target.blurTexB, overlayTex, target.shadowTexA)
            } else {
                // Không có bọ → blit thẳng scene ra màn hình.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glDisable(GLES20.GL_BLEND)
                blit(target.sceneTex)
            }

            core.setPresentationTime(target.eglSurface, timestampNs)
            core.swapBuffers(target.eglSurface)
        }
        // Trả context về pbuffer để các thao tác GL ngoài vòng render không vẽ nhầm output.
        core.makeCurrentPbuffer()
    }

    private fun drawCamera(texMtx: FloatArray) {
        GLES20.glUseProgram(cameraProgram)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(camPosLoc)
        GLES20.glVertexAttribPointer(camPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(camTexLoc)
        GLES20.glVertexAttribPointer(camTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glUniformMatrix4fv(camMatrixLoc, 1, false, texMtx, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(camPosLoc)
        GLES20.glDisableVertexAttribArray(camTexLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /** Downscale scene -> blurA (H gaussian) -> blurB (V gaussian). Kết quả ở [OutputTarget.blurTexB]. */
    private fun buildBlur(t: OutputTarget) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.blurFboA)
        GLES20.glViewport(0, 0, t.smallW, t.smallH)
        GLES20.glDisable(GLES20.GL_BLEND)
        runBlur(t.sceneTex, 1f / t.smallW, 0f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.blurFboB)
        GLES20.glViewport(0, 0, t.smallW, t.smallH)
        runBlur(t.blurTexA, 0f, 1f / t.smallH)
    }

    /** Trích silhouette bọ (alpha→đen) xuống FBO nhỏ rồi gaussian 2 pass. Kết quả ở [OutputTarget.shadowTexA]. */
    private fun buildShadow(t: OutputTarget, bugTex: Int) {
        // Extract: bug.alpha -> (0,0,0,a), downscale.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.shadowFboA)
        GLES20.glViewport(0, 0, t.smallW, t.smallH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(shadowExtractProgram)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(sePosLoc)
        GLES20.glVertexAttribPointer(sePosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(seTexLoc)
        GLES20.glVertexAttribPointer(seTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bugTex)
        GLES20.glUniform1i(seBugLoc, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(sePosLoc)
        GLES20.glDisableVertexAttribArray(seTexLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        // Gaussian H (A->B) rồi V (B->A). rgb=0 nên chỉ alpha bị blur → bóng đen mềm.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.shadowFboB)
        GLES20.glViewport(0, 0, t.smallW, t.smallH)
        runBlur(t.shadowTexA, 1f / t.smallW, 0f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.shadowFboA)
        GLES20.glViewport(0, 0, t.smallW, t.smallH)
        runBlur(t.shadowTexB, 0f, 1f / t.smallH)
    }

    private fun runBlur(texId: Int, dirX: Float, dirY: Float) {
        GLES20.glUseProgram(blurProgram)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(blurPosLoc)
        GLES20.glVertexAttribPointer(blurPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(blurTexLoc)
        GLES20.glVertexAttribPointer(blurTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glUniform2f(blurDirLoc, dirX, dirY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(blurPosLoc)
        GLES20.glDisableVertexAttribArray(blurTexLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Blit 1 texture 2D ra framebuffer hiện hành (passthrough). */
    private fun blit(texId: Int) {
        GLES20.glUseProgram(blitProgram)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(blitPosLoc)
        GLES20.glVertexAttribPointer(blitPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(blitTexLoc)
        GLES20.glVertexAttribPointer(blitTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(blitPosLoc)
        GLES20.glDisableVertexAttribArray(blitTexLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Composite: bóng (silhouette) làm tối da + mix(scene, blur, bọ) theo alpha. */
    private fun composite(sceneTex: Int, blurTex: Int, bugTex: Int, shadowTex: Int) {
        GLES20.glUseProgram(compositeProgram)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(coPosLoc)
        GLES20.glVertexAttribPointer(coPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(coTexLoc)
        GLES20.glVertexAttribPointer(coTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glUniform2f(coShadowOffLoc, SHADOW_OFFSET_X, SHADOW_OFFSET_Y)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex)
        GLES20.glUniform1i(coSceneLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
        GLES20.glUniform1i(coBlurLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bugTex)
        GLES20.glUniform1i(coBugLoc, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glUniform1i(coShadowLoc, 3)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(coPosLoc)
        GLES20.glDisableVertexAttribArray(coTexLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ---- FBO ----

    /** Cấp phát scene/blur/shadow FBO cho 1 target (1 lần, theo đúng kích thước của target). */
    private fun ensureTargetFbos(t: OutputTarget) {
        if (t.sceneTex != 0) return
        val w = t.size.width
        val h = t.size.height
        t.smallW = maxOf(1, (w * BLUR_DOWNSCALE).toInt())
        t.smallH = maxOf(1, (h * BLUR_DOWNSCALE).toInt())

        t.sceneTex = createColorTex(w, h)
        t.sceneFbo = createFbo(t.sceneTex)
        t.blurTexA = createColorTex(t.smallW, t.smallH)
        t.blurFboA = createFbo(t.blurTexA)
        t.blurTexB = createColorTex(t.smallW, t.smallH)
        t.blurFboB = createFbo(t.blurTexB)
        t.shadowTexA = createColorTex(t.smallW, t.smallH)
        t.shadowFboA = createFbo(t.shadowTexA)
        t.shadowTexB = createColorTex(t.smallW, t.smallH)
        t.shadowFboB = createFbo(t.shadowTexB)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun destroyTargetFbos(t: OutputTarget) {
        val texes = intArrayOf(t.sceneTex, t.blurTexA, t.blurTexB, t.shadowTexA, t.shadowTexB)
            .filter { it != 0 }.toIntArray()
        val fbos = intArrayOf(t.sceneFbo, t.blurFboA, t.blurFboB, t.shadowFboA, t.shadowFboB)
            .filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        if (texes.isNotEmpty()) GLES20.glDeleteTextures(texes.size, texes, 0)
        t.sceneTex = 0; t.sceneFbo = 0
        t.blurTexA = 0; t.blurFboA = 0
        t.blurTexB = 0; t.blurFboB = 0
        t.shadowTexA = 0; t.shadowFboA = 0
        t.shadowTexB = 0; t.shadowFboB = 0
    }

    private fun createColorTex(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun createFbo(texId: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0
        )
        return ids[0]
    }

    private fun ensureEgl() {
        if (eglCore != null) return
        val core = EglCore()
        eglCore = core

        cameraProgram = GlUtil.createProgram(CAMERA_VS, CAMERA_FS)
        camPosLoc = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        camTexLoc = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        camMatrixLoc = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")

        blitProgram = GlUtil.createProgram(QUAD_VS, BLIT_FS)
        blitPosLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
        blitTexLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")

        blurProgram = GlUtil.createProgram(QUAD_VS, BLUR_FS)
        blurPosLoc = GLES20.glGetAttribLocation(blurProgram, "aPosition")
        blurTexLoc = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
        blurDirLoc = GLES20.glGetUniformLocation(blurProgram, "uDir")

        shadowExtractProgram = GlUtil.createProgram(QUAD_VS, SHADOW_EXTRACT_FS)
        sePosLoc = GLES20.glGetAttribLocation(shadowExtractProgram, "aPosition")
        seTexLoc = GLES20.glGetAttribLocation(shadowExtractProgram, "aTexCoord")
        seBugLoc = GLES20.glGetUniformLocation(shadowExtractProgram, "sBug")

        compositeProgram = GlUtil.createProgram(QUAD_VS, COMPOSITE_FS)
        coPosLoc = GLES20.glGetAttribLocation(compositeProgram, "aPosition")
        coTexLoc = GLES20.glGetAttribLocation(compositeProgram, "aTexCoord")
        coSceneLoc = GLES20.glGetUniformLocation(compositeProgram, "sScene")
        coBlurLoc = GLES20.glGetUniformLocation(compositeProgram, "sBlur")
        coBugLoc = GLES20.glGetUniformLocation(compositeProgram, "sBug")
        coShadowLoc = GLES20.glGetUniformLocation(compositeProgram, "sShadow")
        coShadowOffLoc = GLES20.glGetUniformLocation(compositeProgram, "uShadowOffset")

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        texBuffer = ByteBuffer.allocateDirect(texs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texs); position(0) }
        Matrix.setIdentityM(texMatrix, 0)

        glListener?.onGlReady(core)
    }

    fun release() {
        glHandler.post {
            released = true
            glListener?.onGlRelease()
            outputs.forEach {
                destroyTargetFbos(it)
                eglCore?.releaseSurface(it.eglSurface)
                it.surfaceOutput.close()
            }
            outputs.clear()
            inputSurfaceTexture?.setOnFrameAvailableListener(null)
            inputSurfaceTexture?.release()
            inputSurface?.release()
            inputSurfaceTexture = null
            inputSurface = null
            if (oesTexId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
                oesTexId = -1
            }
            if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram)
            if (blitProgram != 0) GLES20.glDeleteProgram(blitProgram)
            if (blurProgram != 0) GLES20.glDeleteProgram(blurProgram)
            if (shadowExtractProgram != 0) GLES20.glDeleteProgram(shadowExtractProgram)
            if (compositeProgram != 0) GLES20.glDeleteProgram(compositeProgram)
            cameraProgram = 0
            blitProgram = 0
            blurProgram = 0
            shadowExtractProgram = 0
            compositeProgram = 0
            eglCore?.release()
            eglCore = null
            glThread.quitSafely()
        }
    }

    companion object {
        /** Tỉ lệ thu nhỏ khi tạo blur/bóng (≈ app gốc dùng 0.1–0.2x). Nhỏ hơn → mềm + rẻ hơn. */
        private const val BLUR_DOWNSCALE = 0.12f

        /** Độ lệch bóng theo hướng sáng (uv của không gian bọ). Bóng đổ nhẹ xuống dưới + sang phải. */
        private const val SHADOW_OFFSET_X = -0.006f
        private const val SHADOW_OFFSET_Y = 0.010f

        private const val CAMERA_VS = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val CAMERA_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        // VS dùng chung cho blit/blur/extract/composite: passthrough vị trí + texcoord [0,1].
        private const val QUAD_VS = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        private const val BLIT_FS = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        // Gaussian tách trục 5 tap (trọng số linear-sampling chuẩn). uDir = bước 1 texel theo trục.
        // Dùng cho cả scene-blur (RGB) và shadow-blur (rgb=0 → chỉ blur alpha).
        private const val BLUR_FS = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform vec2 uDir;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord) * 0.2270270270;
                c += texture2D(sTexture, vTexCoord + uDir * 1.3846153846) * 0.3162162162;
                c += texture2D(sTexture, vTexCoord - uDir * 1.3846153846) * 0.3162162162;
                c += texture2D(sTexture, vTexCoord + uDir * 3.2307692308) * 0.0702702703;
                c += texture2D(sTexture, vTexCoord - uDir * 3.2307692308) * 0.0702702703;
                gl_FragColor = c;
            }
        """

        // Trích silhouette bọ: lấy alpha (Filament tex gốc bottom-left → lật V), xuất đen với alpha đó.
        private const val SHADOW_EXTRACT_FS = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sBug;
            void main() {
                float a = texture2D(sBug, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).a;
                gl_FragColor = vec4(0.0, 0.0, 0.0, a);
            }
        """

        // Composite: bóng silhouette làm tối da, rồi mix(camera, blur, bọ) theo alpha (tái hiện f2/i + f2/d).
        // Lớp bọ là texture Filament gốc bottom-left → lật V. Output opaque.
        private const val COMPOSITE_FS = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sScene;
            uniform sampler2D sBlur;
            uniform sampler2D sBug;
            uniform sampler2D sShadow;
            uniform vec2 uShadowOffset;
            void main() {
                vec2 bugUv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                vec4 baseColor = texture2D(sScene, vTexCoord);
                vec4 blurColor = texture2D(sBlur, vTexCoord);
                vec4 faceColor = texture2D(sBug, bugUv);

                // Bóng = silhouette bọ đã blur (đen), lệch theo hướng sáng → làm tối da.
                // sShadow đã được lật-Y sẵn ở bước extract (cùng hệ với sScene) → sample ở vTexCoord, KHÔNG bugUv.
                float shadowA = texture2D(sShadow, vTexCoord - uShadowOffset).a;
                float darken = clamp(shadowA, 0.0, 1.0) * 0.5;
                vec3 base = baseColor.rgb * (1.0 - darken);
                vec3 blur = blurColor.rgb * (1.0 - darken);

                vec3 bugRgb = faceColor.a > 0.001 ? faceColor.rgb / faceColor.a : faceColor.rgb;
                vec3 mixBlurColor = mix(blur, bugRgb, faceColor.a * faceColor.a * 0.65);
                vec3 finalColor = mix(base, mixBlurColor, faceColor.a);
                gl_FragColor = vec4(finalColor, 1.0);
            }
        """
    }
}
