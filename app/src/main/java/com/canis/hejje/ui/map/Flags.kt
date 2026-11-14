package com.canis.hejje.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip

/**
 * Start and finish flags for the maps: start is a solid flag in the accent colour, the end a
 * chequered finish flag. The flag pole stands on the exact point (bottom left of the image,
 * [BASE_DP] in from the left and from the bottom).
 */
object Flags {
    const val START = "flag-start"
    const val END = "flag-end"
    private const val WIDTH_DP = 28f
    private const val HEIGHT_DP = 34f
    const val BASE_DP = 4f

    /** [pxPerDp]: the size; the screen density by default. */
    fun bitmap(context: Context, finish: Boolean, accent: Int, casing: Int, ink: Int, pxPerDp: Float = context.resources.displayMetrics.density): Bitmap {
        val d = pxPerDp
        val bmp = createBitmap((WIDTH_DP * d).toInt(), (HEIGHT_DP * d).toInt())
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val baseX = BASE_DP * d
        val baseY = (HEIGHT_DP - BASE_DP) * d
        val top = 2f * d
        // Pole, with an outline so it shows on light and dark maps.
        p.strokeCap = Paint.Cap.ROUND
        p.color = casing; p.strokeWidth = 4.5f * d
        c.drawLine(baseX, baseY, baseX, top, p)
        p.color = ink; p.strokeWidth = 2f * d
        c.drawLine(baseX, baseY, baseX, top, p)
        // Cloth: a slightly waving flag.
        val left = baseX + 1f * d
        val right = (WIDTH_DP - 1.5f) * d
        val bottom = top + 15f * d
        val cloth = Path().apply {
            moveTo(left, top)
            cubicTo(left + (right - left) * 0.35f, top - 2f * d, left + (right - left) * 0.65f, top + 2.5f * d, right, top + 0.5f * d)
            lineTo(right, bottom + 0.5f * d)
            cubicTo(left + (right - left) * 0.65f, bottom + 2.5f * d, left + (right - left) * 0.35f, bottom - 2f * d, left, bottom)
            close()
        }
        p.style = Paint.Style.FILL
        if (finish) {
            p.color = 0xFFFFFFFF.toInt()
            c.drawPath(cloth, p)
            c.withClip(cloth) {
                p.color = 0xFF1C1B1A.toInt()
                val cols = 4
                val rows = 3
                val cw = (right - left) / cols
                val ch = (bottom - top + 3f * d) / rows
                for (r in 0 until rows) for (col in 0 until cols) {
                    if ((r + col) % 2 == 0) drawRect(RectF(left + col * cw, top - 2f * d + r * ch, left + (col + 1) * cw, top - 2f * d + (r + 1) * ch), p)
                }
            }
        } else {
            p.color = accent
            c.drawPath(cloth, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = casing
        c.drawPath(cloth, p)
        // The exact spot.
        p.style = Paint.Style.FILL
        p.color = casing
        c.drawCircle(baseX, baseY, 3.5f * d, p)
        p.color = if (finish) 0xFF1C1B1A.toInt() else accent
        c.drawCircle(baseX, baseY, 2.2f * d, p)
        return bmp
    }
}
