package com.canis.hejje.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.canis.hejje.R
import com.canis.hejje.ui.theme.drawWordmark
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Google Play listing pictures: the 512 px icon and the 1024 x 500 feature graphic, into build/store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreGraphicsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File("build/store").apply { mkdirs() }
    private val dark = ContextCompat.getColor(context, R.color.icon_background)
    private val accent = ContextCompat.getColor(context, R.color.accent)

    @Test fun icon_is512Square() {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(dark)
        // The launcher shows the middle 72 of the 108 dp layer; Play applies its own mask to that.
        val fg = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
        val bleed = (512 * 18 / 72)
        fg.setBounds(-bleed, -bleed, 512 + bleed, 512 + bleed)
        fg.draw(c)
        save(bmp, "icon-512.png")
        assertEquals(512, bmp.width)
    }

    @Test fun featureGraphic_is1024x500() {
        val w = 1024f
        val h = 500f
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(dark)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.78f, h * 0.55f, w * 0.5f, (accent and 0xFFFFFF) or 0x38000000, accent and 0xFFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, glow)

        // A drive drawn the way trip maps draw it: orange into pink, with a start ring and an end dot.
        val route = Path().apply {
            moveTo(600f, 410f); cubicTo(660f, 300f, 720f, 380f, 780f, 270f); cubicTo(830f, 180f, 900f, 260f, 950f, 110f)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(600f, 410f, 950f, 110f, accent, 0xFFE64980.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawPath(route, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawCircle(950f, 110f, 14f, dot)
        c.drawCircle(600f, 410f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = accent })

        val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.mallige_bold); textSize = 170f; color = 0xFFF5F2EF.toInt()
        }
        val name = context.getString(R.string.brand_name)
        // The joined letter hangs well below the baseline, so place the word by its drawn bounds.
        val bounds = Rect().also { word.getTextBounds(name, 0, name.length, it) }
        val baseline = 100f - bounds.top
        c.drawWordmark(context, name, 70f, baseline, word, accent)

        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 36f; color = 0xFFB9B4AF.toInt()
        }
        c.drawText("Every drive, recorded on its own.", 76f, baseline + bounds.bottom + 75f, tag)
        save(bmp, "feature-1024x500.png")
        assertEquals(500, bmp.height)
    }

    private fun save(bmp: Bitmap, name: String) =
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
