package com.canis.hejje.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.canis.hejje.HejjeApp
import com.canis.hejje.replay.ReplayJobs
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.replay.ReplayScreen
import com.canis.hejje.ui.theme.HejjeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** The replay screen while the video is being made and once it is ready (saved under build/screens). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class ReplayScreenTest {
    @get:Rule val rule = createComposeRule()
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()

    // A winding loop, like a day out and back.
    private val outline = FloatArray(400) { i ->
        val k = i / 2 / 199.0
        if (i % 2 == 0) (.5 + .38 * sin(k * 5.4) * cos(k * 1.3)).toFloat() else (.5 + .4 * cos(k * 3.1) * (1 - k * .3)).toFloat()
    }

    private fun shoot(name: String, job: ReplayJobs.Job, dark: Boolean, expect: String) {
        app.container.replays.show(job)
        rule.setContent {
            HejjeTheme(if (dark) 2 else 1) {
                CompositionLocalProvider(LocalContainer provides app.container) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.background)) { ReplayScreen(job.tripId, onBack = {}) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithText(expect, substring = true).assertExists()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun making_showsTheRouteDrawingAndTimeLeft() {
        val now = System.currentTimeMillis()
        shoot(
            "replay-making",
            ReplayJobs.Job(1, 7, ReplayJobs.Phase.VIDEO, progress = .48f, outline = outline, title = "Thu, 13 Nov · 440 km", json = "{}", startedAt = now - 90_000, videoStartedAt = now - 60_000),
            dark = true, expect = "Filming",
        )
    }

    @Test fun ready_offersWatchAndShare() {
        shoot("replay-ready", ReplayJobs.Job(2, 7, ReplayJobs.Phase.DONE, progress = 1f, outline = outline, title = "Thu, 13 Nov · 440 km"), dark = false, expect = "Watch")
    }
}
