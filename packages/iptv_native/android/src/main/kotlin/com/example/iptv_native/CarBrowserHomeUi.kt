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

/**
 * The car browser's home page, drawn into the car Surface exactly like the phone's Browser tab home:
 * search pill on top, "Popular sites" as an icon grid with names, "Recently visited" history below.
 * Android Auto templates cannot combine a search field, a grid and a list, so (like the page itself and
 * the video player) this is drawn into the Surface and driven by SurfaceCallback taps and scrolls.
 * Colours follow the phone's Material 3 scheme (light/dark setting and accent).
 */
internal class CarBrowserHomeUi(private val ctx: Context) {
    enum class Kind { SEARCH, CURRENT, SITE, HISTORY, CLEAR }
    data class Hit(val kind: Kind, val index: Int = 0)
    class SiteTile(val site: PopularSites.Site, val icon: Bitmap?)
    class Row(val title: String, val url: String)

    data class State(
        val sites: List<SiteTile>,
        val history: List<Row>,
        val current: Row?,
        val dark: Boolean,
        val accent: String?,
        val iconsLoaded: Int,
    ) {
        // Bitmaps are not part of equality; iconsLoaded changes when one arrives.
        override fun equals(other: Any?) = other is State && sites.map { it.site } == other.sites.map { it.site } &&
            history.map { it.url to it.title } == other.history.map { it.url to it.title } &&
            current?.url == other.current?.url && current?.title == other.current?.title &&
            dark == other.dark && accent == other.accent && iconsLoaded == other.iconsLoaded
        override fun hashCode() = history.size * 31 + iconsLoaded
    }

    private class Palette(
        val surface: Int, val container: Int, val secondary: Int, val onSurface: Int, val onVariant: Int, val primary: Int,
    )

    private fun palette(s: State): Palette {
        if (s.dark) {
            val p = when (s.accent) {
                "blue" -> 0xFFAAC7FF; "red" -> 0xFFFFB4AB; "green" -> 0xFF78DC77
                "purple" -> 0xFFDDB8FF; "orange" -> 0xFFFFB68A; else -> 0xFFCFBCFF
            }.toInt()
            return Palette(0xFF141218.toInt(), 0xFF2B2930.toInt(), 0xFF4A4458.toInt(), 0xFFE6E0E9.toInt(), 0xFFCAC4D0.toInt(), p)
        }
        val p = when (s.accent) {
            "blue" -> 0xFF415F91; "red" -> 0xFF904A45; "green" -> 0xFF2E6B2D
            "purple" -> 0xFF7B4F9D; "orange" -> 0xFF8F4C1F; else -> 0xFF6750A4
        }.toInt()
        return Palette(0xFFFEF7FF.toInt(), 0xFFECE6F0.toInt(), 0xFFE8DEF8.toInt(), 0xFF1D1B20.toInt(), 0xFF49454F.toInt(), p)
    }

    private val hits = ArrayList<Pair<RectF, Hit>>()
    private var headerBottom = 0f
    private var viewBottom = Float.MAX_VALUE
    /** Largest useful scroll offset of the last render. */
    var maxScroll = 0f; private set

    fun hitTest(x: Float, y: Float): Hit? = hits.lastOrNull { it.first.contains(x, y) }?.second

    private fun draw(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, tint: Int) {
        val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
        d.setTint(tint)
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(c)
    }

    private fun fit(t: String, p: Paint, max: Float) =
        TextUtils.ellipsize(t, TextPaint(p), max, TextUtils.TruncateAt.END).toString()

    private fun host(u: String) = try { android.net.Uri.parse(u).host.orEmpty().removePrefix("www.") } catch (_: Throwable) { "" }

