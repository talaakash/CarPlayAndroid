package com.example.iptv_native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat

/** Material 3 colours of the phone app (light / dark setting + accent), for everything drawn on the car Surface. */
internal class CarPalette(
    val header: Int, val surface: Int, val container: Int, val containerHigh: Int, val highest: Int,
    val secondary: Int, val onSurface: Int, val onVariant: Int, val primary: Int, val onPrimary: Int,
    val error: Int, val outline: Int,
) {
    companion object {
        fun of(dark: Boolean, accent: String?): CarPalette {
            if (dark) {
                val p = when (accent) {
                    "blue" -> 0xFFAAC7FF; "red" -> 0xFFFFB4AB; "green" -> 0xFF78DC77
                    "purple" -> 0xFFDDB8FF; "orange" -> 0xFFFFB68A; else -> 0xFFCFBCFF
                }.toInt()
                return CarPalette(0xFF6750A4.toInt(), 0xFF141218.toInt(), 0xFF211F26.toInt(), 0xFF2B2930.toInt(), 0xFF36343B.toInt(),
                    0xFF4A4458.toInt(), 0xFFE6E0E9.toInt(), 0xFFCAC4D0.toInt(), p, 0xFF381E72.toInt(), 0xFFF2B8B5.toInt(), 0xFF938F99.toInt())
            }
            val p = when (accent) {
                "blue" -> 0xFF415F91; "red" -> 0xFF904A45; "green" -> 0xFF2E6B2D
                "purple" -> 0xFF7B4F9D; "orange" -> 0xFF8F4C1F; else -> 0xFF6750A4
            }.toInt()
            return CarPalette(0xFFD0BCFF.toInt(), 0xFFFEF7FF.toInt(), 0xFFF3EDF7.toInt(), 0xFFECE6F0.toInt(), 0xFFE6E0E9.toInt(),
                0xFFE8DEF8.toInt(), 0xFF1D1B20.toInt(), 0xFF49454F.toInt(), p, Color.WHITE, 0xFFB3261E.toInt(), 0xFF79747E.toInt())
        }
    }
}

/**
 * The car's IPTV and Local Video screens plus the shared chrome (mini-player + bottom navigation bar),
 * drawn into the car Surface to look like the phone app. Android Auto templates cannot reproduce the phone's
 * chip row, search field, logos and bottom bar, so (like the browser home, the page and the video) these
 * are drawn into the Surface and driven by SurfaceCallback taps / scrolls.
 */
internal class CarShellUi(private val ctx: Context) {
    enum class Kind { NAV, MINI, MINI_PLAY, MINI_PREV, MINI_NEXT, CHIP, SEARCH, SEARCH_CLEAR, CLEAR_RECENTS, ROW, FAV }
    data class Hit(val kind: Kind, val index: Int = 0)

    class Mini(val title: String, val status: String, val logo: Bitmap?, val playing: Boolean)

    class Frame(
        val palette: CarPalette,
        val title: String,
        val status: String,
        val headerIcon: Int,
        val navSelected: Int,
        val mini: Mini?,
    )

    class Chip(val label: String, val scope: String, val icon: Int, val selected: Boolean)

    class IptvContent(
        val chips: List<Chip>,
        val search: String,
        val searchHint: String,
        val channels: List<IptvStorage.Channel>,
        val favIds: Set<String>,
        val currentId: String?,
        val playing: Boolean,
        val emptyText: String?,
        val showClear: Boolean,
        val logo: (IptvStorage.Channel) -> Bitmap?,
    )

    class LocalMeta(val thumb: Bitmap?, val durationMs: Long, val sizeBytes: Long)

    class LocalContent(
        val videos: List<IptvStorage.Channel>,
        val currentId: String?,
        val playing: Boolean,
        val meta: (IptvStorage.Channel) -> LocalMeta?,
    )

