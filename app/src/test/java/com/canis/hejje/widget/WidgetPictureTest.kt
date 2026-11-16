package com.canis.hejje.widget

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.canis.hejje.HejjeApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The home-screen widget's picture, light and dark, at the sizes launchers give it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPictureTest {
    @Test fun widget_drawsInBothThemes_atCommonSizes() {
        val app: HejjeApp = ApplicationProvider.getApplicationContext()
        val dir = File("build/screens").apply { mkdirs() }
        for (night in listOf(false, true)) for ((w, h) in listOf(250 to 110, 320 to 140)) {
            val (pw, ph) = DashboardBitmap.pixels(app, w.toFloat(), h.toFloat())
            val bmp = DashboardBitmap.draw(app, pw, ph, night, 15.5f, "KM", "THIS WEEK", "TRIP", 2, "2 h ago")
            assertEquals(pw, bmp.width)
            File(dir, "widget-${if (night) "dark" else "light"}-$w.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
