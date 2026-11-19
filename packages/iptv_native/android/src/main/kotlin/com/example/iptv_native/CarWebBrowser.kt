package com.example.iptv_native

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * The car's browser: a REAL WebView, rendered off-screen into a [android.hardware.display.VirtualDisplay]
 * whose Surface is the renderer's web SurfaceTexture ([CarVideoRenderer.webSurface]). The renderer then
 * draws it onto the host's car Surface like it draws video, so one GL owner still owns that Surface.
 *
 * Android Auto has no WebView template and the car host never shows app views, so this is the same
 * "navigation surface" route the video player already uses: parked only, sideloaded builds only.
 * Touch comes from SurfaceCallback (tap / scroll / fling) and is forwarded to the WebView.
 *
 * The page survives the surface going away (DHU reconnect, app switch): [detach] saves the WebView state
 * and [attach] restores it, so history and the current page are kept.
 */
internal class CarWebBrowser(private val ctx: Context, private val onChange: () -> Unit) {
    var url = ""; private set
    var title = ""; private set
    var progress = 0; private set
    var loading = false; private set
    var canBack = false; private set
    var canForward = false; private set
    var error: String? = null; private set

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null
    private var saved: Bundle? = null
    private var pendingUrl: String? = null
    private var topInset = 0
    val attached get() = web != null

    val hasPage get() = url.isNotEmpty() || pendingUrl != null

    /** Creates the VirtualDisplay + WebView. [w]x[h] = size of the car area; the top [topInsetPx] are the toolbar. */
    fun attach(renderer: CarVideoRenderer, w: Int, h: Int, topInsetPx: Int): Boolean {
        if (web != null) detach()
        val surface = renderer.webSurface ?: return false
        if (w <= 0 || h <= 0) return false
        topInset = topInsetPx
        return try {
            renderer.setWebBufferSize(w, h)
            val dpi = (160f * w / 1100f).toInt().coerceIn(160, 320)
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay(
                "car-web", w, h, dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: return false
            display = vd
            val p = Presentation(ctx, vd.display)
            val root = FrameLayout(p.context)
            val wv = newWebView(p.context)
            root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = topInsetPx })
            p.setContentView(root)
            p.show()
            presentation = p
            web = wv
            val restore = saved
            val first = pendingUrl
            pendingUrl = null
            when {
                first != null -> wv.loadUrl(first)
                restore != null -> wv.restoreState(restore)
                url.isNotEmpty() -> wv.loadUrl(url)
            }
            true
        } catch (t: Throwable) {
            error = "The car browser could not start (${t.javaClass.simpleName})"
            detach()
            onChange()
            false
        }
    }

    /** Surface gone: keep the page (state bundle), release the display and views. */
    fun detach() {
        web?.let { wv ->
            try { Bundle().also { wv.saveState(it); saved = it } } catch (_: Throwable) {}
            try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Throwable) {}
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Throwable) {}
        }
        web = null
        try { presentation?.dismiss() } catch (_: Throwable) {}
        presentation = null
        try { display?.release() } catch (_: Throwable) {}
        display = null
    }

    fun destroy() { detach(); saved = null }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(c: Context): WebView = WebView(c).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true // never start sound over the IPTV audio by itself
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(android.graphics.Color.WHITE)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@CarWebBrowser.progress = newProgress; refreshNav(view); onChange() }
            override fun onReceivedTitle(view: WebView, t: String?) {
                this@CarWebBrowser.title = t.orEmpty(); onChange()
                if (!this@CarWebBrowser.loading && this@CarWebBrowser.error == null) BrowserHistory.record(ctx, this@CarWebBrowser.url, this@CarWebBrowser.title)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // tel:, intent:, market: ... cannot open on the car display: stay on the page.
                val s = request.url.scheme?.lowercase()
                return s != "http" && s != "https" && s != "about" && s != "data" && s != "blob"
            }
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                if (!u.isNullOrEmpty() && u != "about:blank") this@CarWebBrowser.url = u
                this@CarWebBrowser.loading = true; this@CarWebBrowser.error = null; this@CarWebBrowser.progress = 0; refreshNav(view); onChange()
            }
            override fun onPageFinished(view: WebView, u: String?) {
                if (!u.isNullOrEmpty() && u != "about:blank") this@CarWebBrowser.url = u
                this@CarWebBrowser.loading = false; this@CarWebBrowser.progress = 100; this@CarWebBrowser.title = view.title ?: this@CarWebBrowser.title; refreshNav(view); onChange()
                if (this@CarWebBrowser.error == null) BrowserHistory.record(ctx, this@CarWebBrowser.url, this@CarWebBrowser.title)
            }
            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                if (!u.isNullOrEmpty() && u != "about:blank") this@CarWebBrowser.url = u
                refreshNav(view); onChange()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                if (!request.isForMainFrame) return
                if (e.errorCode == ERROR_UNKNOWN && e.description?.contains("ERR_ABORTED") == true) return
                this@CarWebBrowser.loading = false
                this@CarWebBrowser.error = when {
                    e.description?.contains("ERR_NAME_NOT_RESOLVED") == true -> "This site can't be reached"
                    e.description?.contains("ERR_INTERNET_DISCONNECTED") == true -> "No internet connection"
                    else -> e.description?.toString()?.takeIf { it.isNotBlank() } ?: "The page could not be loaded"
                }
                onChange()
            }
        }
    }

    private fun refreshNav(v: WebView) { this@CarWebBrowser.canBack = v.canGoBack(); this@CarWebBrowser.canForward = v.canGoForward() }

    // ---- commands ----------------------------------------------------------------------------

    fun load(target: String) {
        // Already on this page (live, or saved for the next attach): do not reload it.
        if (error == null && WebUrls.same(url, target) && (web != null || saved != null) && pendingUrl == null) return
        this@CarWebBrowser.url = target; this@CarWebBrowser.title = ""; this@CarWebBrowser.error = null
        val wv = web
        if (wv == null) {
            pendingUrl = target; saved = null
            onChange()
            return // loads when the browser screen attaches; nothing is loading yet
        }
        this@CarWebBrowser.loading = true; this@CarWebBrowser.progress = 0
        BrowserHistory.record(ctx, target, "") // visible on the phone immediately
        wv.loadUrl(target)
        onChange()
    }

    fun back() { web?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { web?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { this@CarWebBrowser.error = null; web?.reload() }
    fun stop() { web?.stopLoading(); this@CarWebBrowser.loading = false; onChange() }

    // ---- touch (car surface coordinates -> page) -------------------------------------------

    /** [x],[y]: coordinates inside the car area. The toolbar region is handled by the caller. */
    fun click(x: Float, y: Float) {
        val wv = web ?: return
        val py = y - topInset
        if (py < 0) return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, py, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, py, 0)
        wv.dispatchTouchEvent(down)
        wv.postDelayed({ wv.dispatchTouchEvent(up); up.recycle() }, 60)
        down.recycle()
    }

    fun scroll(dx: Float, dy: Float) { web?.scrollBy(dx.toInt(), dy.toInt()) }
    fun fling(vx: Float, vy: Float) { web?.flingScroll(-vx.toInt(), -vy.toInt()) }
}
