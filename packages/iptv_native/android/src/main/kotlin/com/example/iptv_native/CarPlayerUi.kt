package com.example.iptv_native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat

/**
 * The car player UI, drawn over the video / artwork inside the same Surface so it never changes the
 * picture size. Same design as the phone's full-screen player: top bar (list, title, favorite, fit/fill),
 * bottom bar (LIVE progress, previous / play-pause / next). Taps arrive through SurfaceCallback.onClick
 * and are mapped to [Hit]s by [hitTest].
 */
internal class CarPlayerUi(private val ctx: Context) {

    enum class Hit { LIST, FAVORITE, FIT, PREV, PLAY, NEXT, SEEK }

    data class State(
        val title: String?,
        val playing: Boolean,
        val favorite: Boolean?, // null = no channel
        val fill: Boolean,
        val banner: Pair<String, String>?,
        val visible: Boolean,
        /** Local video: (position seconds, duration seconds). null = live IPTV channel ("LIVE" bar). */
        val progress: Pair<Long, Long>? = null,
    )

    private val hits = ArrayList<Pair<RectF, Hit>>()
    private var seekBar: RectF? = null

    /** Fraction (0..1) of the progress bar under an area-local tap, or null when it is not on the bar. */
    fun seekFraction(x: Float, y: Float): Float? {
        val b = seekBar ?: return null
        if (!b.contains(x, y)) return null
        return ((x - b.left) / b.width()).coerceIn(0f, 1f)
    }

    private fun fmt(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Area-local coordinates (the bitmap's own pixel space). */
    fun hitTest(x: Float, y: Float): Hit? = hits.firstOrNull { it.first.contains(x, y) }?.second

    private fun drawIcon(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, hit: Hit?) {
        val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
        d.setTint(Color.WHITE)
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(c)
        if (hit != null) {
            val pad = size * 0.35f
            hits.add(RectF(cx - half - pad, cy - half - pad, cx + half + pad, cy + half + pad) to hit)
        }
    }

    fun render(w: Int, h: Int, s: State): Bitmap? {
        if (w <= 0 || h <= 0) return null
        hits.clear()
        seekBar = null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val fw = w.toFloat()
        val fh = h.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val mid = fw / 2f

        if (s.visible) {
        // scrims
        paint.shader = LinearGradient(0f, 0f, 0f, fh * 0.22f, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh * 0.22f, paint)
        paint.shader = LinearGradient(0f, fh, 0f, fh * 0.60f, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, fh * 0.60f, fw, fh, paint)
        paint.shader = null

        val margin = fw * 0.03f
        val ic = fh * 0.085f

        // top bar: title only (the host's own list/back button already sits at the edge)
        val topY = fh * 0.08f
        drawIcon(c, R.drawable.ic_car_list, margin + ic / 2, topY, ic, Hit.LIST)
        if (!s.title.isNullOrBlank()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = fh * 0.05f; typeface = Typeface.DEFAULT_BOLD
            }
            val left = margin + ic * 1.5f
            val t = android.text.TextUtils.ellipsize(s.title, android.text.TextPaint(tp), (fw - left - margin).coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END)
            c.drawText(t.toString(), left, topY + tp.textSize * 0.35f, tp)
        }

        val barY = fh * 0.80f
        val th = (fh * 0.008f).coerceAtLeast(4f)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = fh * 0.032f; typeface = Typeface.DEFAULT_BOLD
        }
        val prog = s.progress
        if (prog == null) {
            // LIVE bar (full, not interactive)
            paint.color = 0xFFE53935.toInt()
            c.drawCircle(margin + th * 1.2f, barY, th * 1.2f, paint)
            val labelX = margin + th * 3.2f
            c.drawText("LIVE", labelX, barY + lp.textSize * 0.35f, lp)
            val barL = labelX + lp.measureText("LIVE") + fw * 0.02f
            val barR = fw - margin
            paint.color = 0x55FFFFFF
            c.drawRoundRect(RectF(barL, barY - th / 2, barR, barY + th / 2), th, th, paint)
            paint.color = 0xFFE53935.toInt()
            c.drawRoundRect(RectF(barL, barY - th / 2, barR, barY + th / 2), th, th, paint)
        } else {
            // Local video: elapsed time, seekable progress bar (tap to jump), total time.
            val (pos, dur) = prog
            val left = fmt(pos)
            val right = if (dur > 0) fmt(dur) else "--:--"
            val lw = lp.measureText("00:00:00")
            c.drawText(left, margin, barY + lp.textSize * 0.35f, lp)
            val barL = margin + lw + fw * 0.015f
            val barR = fw - margin - lw - fw * 0.015f
            c.drawText(right, barR + fw * 0.015f, barY + lp.textSize * 0.35f, lp)
            paint.color = 0x55FFFFFF
            c.drawRoundRect(RectF(barL, barY - th / 2, barR, barY + th / 2), th, th, paint)
            val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            paint.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(RectF(barL, barY - th / 2, barL + (barR - barL) * f, barY + th / 2), th, th, paint)
            c.drawCircle(barL + (barR - barL) * f, barY, th * 1.8f, paint)
            if (dur > 0) seekBar = RectF(barL - th, barY - fh * 0.04f, barR + th, barY + fh * 0.04f)
        }

        // transport
        val rowY = fh * 0.91f
        val step = fh * 0.26f
        drawIcon(c, R.drawable.ic_car_prev, mid - step, rowY, fh * 0.11f, Hit.PREV)
        drawIcon(c, if (s.playing) R.drawable.ic_car_pause else R.drawable.ic_car_play, mid, rowY, fh * 0.15f, Hit.PLAY)
        drawIcon(c, R.drawable.ic_car_next, mid + step, rowY, fh * 0.11f, Hit.NEXT)
        // Favorite and Fit/Fill at the bottom right edge, on the same line as play/pause.
        var rx = fw - margin - ic / 2
        drawIcon(c, if (s.fill) R.drawable.ic_car_fit else R.drawable.ic_car_fill, rx, rowY, ic, Hit.FIT)
        if (s.favorite != null) {
            rx -= ic * 1.8f
            drawIcon(c, if (s.favorite) R.drawable.ic_car_fav else R.drawable.ic_car_fav_border, rx, rowY, ic, Hit.FAVORITE)
        }

        } // visible

        // status banner under the top bar
        s.banner?.let { (title, text) ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = fh * 0.05f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFCFD8DC.toInt(); textSize = fh * 0.04f; textAlign = Paint.Align.CENTER
            }
            val maxW = fw * 0.7f
            fun fit(t: String, p: Paint) = android.text.TextUtils.ellipsize(
                t, android.text.TextPaint(p), maxW - 48f, android.text.TextUtils.TruncateAt.END).toString()
            val t1 = fit(title, tp)
            val t2 = fit(text, sp)
            val boxH = tp.textSize * 1.6f + (if (t2.isNotBlank()) sp.textSize * 1.5f else 0f) + 24f
            val top = fh * 0.26f
            val box = RectF(mid - maxW / 2, top, mid + maxW / 2, top + boxH)
            paint.color = 0xCC000000.toInt()
            c.drawRoundRect(box, 20f, 20f, paint)
            var y = box.top + 12f + tp.textSize * 1.1f
            c.drawText(t1, mid, y, tp)
            if (t2.isNotBlank()) { y += sp.textSize * 1.5f; c.drawText(t2, mid, y, sp) }
        }
        return out
    }
}
