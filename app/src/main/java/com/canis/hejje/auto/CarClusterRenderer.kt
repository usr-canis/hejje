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
import com.canis.hejje.ui.trips.DarkDash
import com.canis.hejje.ui.trips.LightDash
import com.canis.hejje.ui.trips.compass
import com.canis.hejje.ui.trips.drawDrumDigits
import com.canis.hejje.ui.trips.odometerPositions

/** One frame of the car's screen. [turn] and [odometer] are the animated, shown values. */
internal class CarFrame(
    val turn: Float,
    val heading: String,
    val trace: TraceState,
    val here: RawPoint?,
    val odometer: Float,
    val distanceUnit: String,
    /** Driving time as on a stopwatch ("18:42"), or "–" when not recording. */
    val elapsed: String,
    /** The small headings over the time and the odometer ("DRIVING TIME", "TRIP"). */
    val timeLabel: String,
    val tripLabel: String,
    val dark: Boolean,
)

/**
 * Draws the car screen's own part (beside Android Auto's list of stops) with the phone's drawing
 * code: the compass around the line driven so far, with the heading under it, and beside it the
 * driving time and the trip odometer. No speedometer: Android Auto is for finding places, and the
 * car has its own.
 */
internal class CarClusterRenderer(context: Context) {
    private val fonts = createFontFamilyResolver(context)
    private val scope = CanvasDrawScope()
    private var measurer: TextMeasurer? = null
    private var measurerDensity = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, width: Int, height: Int, visible: Rect, density: Float, f: CarFrame) {
        val dash = if (f.dark) DarkDash else LightDash
        val m = measurer?.takeIf { measurerDensity == density }
            ?: TextMeasurer(fonts, Density(density), LayoutDirection.Ltr, cacheSize = 32).also { measurer = it; measurerDensity = density }
        val area = RectF(visible).apply { inset(PAD_DP * density, PAD_DP * density) }
        // Compass (2 r wide) on the left, a gap, then the column of time and odometer.
        val r = minOf(area.width() / WIDE, area.height() / TALL)
        val left = area.left + (area.width() - WIDE * r) / 2
        val top = area.top + (area.height() - TALL * r) / 2
        val cc = Offset(left + r, top + r)
        scope.draw(Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), Size(width.toFloat(), height.toFloat())) {
            drawRect(Brush.verticalGradient(listOf(dash.panelTop, dash.panelBottom)))
            compass(dash, cc, r, f.turn, f.trace, f.here, m)
        }
        fun label(text: String, x: Float, baseline: Float, align: Paint.Align) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = dash.muted.toArgb()
            paint.textSize = 0.12f * r
            paint.letterSpacing = 0.14f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = align
            canvas.drawText(text, x, baseline, paint)
        }
        // The heading, centred under the compass.
        label(f.heading, cc.x, top + 2.3f * r, Paint.Align.CENTER)
        // The column: driving time, then the trip odometer.
        val x0 = left + (2f + GAP) * r
        label(f.timeLabel, x0, top + 0.42f * r, Paint.Align.LEFT)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = dash.text.toArgb()
        paint.textSize = 0.46f * r
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.fontFeatureSettings = "tnum"
        canvas.drawText(f.elapsed, x0 - 0.02f * r, top + 0.92f * r, paint)
        label(f.tripLabel, x0, top + 1.32f * r, Paint.Align.LEFT)
        odometer(canvas, x0, top + 1.42f * r, ODO * r, f, dash)
    }

    /** The trip odometer with its left edge at [left]. */
    private fun odometer(canvas: Canvas, left: Float, top: Float, height: Float, f: CarFrame, dash: com.canis.hejje.ui.trips.DashColors) {
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
        val window = RectF(left, top, left + width, top + height)
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
        /** In compass radii: the gap before the column, the column's width, the odometer's height. */
        const val GAP = 0.6f
        const val COLUMN = 1.9f
        const val ODO = 0.42f
        const val WIDE = 2f + GAP + COLUMN
        const val TALL = 2.4f
    }
}
