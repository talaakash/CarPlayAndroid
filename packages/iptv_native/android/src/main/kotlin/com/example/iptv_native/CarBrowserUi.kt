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
 * Toolbar of the car browser, drawn into the same Surface as the page (like [CarPlayerUi] for video):
 * list, back, forward, reload/stop and the address pill (tap = type a URL or search), plus a progress
 * line. The page sits below [barHeight]. Buttons are large for in-car use.
 */
internal class CarBrowserUi(private val ctx: Context) {
    enum class Hit { HOME, BACK, FORWARD, RELOAD, URL }

    data class State(
        val title: String,
        val url: String,
        val loading: Boolean,
        val progress: Int,
        val canBack: Boolean,
        val canForward: Boolean,
        val banner: Pair<String, String>?,
    )

    private val hits = ArrayList<Pair<RectF, Hit>>()

    fun hitTest(x: Float, y: Float): Hit? = hits.firstOrNull { it.first.contains(x, y) }?.second

    companion object {
        /** Toolbar height for an area [h] pixels tall. */
        /** Share of the width taken by the host's Home action in the top-right corner. */
        const val HOST_STRIP_FRACTION = 0.23f

        fun barHeight(h: Int): Int = (h * 0.15f).toInt().coerceIn(84, 190)
    }

    private fun icon(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, hit: Hit, enabled: Boolean) {
        val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
        d.setTint(if (enabled) Color.WHITE else 0x55FFFFFF)
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(c)
        val pad = size * 0.45f
        if (enabled) hits.add(RectF(cx - half - pad, cy - half - pad, cx + half + pad, cy + half + pad) to hit)
    }

    private fun fit(t: String, p: Paint, max: Float) =
        TextUtils.ellipsize(t, TextPaint(p), max, TextUtils.TruncateAt.END).toString()

    fun render(w: Int, h: Int, s: State): Bitmap? {
        if (w <= 0 || h <= 0) return null
        hits.clear()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bar = barHeight(h).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF1B1B1F.toInt()
        c.drawRect(0f, 0f, w.toFloat(), bar, paint)

        val ic = bar * 0.46f
        val slot = bar * 1.1f
        var cx = slot * 0.7f
        val cy = bar / 2f
        icon(c, R.drawable.ic_car_list, cx, cy, ic, Hit.HOME, true); cx += slot
        icon(c, R.drawable.ic_car_chevron_left, cx, cy, ic * 1.15f, Hit.BACK, s.canBack); cx += slot
        icon(c, R.drawable.ic_car_chevron_right, cx, cy, ic * 1.15f, Hit.FORWARD, s.canForward); cx += slot
        icon(c, if (s.loading) R.drawable.ic_car_close else R.drawable.ic_car_refresh, cx, cy, ic, Hit.RELOAD, true)

        // Address pill: site title over host, tap to type.
        val left = cx + slot * 0.7f
        // The host draws its own "Home" action over the top-right corner of this Surface: keep the pill clear of it.
        val hostStrip = maxOf(slot * 0.35f, w * HOST_STRIP_FRACTION)
        val right = if (w - hostStrip - left >= w * 0.25f) w - hostStrip else w - slot * 0.35f
        val pill = RectF(left, bar * 0.16f, right, bar * 0.84f)
        paint.color = 0xFF2E2E34.toInt()
        c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint)
        hits.add(pill to Hit.URL)
        val sIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_car_search)?.mutate()
        val si = pill.height() * 0.5f
        sIcon?.setTint(0xFFB0B0B8.toInt())
        sIcon?.setBounds((pill.left + si * 0.6f).toInt(), (pill.centerY() - si / 2).toInt(), (pill.left + si * 1.6f).toInt(), (pill.centerY() + si / 2).toInt())
        sIcon?.draw(c)
        val tx = pill.left + si * 2.0f
        val maxW = pill.right - tx - si * 0.6f
        val host = try { android.net.Uri.parse(s.url).host.orEmpty().removePrefix("www.") } catch (_: Throwable) { "" }
        val main = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = pill.height() * 0.34f; typeface = Typeface.DEFAULT_BOLD }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B0B8.toInt(); textSize = pill.height() * 0.27f }
        if (s.url.isEmpty()) {
            main.typeface = Typeface.DEFAULT; main.color = 0xFFB0B0B8.toInt()
            c.drawText("Search or type a URL", tx, pill.centerY() + main.textSize * 0.35f, main)
        } else {
            val t1 = s.title.ifBlank { host.ifBlank { s.url } }
            c.drawText(fit(t1, main, maxW), tx, pill.centerY() - sub.textSize * 0.1f, main)
            c.drawText(fit(host.ifBlank { s.url }, sub, maxW), tx, pill.centerY() + sub.textSize * 1.25f, sub)
        }

        // Progress line under the toolbar.
        if (s.loading) {
            paint.color = 0xFF3A3A40.toInt()
            c.drawRect(0f, bar - 6f, w.toFloat(), bar, paint)
            paint.color = 0xFF8AB4F8.toInt()
            c.drawRect(0f, bar - 6f, w * (s.progress.coerceIn(3, 100) / 100f), bar, paint)
        }

        // Status message (driving, start-up problem...) centred over the page area.
        s.banner?.let { (t, sub2) ->
            val bw = w * 0.7f
            val bh = h * 0.28f
            val top = bar + (h - bar - bh) / 2
            val box = RectF((w - bw) / 2, top, (w + bw) / 2, top + bh)
            paint.color = 0xEE1B1B1F.toInt()
            c.drawRoundRect(box, bh * 0.12f, bh * 0.12f, paint)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = bh * 0.2f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B0B8.toInt(); textSize = bh * 0.14f; textAlign = Paint.Align.CENTER }
            c.drawText(fit(t, tp, bw * 0.9f), box.centerX(), box.centerY() - bh * 0.02f, tp)
            c.drawText(fit(sub2, sp, bw * 0.9f), box.centerX(), box.centerY() + bh * 0.22f, sp)
        }
        return out
    }
}
