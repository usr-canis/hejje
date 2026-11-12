package com.canis.hejje.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.canis.hejje.R
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Route
import com.canis.hejje.ui.theme.Palette
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * A clean image of the route with a few key numbers, in the app's design. No map tiles are
 * involved, and the caller passes a route that already has privacy zones removed.
 */
object ShareCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    class Stat(val label: String, val value: String)

    fun render(
        context: Context, pieces: List<Route>, title: String, subtitle: String, stats: List<Stat>, palette: Palette,
        background: Int, surface: Int, onSurface: Int, onSurfaceVariant: Int,
    ): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val pad = 72f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Brand: icon and wordmark, drawn with the system font so the Kannada name shapes correctly.
        ContextCompat.getDrawable(context, R.drawable.ic_mark)?.let {
            it.setBounds(pad.toInt() - 28, 44, pad.toInt() + 92, 164)
            it.draw(canvas)
        }
        paint.color = onSurface
        paint.textSize = 54f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(context.getString(R.string.brand_name), pad + 104, 124f, paint)

        // Route panel.
        val panel = RectF(pad, 200f, WIDTH - pad, 850f)
        paint.color = surface
        canvas.drawRoundRect(panel, 56f, 56f, paint)
        drawRoute(canvas, pieces, RectF(panel.left + 90, panel.top + 90, panel.right - 90, panel.bottom - 90), palette, surface)

        // Title and date.
        paint.color = onSurface
        paint.textSize = 50f
        canvas.drawText(ellipsize(title, paint, WIDTH - 2 * pad), pad, 948f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.color = onSurfaceVariant
        paint.textSize = 36f
        canvas.drawText(ellipsize(subtitle, paint, WIDTH - 2 * pad), pad, 1006f, paint)

        // Stats in one row.
        val shown = stats.take(4)
        val cell = (WIDTH - 2 * pad) / shown.size.coerceAtLeast(1)
        shown.forEachIndexed { i, stat ->
            val x = pad + i * cell
            paint.typeface = Typeface.DEFAULT
            paint.color = onSurfaceVariant
            paint.textSize = 30f
            canvas.drawText(ellipsize(stat.label, paint, cell - 16), x, 1128f, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.color = onSurface
            paint.textSize = if (shown.size > 3) 46f else 54f
            paint.fontFeatureSettings = "tnum"
            canvas.drawText(ellipsize(stat.value, paint, cell - 16), x, 1192f, paint)
        }
        // Thin accent line as a footer.
        paint.color = palette.accent.toArgb()
        canvas.drawRoundRect(RectF(pad, HEIGHT - 76f, pad + 120f, HEIGHT - 68f), 4f, 4f, paint)
        return bitmap
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun drawRoute(canvas: Canvas, pieces: List<Route>, area: RectF, palette: Palette, surface: Int) {
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
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.accent.toArgb()
        }
        for (r in pieces) {
            if (r.size < 2) continue
            val path = Path()
            path.moveTo(x(r.lon[0]), y(r.lat[0]))
            for (i in 1 until r.size) path.lineTo(x(r.lon[i]), y(r.lat[i]))
            canvas.drawPath(path, line)
        }
        val first = pieces.first { it.size > 0 }
        val last = pieces.last { it.size > 0 }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = palette.accent.toArgb()
        canvas.drawCircle(x(first.lon[0]), y(first.lat[0]), 18f, dot)
        dot.color = surface
        canvas.drawCircle(x(first.lon[0]), y(first.lat[0]), 9f, dot)
        canvas.drawCircle(x(last.lon.last()), y(last.lat.last()), 24f, dot)
        dot.color = palette.accent.toArgb()
        canvas.drawCircle(x(last.lon.last()), y(last.lat.last()), 16f, dot)
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
