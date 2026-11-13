package com.canis.hejje.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.canis.hejje.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the Trips-tab dashboard as a picture for the home-screen widget (widgets cannot use custom
 * fonts, gradients or shading). Laid out on a 360 x 160 grid, scaled to fit the widget.
 */
object DashboardBitmap {
    private class Colors(
        val top: Int, val bottom: Int, val edge: Int, val text: Int, val muted: Int, val accent: Int,
        val window: Int, val drumLight: Int, val drumDark: Int, val shade: Int,
    )

    private val dark = Colors(
        0xFF24282C.toInt(), 0xFF15181A.toInt(), 0x0FFFFFFF, 0xFFECE9E6.toInt(), 0xFF8E8984.toInt(), 0xFFFF7A33.toInt(),
        0xFF0B0C0D.toInt(), 0xFF3A3F45.toInt(), 0xFF1A1D20.toInt(), 0xA6000000.toInt(),
    )
    private val light = Colors(
        0xFFFFFFFF.toInt(), 0xFFF3F0EC.toInt(), 0x12000000, 0xFF1E1C1A.toInt(), 0xFF77706A.toInt(), 0xFFE8590C.toInt(),
        0xFFE9E5E0.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0DBD5.toInt(), 0x29000000,
    )

    fun draw(
        context: Context, widthPx: Int, heightPx: Int, night: Boolean,
        value: Float, unit: String, label: String, tripLabel: String, trips: Int, parked: String?,
    ): Bitmap {
        val c = if (night) dark else light
        val bmp = createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val s = min(w / 360f, h / 160f)
        val radius = 24f * s.coerceAtLeast(min(w, h) / 200f).coerceAtMost(s * 1.4f)

        // Panel.
        p.shader = LinearGradient(0f, 0f, 0f, h, c.top, c.bottom, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * s; p.color = c.edge
        cv.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), radius, radius, p)
        p.style = Paint.Style.FILL

        // Content on the 360 x 160 grid, centred.
        cv.withTranslation((w - 360f * s) / 2f, (h - 160f * s) / 2f) {
        scale(s, s)

        // Wordmark, fitted into the left part.
        p.typeface = runCatching { ResourcesCompat.getFont(context, R.font.mallige_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        p.color = c.text
        val name = context.getString(R.string.brand_name)
        p.textSize = 100f
        val bounds = android.graphics.Rect().also { p.getTextBounds(name, 0, name.length, it) }
        // 70% of the space available, centred in the left part.
        val fit = min(140f / bounds.width(), 100f / bounds.height()) * 0.7f
        p.textSize = 100f * fit
        cv.drawText(name, 18f + (140f - bounds.width() * fit) / 2f - bounds.left * fit, 80f - (bounds.top + bounds.bottom) / 2f * fit, p)

        // Label.
        val x0 = 172f
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 10f; p.letterSpacing = 0.16f; p.color = c.accent
        cv.drawText(label, x0, 34f, p)
        p.letterSpacing = 0f

        // Odometer.
        val whole = if (value >= 999.95f) 4 else 3
        val dw = if (whole == 4) 22f else 26f
        val dh = 40f
        val digits = String.format(java.util.Locale.ROOT, "%0${whole + 2}.1f", value).replace(".", "")
        val winW = 8f + (whole + 1) * dw + whole * 3f + 10f + 30f
        p.color = c.window
        cv.drawRoundRect(RectF(x0, 44f, x0 + winW, 44f + dh + 12f), 12f, 12f, p)
        var x = x0 + 8f
        p.textAlign = Paint.Align.CENTER
        digits.forEachIndexed { i, ch ->
            val tenths = i == digits.length - 1
            if (tenths) {
                p.color = c.text; p.alpha = 180
                cv.drawCircle(x + 2f, 44f + 6f + dh - 6f, 2f, p)
                p.alpha = 255
                x += 7f
            }
            val r = RectF(x, 50f, x + dw, 50f + dh)
            p.shader = if (tenths) null else LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(c.drumDark, c.drumLight, c.drumDark), null, Shader.TileMode.CLAMP)
            p.color = c.accent
            cv.drawRoundRect(r, 5f, 5f, p)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(c.shade, 0, 0, c.shade), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            cv.drawRoundRect(r, 5f, 5f, p)
            p.shader = null
            p.color = if (tenths) 0xFFFFFFFF.toInt() else c.text
            p.textSize = 25f
            cv.drawText(ch.toString(), r.centerX(), r.centerY() + 9f, p)
            x += dw + 3f
        }
        p.textAlign = Paint.Align.LEFT
        p.textSize = 10f; p.letterSpacing = 0.1f; p.color = c.muted
        cv.drawText(unit, x + 2f, 44f + dh + 2f, p)
        p.letterSpacing = 0f

        // Chips: trip count, and the parked car as a lit P.
        var cx = x0
        fun chip(width: Float) {
            p.color = c.window
            cv.drawRoundRect(RectF(cx, 112f, cx + width, 146f), 17f, 17f, p)
        }
        p.textSize = 12f
        val count = trips.toString()
        p.textSize = 9f
        val tagW = p.measureText(tripLabel) + 8f
        p.textSize = 12f
        val tripW = 7f + tagW + 7f + p.measureText(count) + 11f
        chip(tripW)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = c.muted
        cv.drawRoundRect(RectF(cx + 7f, 122f, cx + 7f + tagW, 136f), 5f, 5f, p)
        p.style = Paint.Style.FILL
        p.textSize = 9f
        cv.drawText(tripLabel, cx + 11f, 132.5f, p)
        p.textSize = 12f; p.color = c.text
        cv.drawText(count, cx + 7f + tagW + 7f, 133.5f, p)
        cx += tripW + 8f
        if (parked != null) {
            val pw = 7f + 20f + 7f + p.measureText(parked) + 12f
            if (cx + pw <= 352f) {
                chip(pw)
                p.color = c.accent
                cv.drawRoundRect(RectF(cx + 7f, 119f, cx + 27f, 139f), 6f, 6f, p)
                p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER
                cv.drawText("P", cx + 17f, 133.5f, p)
                p.textAlign = Paint.Align.LEFT; p.color = c.text
                cv.drawText(parked, cx + 34f, 133.5f, p)
            }
        }
        }
        return bmp
    }

    /** Pixel size for a widget of [wDp] x [hDp], capped so the picture stays within widget memory limits. */
    fun pixels(context: Context, wDp: Float, hDp: Float): Pair<Int, Int> {
        val d = min(context.resources.displayMetrics.density, 2.5f)
        return (wDp * d).roundToInt() to (hDp * d).roundToInt()
    }
}
