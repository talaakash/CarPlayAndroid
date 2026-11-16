package com.example.iptv_native

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws the shared player's video onto the car's host Surface with real Fit / Fill scaling.
 *
 * A raw car Surface stretches whatever the decoder produces, so the player is pointed at the
 * stable [inputSurface] of this renderer instead, and every frame is drawn into the car
 * Surface with the right aspect ratio (Fit = whole picture, bars where needed; Fill = covers
 * the area, crops only the overflow). The player is never reconfigured or restarted: switching
 * output is just a surface swap, and Fit/Fill is a uniform change that redraws the last frame.
 */
class CarVideoRenderer(private val target: Surface) {
    private val thread = HandlerThread("car-video-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var program = 0
    private var texId = 0
    private var uMvp = 0
    private var uTex = 0
    private var hasFrame = false
    private var released = false

    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    @Volatile var videoWidth = 0f; private set
    @Volatile var videoHeight = 0f; private set
    @Volatile var fill = false; private set

    /** Stable Surface the player renders into. Null if GL could not be initialised. */
    @Volatile var inputSurface: Surface? = null; private set

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val uv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }

    init {
        val ready = CountDownLatch(1)
        handler.post {
            try { setup() } catch (_: Throwable) { teardown() } finally { ready.countDown() }
        }
        ready.await(2, TimeUnit.SECONDS)
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1))
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0)
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))

        program = link(
            "attribute vec4 aPos; attribute vec2 aUv; uniform mat4 uMvp; uniform mat4 uTex; varying vec2 vUv;" +
                "void main(){ gl_Position = uMvp * aPos; vUv = (uTex * vec4(aUv, 0.0, 1.0)).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv;" +
                "uniform samplerExternalOES uSampler; void main(){ gl_FragColor = texture2D(uSampler, vUv); }",
        )
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({ draw(newFrame = true) }, handler)
        texture = st
        inputSurface = Surface(st)
        draw(newFrame = false) // black until the first frame
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    /** Video size with pixel aspect applied; 0 = unknown (then the picture is simply filled). */
    fun setVideoSize(width: Float, height: Float) {
        videoWidth = width; videoHeight = height
        handler.post { draw(newFrame = false) }
    }

    fun setFill(value: Boolean) {
        fill = value
        handler.post { draw(newFrame = false) }
    }

    private fun draw(newFrame: Boolean) {
        if (released || texture == null || eglSurface == EGL14.EGL_NO_SURFACE) return
        try {
            if (newFrame) {
                texture!!.updateTexImage()
                texture!!.getTransformMatrix(texMatrix)
                hasFrame = true
            }
            val w = IntArray(1); val h = IntArray(1)
            EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, w, 0)
            EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, h, 0)
            if (w[0] <= 0 || h[0] <= 0) return
            GLES20.glViewport(0, 0, w[0], h[0])
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (hasFrame) {
                val sa = w[0].toFloat() / h[0]
                val va = if (videoWidth > 0 && videoHeight > 0) videoWidth / videoHeight else sa
                // Fit: shrink the quad so the whole picture is inside. Fill: grow it to cover.
                val sx: Float; val sy: Float
                if (!fill) { sx = if (va > sa) 1f else va / sa; sy = if (va > sa) sa / va else 1f }
                else { sx = if (va > sa) va / sa else 1f; sy = if (va > sa) 1f else sa / va }
                android.opengl.Matrix.setIdentityM(mvp, 0)
                android.opengl.Matrix.scaleM(mvp, 0, sx, sy, 1f)
                GLES20.glUseProgram(program)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                val aPos = GLES20.glGetAttribLocation(program, "aPos")
                val aUv = GLES20.glGetAttribLocation(program, "aUv")
                GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (_: Throwable) { /* surface gone: the session will release us */ }
    }

    /** Call after the player stopped using [inputSurface]. Frees GL resources asynchronously. */
    fun release() {
        // Small grace period so ExoPlayer has finished detaching from our Surface.
        handler.postDelayed({ teardown(); thread.quitSafely() }, 300)
    }

    private fun teardown() {
        released = true
        try { inputSurface?.release() } catch (_: Throwable) {}
        try { texture?.release() } catch (_: Throwable) {}
        inputSurface = null; texture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; eglSurface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT
    }
}
