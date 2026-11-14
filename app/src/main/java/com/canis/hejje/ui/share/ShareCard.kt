package com.canis.hejje.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.canis.hejje.R
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Route
import com.canis.hejje.ui.map.Flags
import com.canis.hejje.ui.theme.DarkPalette
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The image shared for a trip, styled like the dashboard: the route as the hero (coloured by
 * speed, with start and finish flags), the distance on odometer drums, and a row of key numbers.
 * Always dark. No map tiles and no place names; the caller passes a route that already has
 * private places removed.
 */
object ShareCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    class Stat(val label: String, val value: String)

    private const val TEXT = 0xFFECE9E6.toInt()
    private const val MUTED = 0xFF8E8984.toInt()
    private const val LINE = 0xFF2B3035.toInt()
    private const val WINDOW = 0xFF0B0C0D.toInt()
    private const val DRUM_LIGHT = 0xFF3A3F45.toInt()
    private const val DRUM_DARK = 0xFF1A1D20.toInt()
    private const val PAD = 64f

    /**
     * [distance] in the display unit (e.g. 376.4) and its [unit] ("KM" or "MI"); [date] and [time]
     * are shown top right; up to four [stats] in the bottom row.
     */
    fun render(context: Context, pieces: List<Route>, date: String, time: String, distance: Double, unit: String, stats: List<Stat>): Bitmap {
        val palette = DarkPalette
        val accent = palette.accent.toArgb()
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background: deep charcoal, a soft light behind the route, and a faint dot grid like map paper.
        p.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF22272B.toInt(), 0xFF0C0E10.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), p)
        val hero = RectF(PAD, 190f, WIDTH - PAD, 800f)
        p.shader = RadialGradient(hero.centerX(), hero.centerY(), 560f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), 900f, p)
        p.shader = null
        p.color = 0x10FFFFFF
        var gy = hero.top - 20
        while (gy < hero.bottom + 20) {
            var gx = hero.left
            while (gx <= hero.right) { c.drawCircle(gx, gy, 1.6f, p); gx += 36f }
            gy += 36f
        }

        // Top: the wordmark, and the date and time.
        val mallige = runCatching { ResourcesCompat.getFont(context, R.font.mallige_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        p.typeface = mallige
        p.color = TEXT
        p.textSize = 78f
        c.drawText(context.getString(R.string.brand_name), PAD, 128f, p)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textAlign = Paint.Align.RIGHT
        p.letterSpacing = 0.14f
        p.textSize = 28f
        p.color = accent
        c.drawText(date.uppercase(Locale.getDefault()), WIDTH - PAD, 92f, p)
        p.letterSpacing = 0.02f
        p.textSize = 32f
        p.color = MUTED
        c.drawText(time, WIDTH - PAD, 136f, p)
        p.textAlign = Paint.Align.LEFT
        p.letterSpacing = 0f

        drawRoute(context, c, pieces, RectF(hero.left + 70, hero.top + 70, hero.right - 70, hero.bottom - 60))
        drawSpeedKey(c, right = hero.right, baseline = hero.bottom + 18f)

        // The distance on odometer drums, like the dashboard.
        drawOdometer(c, distance, unit, centerX = WIDTH / 2f, top = 860f, accent = accent)

        // Bottom row of numbers.
        val shown = stats.take(4)
        val rowTop = 1102f
        val cell = (WIDTH - 2 * PAD) / shown.size.coerceAtLeast(1)
        p.color = LINE
        c.drawRect(PAD, rowTop - 36f, WIDTH - PAD, rowTop - 34f, p)
        shown.forEachIndexed { i, stat ->
            val x = PAD + i * cell
            if (i > 0) { p.color = LINE; c.drawRect(x, rowTop, x + 2f, rowTop + 110f, p) }
            val left = x + if (i > 0) 28f else 0f
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            p.letterSpacing = 0.12f
            p.textSize = 23f
            p.color = MUTED
            c.drawText(ellipsize(stat.label.uppercase(Locale.getDefault()), p, cell - 36f), left, rowTop + 30f, p)
            drawValue(c, stat.value, left, rowTop + 96f, big = if (shown.size > 3) 54f else 60f)
        }

        // Footer: a short accent bar.
        p.color = accent
        c.drawRoundRect(RectF(WIDTH / 2f - 48f, HEIGHT - 62f, WIDTH / 2f + 48f, HEIGHT - 56f), 3f, 3f, p)
        return bitmap
    }

    private fun drawOdometer(c: Canvas, value: Double, unit: String, centerX: Float, top: Float, accent: Int) {
        val v = (value.coerceIn(0.0, 99999.9) * 10).roundToInt() / 10.0
        val whole = floor(v).toLong().toString()
        val tenth = ((v * 10).roundToInt() % 10).toString()
        val drumW = 92f
        val drumH = 136f
        val gap = 10f
        val dotW = 22f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = 30f
        p.letterSpacing = 0.1f
        val unitW = p.measureText(unit) + 18f
        val inner = whole.length * (drumW + gap) + dotW + drumW
        val windowW = inner + 2 * 16f + unitW
        val left = centerX - windowW / 2
        val window = RectF(left, top, left + windowW, top + drumH + 32f)
        p.color = WINDOW
        c.drawRoundRect(window, 30f, 30f, p)
        // Recessed look: shadow inside the top edge.
        p.shader = LinearGradient(0f, window.top, 0f, window.top + 18f, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRoundRect(window, 30f, 30f, p)
        p.shader = null

        var x = window.left + 16f
        val y = window.top + 16f
        fun drum(digit: String, tenths: Boolean) {
            val r = RectF(x, y, x + drumW, y + drumH)
            p.shader = if (tenths) null else LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(DRUM_DARK, DRUM_LIGHT, DRUM_DARK), null, Shader.TileMode.CLAMP)
            p.color = accent
            c.drawRoundRect(r, 12f, 12f, p)
            // Curved drum: darker towards the top and bottom edges.
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xA6000000.toInt(), 0, 0x14FFFFFF, 0, 0xA6000000.toInt()), floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 12f, 12f, p)
            p.shader = null
            p.color = if (tenths) 0xFFFFFFFF.toInt() else TEXT
            p.textAlign = Paint.Align.CENTER
            p.letterSpacing = 0f
            p.textSize = 92f
            p.fontFeatureSettings = "tnum"
            c.drawText(digit, r.centerX(), r.centerY() + 33f, p)
            p.textAlign = Paint.Align.LEFT
            p.fontFeatureSettings = null
            x += drumW + gap
        }
        whole.forEach { drum(it.toString(), tenths = false) }
        p.color = 0xB3ECE9E6.toInt()
        c.drawCircle(x + dotW / 2 - gap / 2, y + drumH - 16f, 6f, p)
        x += dotW
        drum(tenth, tenths = true)
        p.color = MUTED
        p.textSize = 30f
        p.letterSpacing = 0.1f
        c.drawText(unit, x + 2f, y + drumH - 6f, p)
    }

    /** A number with its units: digits large and bright, units ("km/h", "h", "m") small and muted. */
    private fun drawValue(c: Canvas, value: String, left: Float, baseline: Float, big: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.fontFeatureSettings = "tnum"
        var x = left
        var outOf = false // after a "/" (as in 98/100) everything is small
        Regex("[0-9][0-9.,:]*|[^0-9]+").findAll(value).forEach { m ->
            val part = m.value
            if ('/' in part) outOf = true
            val digits = part[0].isDigit() && !outOf
            p.typeface = Typeface.create(Typeface.DEFAULT, if (digits) Typeface.BOLD else Typeface.NORMAL)
            p.textSize = if (digits) big else big * 0.46f
            p.color = if (digits) TEXT else MUTED
            val text = if (digits) part else part.replace(" ", "")
            if (!digits && x > left) x += big * 0.06f
            c.drawText(text, x, baseline, p)
            x += p.measureText(text) + if (!digits) big * 0.12f else 0f
        }
    }

    /** Slow to fast: what the route colours mean. */
    private fun drawSpeedKey(c: Canvas, right: Float, baseline: Float) {
        val ramp = DarkPalette.speedRamp.map { it.toArgb() }.toIntArray()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 20f
        p.letterSpacing = 0.12f
        p.color = MUTED
        val fast = "FAST"
        val slow = "SLOW"
        val barW = 150f
        val fastW = p.measureText(fast)
        val barRight = right - fastW - 14f
        val barLeft = barRight - barW
        c.drawText(fast, right - fastW, baseline, p)
        c.drawText(slow, barLeft - 14f - p.measureText(slow), baseline, p)
        p.shader = LinearGradient(barLeft, 0f, barRight, 0f, ramp, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(barLeft, baseline - 13f, barRight, baseline - 5f), 4f, 4f, p)
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    /** The route coloured by speed, on a soft glow, with the start and finish flags. */
    private fun drawRoute(context: Context, canvas: Canvas, pieces: List<Route>, area: RectF) {
        val palette = DarkPalette
        val lats = pieces.flatMap { it.lat.asList() }
        val lons = pieces.flatMap { it.lon.asList() }
        if (lats.isEmpty()) return
        val lat0 = (lats.min() + lats.max()) / 2
        val lon0 = (lons.min() + lons.max()) / 2
        val spanX = max(1.0, Geo.x(lons.max(), lon0, lat0) - Geo.x(lons.min(), lon0, lat0))
        val spanY = max(1.0, Geo.y(lats.max(), lat0) - Geo.y(lats.min(), lat0))
        val scale = min(area.width() / spanX, area.height() / spanY).toFloat()
        fun x(lon: Double) = area.centerX() + (Geo.x(lon, lon0, lat0) * scale).toFloat()
        fun y(lat: Double) = area.centerY() - (Geo.y(lat, lat0) * scale).toFloat()
        val paths = pieces.filter { it.size >= 2 }.map { r ->
            Path().apply {
                moveTo(x(r.lon[0]), y(r.lat[0]))
                for (i in 1 until r.size) lineTo(x(r.lon[i]), y(r.lat[i]))
            }
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Glow, then a dark casing, then the line coloured by speed.
        stroke.color = (palette.accent.toArgb() and 0x00FFFFFF) or 0x55000000
        stroke.strokeWidth = 34f
        stroke.maskFilter = BlurMaskFilter(26f, BlurMaskFilter.Blur.NORMAL)
        paths.forEach { canvas.drawPath(it, stroke) }
        stroke.maskFilter = null
        stroke.color = 0xFF0E1012.toInt()
        stroke.strokeWidth = 20f
        paths.forEach { canvas.drawPath(it, stroke) }
        stroke.strokeWidth = 11f
        for (r in pieces) {
            for (i in 1 until r.size) {
                stroke.color = palette.speedColor((r.v[i - 1] + r.v[i]) / 2).toArgb()
                canvas.drawLine(x(r.lon[i - 1]), y(r.lat[i - 1]), x(r.lon[i]), y(r.lat[i]), stroke)
            }
        }
        val first = pieces.first { it.size > 0 }
        val last = pieces.last { it.size > 0 }
        val ink = 0xFFECE9E6.toInt()
        val size = 2.6f
        val start = Flags.bitmap(context, false, palette.accent.toArgb(), palette.routeCasing.toArgb(), ink, size)
        val finish = Flags.bitmap(context, true, palette.accent.toArgb(), palette.routeCasing.toArgb(), ink, size)
        val base = Flags.BASE_DP * size
        canvas.drawBitmap(start, x(first.lon[0]) - base, y(first.lat[0]) - (start.height - base), null)
        canvas.drawBitmap(finish, x(last.lon.last()) - base, y(last.lat.last()) - (finish.height - base), null)
    }

    /** Saves the image in the app's private cache and opens the Android share sheet. */
    fun share(context: Context, bitmap: Bitmap, chooserTitle: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "hejje-trip.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