    fun render(w: Int, h: Int, scroll: Float, s: State, bottomInset: Float = 0f): Bitmap? {
        if (w <= 0 || h <= 0) return null
        hits.clear()
        viewBottom = h - bottomInset
        val pal = palette(s)
        val u = (h / 720f).coerceIn(0.6f, 1.7f)
        val m = 24f * u
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = pal.surface
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        fun text(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; textAlign = align
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

        // ---- content (scrolls under the fixed search bar) ----
        val pillH = 76f * u
        headerBottom = 16f * u + pillH + 12f * u
        c.save()
        c.clipRect(0f, headerBottom, w.toFloat(), h - bottomInset)
        c.translate(0f, -scroll)
        var y = headerBottom + 8f * u
        val contentLeft = m
        val contentW = w - 2 * m

        if (s.current != null) {
            val card = RectF(m, y, w - m, y + 92f * u)
            paint.color = pal.secondary
            c.drawRoundRect(card, 16f * u, 16f * u, paint)
            draw(c, R.drawable.ic_car_web, card.left + 40f * u, card.centerY(), 36f * u, pal.onSurface)
            val tx = card.left + 80f * u
            val mw = card.right - tx - m
            c.drawText(fit(s.current.title.ifBlank { host(s.current.url).ifBlank { s.current.url } }, text(pal.onSurface, 26f * u, true), mw),
                tx, card.centerY() - 4f * u, text(pal.onSurface, 26f * u, true))
            c.drawText(fit(s.current.url, text(pal.onVariant, 20f * u), mw), tx, card.centerY() + 26f * u, text(pal.onVariant, 20f * u))
            hitsAdd(card, Hit(Kind.CURRENT), scroll)
            y = card.bottom + 20f * u
        }

        c.drawText("Popular sites", contentLeft, y + 28f * u, text(pal.onSurface, 30f * u, true))
        y += 48f * u

        val cols = 4
        val colW = contentW / cols
        val tile = (76f * u).coerceAtMost(colW * 0.7f)
        val nameP = text(pal.onSurface, 21f * u, false, Paint.Align.CENTER)
        val rowH = tile + 44f * u
        s.sites.forEachIndexed { i, st ->
            val cx = contentLeft + colW * (i % cols) + colW / 2f
            val top = y + (i / cols) * (rowH + 8f * u)
            val box = RectF(cx - tile / 2, top, cx + tile / 2, top + tile)
            if (st.icon != null) {
                paint.color = Color.WHITE
                c.drawRoundRect(box, tile * 0.27f, tile * 0.27f, paint)
                val pad = tile * 0.2f
                c.drawBitmap(st.icon, null, RectF(box.left + pad, box.top + pad, box.right - pad, box.bottom - pad), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                paint.color = st.site.color
                c.drawRoundRect(box, tile * 0.27f, tile * 0.27f, paint)
                val lp = text(Color.WHITE, tile * 0.45f, true, Paint.Align.CENTER)
                c.drawText(st.site.name.first().uppercase(), box.centerX(), box.centerY() - (lp.descent() + lp.ascent()) / 2f, lp)
            }
            c.drawText(fit(st.site.name, nameP, colW - 8f * u), cx, box.bottom + 28f * u, nameP)
            hitsAdd(RectF(cx - colW / 2, top - 4f * u, cx + colW / 2, top + rowH), Hit(Kind.SITE, i), scroll)
        }
        val gridRows = (s.sites.size + cols - 1) / cols
        y += gridRows * (rowH + 8f * u) + 12f * u

        if (s.history.isNotEmpty()) {
            c.drawText("Recently visited", contentLeft, y + 28f * u, text(pal.onSurface, 30f * u, true))
            val clear = text(pal.primary, 24f * u, true, Paint.Align.RIGHT)
            c.drawText("Clear", w - m, y + 28f * u, clear)
            hitsAdd(RectF(w - m - 140f * u, y - 8f * u, w - m + 8f * u, y + 44f * u), Hit(Kind.CLEAR), scroll)
            y += 52f * u
            s.history.forEachIndexed { i, r ->
                val rowTop = y + i * 76f * u
                draw(c, R.drawable.ic_car_history, contentLeft + 22f * u, rowTop + 38f * u, 32f * u, pal.onVariant)
                val tx = contentLeft + 68f * u
                val mw = w - m - tx
                val tp = text(pal.onSurface, 25f * u)
                val sp = text(pal.onVariant, 19f * u)
                c.drawText(fit(r.title.ifBlank { host(r.url).ifBlank { r.url } }, tp, mw), tx, rowTop + 32f * u, tp)
                c.drawText(fit(r.url, sp, mw), tx, rowTop + 58f * u, sp)
                hitsAdd(RectF(0f, rowTop, w.toFloat(), rowTop + 76f * u), Hit(Kind.HISTORY, i), scroll)
            }
            y += s.history.size * 76f * u
        }
        maxScroll = (y + m - (h - bottomInset)).coerceAtLeast(0f)
        c.restore()

        // ---- fixed search bar (same pill as the phone's address bar) ----
        paint.color = pal.surface
        c.drawRect(0f, 0f, w.toFloat(), headerBottom, paint)
        val hostStrip = maxOf(m, w * CarBrowserUi.HOST_STRIP_FRACTION) // host draws "Home" over the top-right corner
        val pill = RectF(m, 16f * u, w - hostStrip, 16f * u + pillH)
        paint.color = pal.container
        c.drawRoundRect(pill, pillH / 2, pillH / 2, paint)
        draw(c, R.drawable.ic_car_search, pill.left + 40f * u, pill.centerY(), 34f * u, pal.onVariant)
        val hint = text(pal.onVariant, 27f * u)
        c.drawText(fit("Search Google or type a URL", hint, pill.width() - 100f * u), pill.left + 76f * u, pill.centerY() + 9f * u, hint)
        hits.add(pill to Hit(Kind.SEARCH))
        return out
    }

    /** Content hits are stored in screen coordinates and ignored under the fixed header. */
    private fun hitsAdd(r: RectF, hit: Hit, scroll: Float) {
        val s = RectF(r.left, r.top - scroll, r.right, r.bottom - scroll)
        s.top = maxOf(s.top, headerBottom)
        s.bottom = minOf(s.bottom, viewBottom)
        if (s.bottom > s.top) hits.add(s to hit)
    }
}
