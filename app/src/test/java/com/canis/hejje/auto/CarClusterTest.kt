package com.canis.hejje.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.car.app.versioning.CarAppApiLevels
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.domain.DriveSim
import com.canis.hejje.recording.LiveTrace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarClusterTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking { app.container.settings.reset() }
    }

    private fun rows(screen: ClusterScreen): List<String> {
        val template = screen.onGetTemplate() as MapWithContentTemplate
        val list = template.contentTemplate as ListTemplate
        return list.singleList!!.items.map { row -> (row as Row).let { "${it.title} | ${it.texts.joinToString { t -> t.toString() }}" } }
    }

    @Test fun card_listsYourStops_nextOnTheRoadFirst() {
        // As if connected to an Android Auto that can show app-drawn screens (the library sets this on connect).
        val car = TestCarContext.createCarContext(app).apply {
            CarContext::class.java.getDeclaredField("mCarAppApiLevel").apply { isAccessible = true }.setInt(this, CarAppApiLevels.LEVEL_7)
        }
        val screen = ClusterScreen(car)
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
        // Data loads in the background: wait for the card to follow along.
        fun eventually(check: (List<String>) -> Boolean) {
            repeat(100) {
                idle()
                if (check(rows(screen))) return
                Thread.sleep(20)
            }
            throw AssertionError(rows(screen).toString())
        }
        // No saved stops yet: a short note.
        eventually { it.first().startsWith("No saved stops yet") }
        // Two saved stops (one set to "don't remind", which stays out) and a home that is no stop.
        runBlocking {
            app.container.food.save("Coffee", "Brew Corner", 13.06, 77.71, System.currentTimeMillis())
            val fuel = app.container.food.save("Fuel", "Shell", 13.2, 77.8, System.currentTimeMillis())
            app.container.food.save("Food", "Skipped Dhaba", 13.1, 77.75, System.currentTimeMillis())
                .let { app.container.food.setRemind(it, remind = false) }
            assertTrue(fuel > 0)
        }
        val controller = app.container.controller
        controller.manualStart()
        idle()
        DriveSim(t = System.currentTimeMillis(), lat = 13.05, lon = 77.7).drive(30, 15f).points.forEach(controller::onLocation)
        eventually { rows -> rows.size == 2 && rows[0].startsWith("Brew Corner | Coffee ·") && "away" in rows[0] && rows[1].startsWith("Shell | Fuel ·") }
        // The next stop on this road comes first.
        app.container.food.showNext(com.canis.hejje.food.NextStop("Kamat Upachar", "food", 4_200.0, 0.0, 13.3, 77.9))
        eventually { rows -> rows.size == 3 && rows[0].startsWith("Kamat Upachar | Next stop ·") }
        controller.manualStop()
        app.container.food.showNext(null)
    }

    @Test fun drawnCluster_fitsBesideTheCard() {
        val trace = LiveTrace()
        val sim = DriveSim(lat = 13.05, lon = 77.7).apply { heading = 10.0 }.drive(60, 15f).apply { heading = 40.0 }.drive(60, 24f, turnPerSec = 0.4)
        sim.points.forEach(trace::add)
        for (dark in listOf(true, false)) {
            val bitmap = Bitmap.createBitmap(1280, 600, Bitmap.Config.ARGB_8888)
            val frame = CarFrame(
                turn = trace.state.value.heading!!, heading = "NE · 40°", trace = trace.state.value, here = sim.points.last(),
                odometer = 12.4f, distanceUnit = "KM", elapsed = "18:42", timeLabel = "DRIVING TIME", tripLabel = "TRIP", dark = dark,
            )
            // Android Auto's card covers the left of the screen; the visible area is the rest.
            CarClusterRenderer(app).draw(Canvas(bitmap), 1280, 600, Rect(410, 70, 1280, 600), 1.5f, frame)
            val dir = File("build/screens").apply { mkdirs() }
            File(dir, if (dark) "auto-cluster-dark.png" else "auto-cluster-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Nothing drawn under the card except the background.
            assertEquals(bitmap.getPixel(100, 300), bitmap.getPixel(300, 300))
        }
    }
}
