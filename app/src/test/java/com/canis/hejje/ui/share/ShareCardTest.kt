package com.canis.hejje.ui.share

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.canis.hejje.domain.DriveSim
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class ShareCardTest {
    @Test fun roadTrip_rendersTheShareImage() {
        // A loop out of the city and back, then off to another town, like a long night drive.
        val sim = DriveSim()
        sim.heading = 230.0
        sim.drive(300, 14f, turnPerSec = 0.08).drive(900, 27f, turnPerSec = -0.03).drive(400, 16f, turnPerSec = 0.35)
        sim.drive(900, 22f, turnPerSec = 0.02).drive(500, 15f, turnPerSec = -0.12).drive(200, 9f, turnPerSec = 0.9)
        sim.heading = 330.0
        sim.drive(1100, 24f, turnPerSec = 0.02)
        val seg = sim.segment()
        val bitmap = ShareCard.render(
            ApplicationProvider.getApplicationContext(), listOf(seg.data.route),
            date = "Fri · 14 Nov 2026", time = "21:26 – 06:13", distance = 376.4, unit = "KM",
            stats = listOf(
                ShareCard.Stat("Duration", "8 h 47 m"), ShareCard.Stat("Avg speed", "49 km/h"),
                ShareCard.Stat("Top speed", "124 km/h"), ShareCard.Stat("Fluidity", "98/100"),
            ),
        )
        assertEquals(ShareCard.WIDTH, bitmap.width)
        File("build/screens").mkdirs()
        File("build/screens/share-card.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
