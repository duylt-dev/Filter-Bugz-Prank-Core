package com.piontech.bugfilter.core.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Bao gói tối thiểu của EGL: tạo display/context/config ES3, tạo window surface từ Surface đầu ra
 * của CameraX (preview & video), và hỗ trợ makeCurrent/swapBuffers.
 *
 * Context được tạo với cờ RECORDABLE_ANDROID để có thể vẽ thẳng vào surface của VideoCapture.
 * [eglContext] được expose để Stage 2 tạo Filament Engine dùng chung context (shared GL).
 */
class EglCore {

    private val display: EGLDisplay
    private val config: EGLConfig
    val eglContext: EGLContext

    /** PBuffer 1x1 để có context current ngay sau khi khởi tạo (compile shader, tạo texture…). */
    private val pbufferSurface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
            || numConfigs[0] <= 0
        ) {
            throw RuntimeException("eglChooseConfig failed")
        }
        config = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext === EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        // PBuffer 1x1 + makeCurrent ngay để có context hợp lệ cho các lệnh GL init.
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        if (pbufferSurface === EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
        if (!EGL14.eglMakeCurrent(display, pbufferSurface, pbufferSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(pbuffer) failed")
        }
    }

    /** Quay lại context offscreen (pbuffer) khi không vẽ vào output nào. */
    fun makeCurrentPbuffer() {
        EGL14.eglMakeCurrent(display, pbufferSurface, pbufferSurface, eglContext)
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(display, eglSurface)
    }

    fun release() {
        if (display !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, pbufferSurface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }
}
