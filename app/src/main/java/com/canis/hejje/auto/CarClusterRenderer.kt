package com.canis.hejje.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.recording.TraceState
import com.canis.hejje.ui.theme.DarkPalette
import com.canis.hejje.ui.theme.LightPalette
import com.canis.hejje.ui.trips.DarkDash
import com.canis.hejje.ui.trips.LightDash
import com.canis.hejje.ui.trips.compass
import com.canis.hejje.ui.trips.dialMax
import com.canis.hejje.ui.trips.drawDrumDigits
import com.canis.hejje.ui.trips.odometerPositions
import com.canis.hejje.ui.trips.speedStops
import com.canis.hejje.ui.trips.speedometer

/** One frame of the car's cluster. [needle], [turn] and [odometer] are the animated, shown values. */
internal class CarFrame(
    val needle: Float,
    val digits: String,
    val unit: String,
    val turn: Float,
    val heading: String,
    val trace: TraceState,
    val here: RawPoint?,
    val odometer: Float,
    val distanceUnit: String,
    val miles: Boolean,
    val dark: Boolean,
    /** Unsure of the speed (a stop not told from a crawl, GPS gone quiet): digits in grey. */
    val uncertain: Boolean = false,
)

/**
 * Draws the live cluster on the car screen with the same drawing code as the phone's card: the
 * speedometer and the compass side by side in the part of the screen Android Auto leaves free,
 * the trip odometer under the speedometer and the heading under the compass.
 */
internal class CarClusterRenderer(context: Context) {
    private val fonts = createFontFamilyResolver(context)
    private val scope = CanvasDrawScope()
    private var measurer: TextMeasurer? = null
    private var measurerDensity = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, width: Int, height: Int, visible: Rect, density: Float, f: CarFrame) {
        val dash = if (f.dark) DarkDash else LightDash
        val palette = if (f.dark) DarkPalette else LightPalette
        val m = measurer?.takeIf { measurerDensity == density }
            ?: TextMeasurer(fonts, Density(density), LayoutDirection.Ltr, cacheSize = 32).also { measurer = it; measurerDensity = density }
        val area = RectF(visible).apply { inset(PAD_DP * density, PAD_DP * density) }
        // The phone's proportions: speedometer (2.07 r), a gap, compass (1.52 r); dials, then the odometer row.
        val r = minOf(area.width() / WIDE, area.height() / TALL)
        val left = area.left + (area.width() - WIDE * r) / 2
        val top = area.top + (area.height() - TALL * r) / 2
        val c = Offset(left + 1.035f * r, top + 1.035f * r)
        val rc = 0.76f * r
        val cc = Offset(left + WIDE * r - rc, c.y)
        val u = r / 100f
        scope.draw(Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), Size(width.toFloat(), height.toFloat())) {
            drawRect(Brush.verticalGradient(listOf(dash.panelTop, dash.panelBottom)))
            val brush = Brush.sweepGradient(*speedStops(palette.speedRamp, f.miles), center = c)
            speedometer(dash, c, r, u, 7 * u, f.needle, dialMax(f.miles), brush, m, f.digits, f.unit, f.uncertain)
            compass(dash, cc, rc, f.turn, f.trace, f.here, m)
        }
        // The odometer row: drums centred under the speedometer, the heading under the compass.
        val rowTop = top + (DIAL + ROW_GAP) * r
        val rowMid = rowTop + ODO * r / 2
        odometer(canvas, c.x, rowTop, ODO * r, f, dash)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = dash.muted.toArgb()
        paint.textSize = 0.13f * r
        paint.letterSpacing = 0.14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(f.heading, cc.x, rowMid - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun odometer(canvas: Canvas, centerX: Float, top: Float, height: Float, f: CarFrame, dash: com.canis.hejje.ui.trips.DashColors) {
        val whole = if (f.odometer >= 999f) 4 else 3
        val positions = odometerPositions(f.odometer, whole)
        val inner = height * 0.18f
        val drumH = height - inner * 2
        val drumW = drumH * 0.66f
        val gap = drumH * 0.08f
        val dotW = drumH * 0.22f
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = drumH * 0.3f
        paint.letterSpacing = 0.1f
        val unitW = paint.measureText(f.distanceUnit) + gap * 2
        val width = inner * 2 + whole * (drumW + gap) + dotW + drumW + unitW
        val window = RectF(centerX - width / 2, top, centerX + width / 2, top + height)
        val corner = height * 0.22f
        paint.color = dash.window.toArgb()
        canvas.drawRoundRect(window, corner, corner, paint)
        var x = window.left + inner
        val y = window.top + inner
        fun drum(position: Float, tenths: Boolean) {
            val d = RectF(x, y, x + drumW, y + drumH)
            val radius = drumW * 0.18f
            paint.shader = if (tenths) null else LinearGradient(
                d.left, 0f, d.right, 0f, intArrayOf(dash.drumDark.toArgb(), dash.drumLight.toArgb(), dash.drumDark.toArgb()), null, Shader.TileMode.CLAMP,
            )
            paint.color = dash.accent.toArgb()
            canvas.drawRoundRect(d, radius, radius, paint)
            paint.shader = LinearGradient(
                0f, d.top, 0f, d.bottom, intArrayOf(dash.drumShade.toArgb(), 0, dash.drumShine.toArgb(), 0, dash.drumShade.toArgb()),
                floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(d, radius, radius, paint)
            paint.shader = null
            paint.color = if (tenths) android.graphics.Color.WHITE else dash.text.toArgb()
            paint.textAlign = Paint.Align.CENTER
            paint.letterSpacing = 0f
            paint.textSize = drumH * 0.62f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.fontFeatureSettings = "tnum"
            canvas.drawDrumDigits(d, position, paint, paint.textSize * 0.36f)
            x += drumW + gap
        }
        for (i in whole downTo 1) drum(positions[i], tenths = false)
        paint.color = dash.text.copy(alpha = 0.7f).toArgb()
        canvas.drawCircle(x + dotW / 2 - gap / 2, y + drumH - drumH * 0.12f, drumH * 0.05f, paint)
        x += dotW
        drum(positions[0], tenths = true)
        paint.color = dash.muted.toArgb()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = drumH * 0.3f
        paint.letterSpacing = 0.1f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(f.distanceUnit, x + gap, y + drumH - drumH * 0.08f, paint)
    }

    private companion object {
        const val PAD_DP = 16f
        const val DIAL = 1.76f
        const val ROW_GAP = 0.08f
        const val ODO = 0.42f
        const val WIDE = 2.07f + 0.55f + 1.52f
        const val TALL = DIAL + ROW_GAP + ODO
    }
}