    private val hits = ArrayList<Pair<RectF, Hit>>()
    private val chromeHits = ArrayList<Pair<RectF, Hit>>()
    private var listTop = 0f
    private var listBottom = 0f

    var maxScroll = 0f; private set
    var maxChipScroll = 0f; private set

    /** Only the bottom bar / mini-player (the browser home has its own list targets). */
    fun hitTestChrome(x: Float, y: Float): Hit? = chromeHits.lastOrNull { it.first.contains(x, y) }?.second

    fun hitTest(x: Float, y: Float): Hit? =
        chromeHits.lastOrNull { it.first.contains(x, y) }?.second ?: hits.lastOrNull { it.first.contains(x, y) }?.second

    companion object {
        fun unit(h: Int) = (h / 720f).coerceIn(0.6f, 1.7f)
        fun navHeight(h: Int) = 84f * unit(h)
        fun miniHeight(h: Int) = 80f * unit(h)
        fun rowHeight(h: Int) = 84f * unit(h)
        fun localRowHeight(h: Int) = 96f * unit(h)
        const val NAV_IPTV = 0
        const val NAV_LOCAL = 1
        const val NAV_BROWSER = 2
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun text(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun fit(t: String, p: Paint, max: Float) =
        TextUtils.ellipsize(t, TextPaint(p), max, TextUtils.TruncateAt.END).toString()

    private fun icon(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, tint: Int) {
        val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
        d.setTint(tint)
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(c)
    }

    private fun addHit(list: ArrayList<Pair<RectF, Hit>>, r: RectF, hit: Hit, clipTop: Float = -1f, clipBottom: Float = -1f) {
        val rr = RectF(r)
        if (clipTop >= 0f) rr.top = maxOf(rr.top, clipTop)
        if (clipBottom >= 0f) rr.bottom = minOf(rr.bottom, clipBottom)
        if (rr.bottom > rr.top) list.add(rr to hit)
    }

    /** Header bar (the phone's AppBar): icon, title and status line. Returns its height. */
    private fun drawHeader(c: Canvas, w: Int, h: Int, f: Frame): Float {
        val u = unit(h)
        val bar = 84f * u
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = f.palette.header
        c.drawRect(0f, 0f, w.toFloat(), bar, p)
        val onHeader = if (f.palette.header == 0xFF6750A4.toInt()) Color.WHITE else 0xFF1D1B20.toInt()
        icon(c, f.headerIcon, 44f * u, bar / 2, 34f * u, onHeader)
        val strip = w * CarBrowserUi.HOST_STRIP_FRACTION // the host draws its action over the top-right corner
        val maxW = w - strip - 90f * u
        val t = text(onHeader, 30f * u, true)
        val s = text(onHeader, 19f * u)
        if (f.status.isBlank()) c.drawText(fit(f.title, t, maxW), 84f * u, bar / 2 + 10f * u, t)
        else {
            c.drawText(fit(f.title, t, maxW), 84f * u, bar / 2 - 2f * u, t)
            c.drawText(fit(f.status, s, maxW), 84f * u, bar / 2 + 24f * u, s)
        }
        return bar
    }

    /** Mini-player + bottom navigation bar. Returns the height they take at the bottom. */
    fun drawChrome(c: Canvas, w: Int, h: Int, f: Frame, withMini: Boolean): Float {
        chromeHits.clear()
        val u = unit(h)
        val pal = f.palette
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val nav = navHeight(h)
        var top = h - nav
        // Navigation bar
        p.color = pal.container
        c.drawRect(0f, top, w.toFloat(), h.toFloat(), p)
        val items = listOf(Triple("IPTV", R.drawable.ic_car_tv, NAV_IPTV), Triple("Local Video", R.drawable.ic_car_video, NAV_LOCAL), Triple("Browser", R.drawable.ic_car_web, NAV_BROWSER))
        val slot = w / items.size.toFloat()
        items.forEachIndexed { i, (label, res, id) ->
            val cx = slot * i + slot / 2
            val sel = f.navSelected == id
            if (sel) {
                p.color = pal.secondary
                c.drawRoundRect(RectF(cx - 40f * u, top + 8f * u, cx + 40f * u, top + 8f * u + 36f * u), 18f * u, 18f * u, p)
            }
            icon(c, res, cx, top + 26f * u, 26f * u, if (sel) pal.onSurface else pal.onVariant)
            c.drawText(label, cx, top + 66f * u, text(if (sel) pal.onSurface else pal.onVariant, 19f * u, sel, Paint.Align.CENTER))
            addHit(chromeHits, RectF(slot * i, top, slot * (i + 1), h.toFloat()), Hit(Kind.NAV, id))
        }
        var used = nav
        if (withMini && f.mini != null) {
            val mh = miniHeight(h)
            val mt = top - mh
            p.color = pal.containerHigh
            c.drawRect(0f, mt, w.toFloat(), top, p)
            val m = f.mini
            val lh = 56f * u
            drawLogo(c, m.logo, RectF(16f * u, mt + (mh - lh) / 2, 16f * u + lh, mt + (mh + lh) / 2), pal)
            val tx = 16f * u + lh + 16f * u
            val btn = 56f * u
            val rightEdge = w - 12f * u
            val maxW = rightEdge - 3 * btn - tx - 8f * u
            c.drawText(fit(m.title, text(pal.onSurface, 25f * u, true), maxW), tx, mt + mh / 2 - 2f * u, text(pal.onSurface, 25f * u, true))
            c.drawText(fit(m.status, text(pal.onVariant, 19f * u), maxW), tx, mt + mh / 2 + 24f * u, text(pal.onVariant, 19f * u))
            val cy = mt + mh / 2
            val nextX = rightEdge - btn / 2
            val playX = nextX - btn
            val prevX = playX - btn
            icon(c, R.drawable.ic_car_prev, prevX, cy, 34f * u, pal.onSurface)
            p.color = pal.primary
            c.drawCircle(playX, cy, 27f * u, p)
            icon(c, if (m.playing) R.drawable.ic_car_pause else R.drawable.ic_car_play, playX, cy, 32f * u, pal.onPrimary)
            icon(c, R.drawable.ic_car_next, nextX, cy, 34f * u, pal.onSurface)
            addHit(chromeHits, RectF(0f, mt, prevX - btn / 2, top), Hit(Kind.MINI))
            addHit(chromeHits, RectF(prevX - btn / 2, mt, prevX + btn / 2, top), Hit(Kind.MINI_PREV))
            addHit(chromeHits, RectF(playX - btn / 2, mt, playX + btn / 2, top), Hit(Kind.MINI_PLAY))
            addHit(chromeHits, RectF(nextX - btn / 2, mt, nextX + btn / 2, top), Hit(Kind.MINI_NEXT))
            used += mh
            top = mt
        }
        return used
    }

    private fun drawLogo(c: Canvas, bmp: Bitmap?, box: RectF, pal: CarPalette) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = box.height() * 0.125f
        if (bmp != null) {
            c.save(); val path = android.graphics.Path().apply { addRoundRect(box, r, r, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            c.drawBitmap(bmp, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            p.color = pal.highest
            c.drawRoundRect(box, r, r, p)
            icon(c, R.drawable.ic_car_tv, box.centerX(), box.centerY(), box.height() * 0.5f, pal.onVariant)
        }
    }

    private fun base(w: Int, h: Int, pal: CarPalette): Pair<Bitmap, Canvas> {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(pal.surface)
        return out to c
    }

    // ---- IPTV -----------------------------------------------------------------------------

    fun renderIptv(w: Int, h: Int, f: Frame, d: IptvContent, scroll: Float, chipScroll: Float): Bitmap? {
        if (w <= 0 || h <= 0) return null
        hits.clear()
        val pal = f.palette
        val u = unit(h)
        val (out, c) = base(w, h, pal)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val m = 16f * u

        val chrome = navHeight(h) + if (f.mini != null) miniHeight(h) else 0f
        listBottom = h - chrome
        val headerH = 84f * u
        var y = headerH

        // Chips row (horizontal scroll)
        val chipsH = 64f * u
        val chipBoxH = 44f * u
        val chipTop = y + (chipsH - chipBoxH) / 2
        val cp = text(pal.onSurface, 22f * u)
        var x = m - chipScroll
        c.save(); c.clipRect(0f, y, w.toFloat(), y + chipsH)
        d.chips.forEachIndexed { i, ch ->
            val tw = cp.measureText(ch.label)
            val cw = 12f * u + 22f * u + 8f * u + tw + 14f * u
            val box = RectF(x, chipTop, x + cw, chipTop + chipBoxH)
            if (ch.selected) { p.color = pal.secondary; p.style = Paint.Style.FILL; c.drawRoundRect(box, 10f * u, 10f * u, p) }
            else { p.color = pal.outline; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u; c.drawRoundRect(box, 10f * u, 10f * u, p); p.style = Paint.Style.FILL }
            icon(c, ch.icon, box.left + 12f * u + 11f * u, box.centerY(), 22f * u, pal.onSurface)
            c.drawText(ch.label, box.left + 12f * u + 22f * u + 8f * u, box.centerY() + 8f * u, cp)
            addHit(hits, box, Hit(Kind.CHIP, i), y, y + chipsH)
            x += cw + 8f * u
        }
        c.restore()
        maxChipScroll = (x + chipScroll + m - w).coerceAtLeast(0f)
        y += chipsH

        // Search field (outlined, like the phone's TextField)
        val sh = 60f * u
        val sbox = RectF(m, y + 4f * u, w - m, y + 4f * u + sh)
        p.color = pal.outline; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u
        c.drawRoundRect(sbox, 6f * u, 6f * u, p); p.style = Paint.Style.FILL
        icon(c, R.drawable.ic_car_search, sbox.left + 32f * u, sbox.centerY(), 30f * u, pal.onVariant)
        val q = d.search
        val sp = text(if (q.isEmpty()) pal.onVariant else pal.onSurface, 25f * u)
        c.drawText(fit(if (q.isEmpty()) d.searchHint else q, sp, sbox.width() - 130f * u), sbox.left + 64f * u, sbox.centerY() + 9f * u, sp)
        addHit(hits, sbox, Hit(Kind.SEARCH))
        if (q.isNotEmpty()) {
            icon(c, R.drawable.ic_car_close, sbox.right - 36f * u, sbox.centerY(), 28f * u, pal.onVariant)
            addHit(hits, RectF(sbox.right - 80f * u, sbox.top, sbox.right, sbox.bottom), Hit(Kind.SEARCH_CLEAR))
        }
        y = sbox.bottom + 8f * u
        listTop = y

        // Optional "Clear history" line (Recent view)
        if (d.showClear && d.channels.isNotEmpty()) {
            val clear = text(pal.primary, 23f * u, true, Paint.Align.RIGHT)
            c.drawText("Clear history", w - m, y + 34f * u, clear)
            addHit(hits, RectF(w - m - 220f * u, y, w.toFloat(), y + 48f * u), Hit(Kind.CLEAR_RECENTS))
            y += 48f * u
            listTop = y
        }

        // Rows
        val rh = rowHeight(h)
        if (d.emptyText != null || d.channels.isEmpty()) {
            val msg = d.emptyText ?: "No channels match."
            val tp = text(pal.onSurface, 26f * u, false, Paint.Align.CENTER)
            val lines = msg.split('\n')
            val mid = (listTop + listBottom) / 2
            lines.forEachIndexed { i, l -> c.drawText(l, w / 2f, mid + (i - (lines.size - 1) / 2f) * 36f * u, tp) }
            maxScroll = 0f
        } else {
            c.save(); c.clipRect(0f, listTop, w.toFloat(), listBottom)
            val first = (scroll / rh).toInt().coerceAtLeast(0)
            val last = ((scroll + (listBottom - listTop)) / rh).toInt() + 1
            for (i in first..minOf(last, d.channels.size - 1)) {
                val ch = d.channels[i]
                val top = listTop + i * rh - scroll
                val current = ch.id == d.currentId
                val title = text(if (current) pal.primary else pal.onSurface, 26f * u)
                val sub = text(if (current) pal.primary else pal.onVariant, 20f * u)
                val logoS = 56f * u
                val box = RectF(m + 4f * u, top + (rh - logoS) / 2, m + 4f * u + logoS, top + (rh + logoS) / 2)
                drawLogo(c, d.logo(ch), box, pal)
                if (current) {
                    p.color = pal.primary
                    c.drawCircle(box.right - 2f * u, box.bottom - 2f * u, 12f * u, p)
                    icon(c, if (d.playing) R.drawable.ic_car_nowplaying else R.drawable.ic_car_pause, box.right - 2f * u, box.bottom - 2f * u, 16f * u, pal.onPrimary)
                }
                val tx = box.right + 20f * u
                val favW = 76f * u
                val maxW = w - m - favW - tx
                c.drawText(fit(ch.name, title, maxW), tx, top + rh / 2 - 4f * u, title)
                val subText = listOf(ch.group, ch.country).filter { it.isNotBlank() && it != IptvStorage.UNKNOWN_COUNTRY }.joinToString(" · ")
                c.drawText(fit(subText, sub, maxW), tx, top + rh / 2 + 24f * u, sub)
                val fav = ch.id in d.favIds
                icon(c, if (fav) R.drawable.ic_car_fav else R.drawable.ic_car_fav_border, w - m - favW / 2, top + rh / 2, 34f * u, if (fav) pal.error else pal.onVariant)
                addHit(hits, RectF(w - m - favW, top, w.toFloat(), top + rh), Hit(Kind.FAV, i), listTop, listBottom)
                addHit(hits, RectF(0f, top, w - m - favW, top + rh), Hit(Kind.ROW, i), listTop, listBottom)
            }
            c.restore()
            maxScroll = (d.channels.size * rh - (listBottom - listTop)).coerceAtLeast(0f)
        }

        drawHeader(c, w, h, f)
        drawChrome(c, w, h, f, withMini = true)
        return out
    }

    // ---- Local videos ---------------------------------------------------------------------

    fun renderLocal(w: Int, h: Int, f: Frame, d: LocalContent, scroll: Float): Bitmap? {
        if (w <= 0 || h <= 0) return null
        hits.clear()
        val pal = f.palette
        val u = unit(h)
        val (out, c) = base(w, h, pal)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val m = 16f * u
        val chrome = navHeight(h) + if (f.mini != null) miniHeight(h) else 0f
        listTop = 84f * u
        listBottom = h - chrome
        maxChipScroll = 0f

        if (d.videos.isEmpty()) {
            val mid = (listTop + listBottom) / 2
            icon(c, R.drawable.ic_car_video, w / 2f, mid - 70f * u, 76f * u, pal.primary)
            c.drawText("No local videos yet", w / 2f, mid + 4f * u, text(pal.onSurface, 30f * u, true, Paint.Align.CENTER))
            val sp = text(pal.onVariant, 21f * u, false, Paint.Align.CENTER)
            c.drawText("Add MP4, MKV, MOV and other video files in the phone app.", w / 2f, mid + 44f * u, sp)
            c.drawText("They play here on the car display.", w / 2f, mid + 74f * u, sp)
            maxScroll = 0f
        } else {
            val rh = localRowHeight(h)
            c.save(); c.clipRect(0f, listTop, w.toFloat(), listBottom)
            val first = (scroll / rh).toInt().coerceAtLeast(0)
            val last = ((scroll + (listBottom - listTop)) / rh).toInt() + 1
            for (i in first..minOf(last, d.videos.size - 1)) {
                val v = d.videos[i]
                val top = listTop + i * rh - scroll
                val current = v.id == d.currentId
                val meta = d.meta(v)
                val tw = 128f * u
                val th = 72f * u
                val box = RectF(m + 4f * u, top + (rh - th) / 2, m + 4f * u + tw, top + (rh + th) / 2)
                val r = 8f * u
                p.color = pal.highest
                c.drawRoundRect(box, r, r, p)
                if (meta?.thumb != null) {
                    c.save(); c.clipPath(android.graphics.Path().apply { addRoundRect(box, r, r, android.graphics.Path.Direction.CW) })
                    val bw = meta.thumb.width.toFloat(); val bh = meta.thumb.height.toFloat()
                    val sc = maxOf(box.width() / bw, box.height() / bh)
                    val dw = bw * sc; val dh = bh * sc
                    c.drawBitmap(meta.thumb, null, RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                    c.restore()
                } else icon(c, R.drawable.ic_car_video, box.centerX(), box.centerY(), 36f * u, pal.onVariant)
                if (current) {
                    p.color = 0x73000000
                    c.drawRoundRect(box, r, r, p)
                    icon(c, if (d.playing) R.drawable.ic_car_nowplaying else R.drawable.ic_car_pause, box.centerX(), box.centerY(), 36f * u, Color.WHITE)
                }
                val tx = box.right + 20f * u
                val maxW = w - m - tx
                val title = text(if (current) pal.primary else pal.onSurface, 26f * u)
                c.drawText(fit(v.name, title, maxW), tx, top + rh / 2 - 2f * u, title)
                val parts = listOfNotNull(
                    meta?.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
                    meta?.sizeBytes?.takeIf { it > 0 }?.let(::formatSize),
                )
                if (parts.isNotEmpty()) {
                    val sp = text(if (current) pal.primary else pal.onVariant, 20f * u)
                    c.drawText(parts.joinToString(" · "), tx, top + rh / 2 + 28f * u, sp)
                }
                addHit(hits, RectF(0f, top, w.toFloat(), top + rh), Hit(Kind.ROW, i), listTop, listBottom)
            }
            c.restore()
            maxScroll = (d.videos.size * rh - (listBottom - listTop)).coerceAtLeast(0f)
        }
        drawHeader(c, w, h, f)
        drawChrome(c, w, h, f, withMini = true)
        return out
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600; val mm = (s % 3600) / 60; val ss = s % 60
        return if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss) else "%d:%02d".format(mm, ss)
    }

    private fun formatSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> "%.0f MB".format(b / (1024.0 * 1024))
        else -> "%.0f KB".format(b / 1024.0)
    }
}

/** Reads thumbnail, duration and size of a local video (content:// URI) off the main thread, cached. */
internal class LocalVideoMeta(private val ctx: Context, private val onLoaded: () -> Unit) {
    private val cache = HashMap<String, CarShellUi.LocalMeta>()
    private val loading = HashSet<String>()

    fun get(ch: IptvStorage.Channel): CarShellUi.LocalMeta? {
        synchronized(cache) { cache[ch.id]?.let { return it } }
        val start = synchronized(loading) { loading.add(ch.id) }
        if (start) Thread {
            var thumb: Bitmap? = null
            var dur = -1L
            var size = -1L
            try {
                val uri = android.net.Uri.parse(ch.url)
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    thumb = r.getScaledFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 144)
                } finally { try { r.release() } catch (_: Throwable) {} }
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) size = it.getLong(0)
                }
            } catch (_: Throwable) {}
            synchronized(cache) { cache[ch.id] = CarShellUi.LocalMeta(thumb, dur, size) }
            synchronized(loading) { loading.remove(ch.id) }
            onLoaded()
        }.start()
        return null
    }
}
