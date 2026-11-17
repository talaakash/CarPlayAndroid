package com.example.iptv_native

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLUtils
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
 * Owns the car's host Surface for its whole lifetime and draws onto it with OpenGL only: either
 * the shared player's video (real Fit / Fill scaling) or the channel artwork. A Surface accepts
 * one kind of producer (CPU canvas OR GL), so mixing a canvas for artwork with GL for video made
 * the video refuse to attach after the output was switched; everything now goes through GL, and
 * switching between artwork and video never touches the Surface itself.
 *
 * A raw car Surface stretches whatever the decoder produces, so the player is pointed at the
 * stable [inputSurface] of this renderer instead, and every frame is drawn into the car
 * Surface with the right aspect ratio (Fit = whole picture, bars where needed; Fill = covers
 * the area, crops only the overflow). The player is never reconfigured or restarted: switching
 * output is just a surface swap, and Fit/Fill is a uniform change that redraws the last frame.
 */
class CarVideoRenderer(private val target: Surface) {
    companion object {
        /** A side inset must cover at least this share of the surface width to count as a pane. */
        const val MIN_PANE_FRACTION = 0.2f

        /**
         * Scale of the full-area quad that shows a picture of aspect [pictureAspect] inside an
         * area of aspect [areaAspect]: Fit = whole picture inside (bars), Fill = covers (crops).
         */
        fun quadScale(pictureAspect: Float, areaAspect: Float, fill: Boolean): Pair<Float, Float> {
            val wide = pictureAspect > areaAspect
            return if (!fill) {
                if (wide) 1f to areaAspect / pictureAspect else pictureAspect / areaAspect to 1f
            } else {
                if (wide) pictureAspect / areaAspect to 1f else 1f to areaAspect / pictureAspect
            }
        }
    }

    /** Part of the Surface that is actually visible (not under a secondary pane); null = all of it. */
    @Volatile private var area: android.graphics.Rect? = null

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
    private var showingVideo = false
    private var imgProgram = 0
    private var imgMvp = 0
    private var imgTex = 0
    private var hasImage = false
    private var overlayTex = 0
    private var hasOverlay = false

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
    private val uvFlipped = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0)
    }
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
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
        imgProgram = link(
            "attribute vec4 aPos; attribute vec2 aUv; uniform mat4 uMvp; varying vec2 vUv;" +
                "void main(){ gl_Position = uMvp * aPos; vUv = aUv; }",
            "precision mediump float; varying vec2 vUv; uniform sampler2D uSampler;" +
                "void main(){ gl_FragColor = texture2D(uSampler, vUv); }",
        )
        imgMvp = GLES20.glGetUniformLocation(imgProgram, "uMvp")
        val it2 = IntArray(1)
        GLES20.glGenTextures(1, it2, 0)
        imgTex = it2[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val ot = IntArray(1)
        GLES20.glGenTextures(1, ot, 0)
        overlayTex = ot[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
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
        val dim = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, dim, 0); width = dim[0]
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, dim, 0); height = dim[0]
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

    /** Pixel size of the host Surface (valid after construction). */
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set

    /** Show the player's video (the caller points the player at [inputSurface]). */
    fun showVideo() {
        handler.post { showingVideo = true; draw(newFrame = false) }
    }

    /** Show a full-surface picture (artwork) instead of the video. Takes ownership of [bmp]. */
    fun showArtwork(bmp: Bitmap) {
        handler.post {
            if (released) { bmp.recycle(); return@post }
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                hasImage = true
            } catch (_: Throwable) {} finally { bmp.recycle() }
            showingVideo = false
            draw(newFrame = false)
        }
    }

    /**
     * Status banner (buffering, error...) drawn ON TOP of the picture, inside the same Surface.
     * Because it is part of the frame and not a host UI panel, it never changes the visible area
     * and so never resizes the video. Pass null to remove it. Takes ownership of [bmp].
     */
    fun setOverlay(bmp: Bitmap?) {
        handler.post {
            if (released) { bmp?.recycle(); return@post }
            if (bmp == null) hasOverlay = false else {
                try {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    hasOverlay = true
                } catch (_: Throwable) {} finally { bmp.recycle() }
            }
            draw(newFrame = false)
        }
    }

    /** Video size with pixel aspect applied; 0 = unknown (then the picture is simply filled). */
    fun setVideoSize(width: Float, height: Float) {
        videoWidth = width; videoHeight = height
        handler.post { draw(newFrame = false) }
    }

    /** The host reported which part of the Surface is visible; everything is laid out inside it. */
    fun setVisibleArea(rect: android.graphics.Rect?) {
        area = rect?.takeIf { it.width() > 0 && it.height() > 0 }?.let { android.graphics.Rect(it) }
        handler.post { draw(newFrame = false) }
    }

    /** Current drawing area in surface pixels (visible area clamped to the Surface, else all of it). */
    fun areaSize(): Pair<Int, Int> {
        val r = clampedArea(width, height)
        return (r?.width() ?: width) to (r?.height() ?: height)
    }

    private fun clampedArea(w: Int, h: Int): android.graphics.Rect? {
        val a = area ?: return null
        // Only a secondary pane may resize the picture. It takes a big slice of the width, while the
        // host's own controls (the map-action-strip rail with Favorite / Fit / Fill, the action
        // strip, the message panel) are small and sit on top of the picture, so side insets below
        // MIN_PANE_FRACTION of the width are ignored. Height is never reduced.
        val minInset = (w * MIN_PANE_FRACTION).toInt()
        val left = if (a.left >= minInset) a.left else 0
        val right = if (w - a.right >= minInset) a.right else w
        val r = android.graphics.Rect(left, 0, right, h)
        if (!r.intersect(0, 0, w, h) || r.width() <= 0) return null
        return r
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
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glViewport(0, 0, w[0], h[0])
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // Lay out inside the visible area only (GL origin is bottom-left); the scissor makes
            // Fill crop at the pane edge instead of drawing under the secondary pane.
            val vr = clampedArea(w[0], h[0])
            val vx = vr?.left ?: 0
            val vy = if (vr != null) h[0] - vr.bottom else 0
            val vw = vr?.width() ?: w[0]
            val vh = vr?.height() ?: h[0]
            GLES20.glViewport(vx, vy, vw, vh)
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(vx, vy, vw, vh)
            if (!showingVideo) {
                if (hasImage) drawImage(imgTex)
            } else if (hasFrame) {
                val sa = vw.toFloat() / vh
                val va = if (videoWidth > 0 && videoHeight > 0) videoWidth / videoHeight else sa
                // Fit: shrink the quad so the whole picture is inside. Fill: grow it to cover.
                val (sx, sy) = quadScale(va, sa, fill)
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
            if (hasOverlay) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // bitmap is premultiplied
                drawImage(overlayTex)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (_: Throwable) { /* surface gone: the session will release us */ }
    }

    private fun drawImage(tex: Int) {
        android.opengl.Matrix.setIdentityM(mvp, 0)
        GLES20.glUseProgram(imgProgram)
        GLES20.glUniformMatrix4fv(imgMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val aPos = GLES20.glGetAttribLocation(imgProgram, "aPos")
        val aUv = GLES20.glGetAttribLocation(imgProgram, "aUv")
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvFlipped)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
