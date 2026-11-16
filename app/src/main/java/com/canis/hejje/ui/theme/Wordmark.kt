package com.canis.hejje.ui.theme

import android.content.Context
import android.graphics.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.canis.hejje.R

/** Mallige Bold, used only for the name ಹೆಜ್ಜೆ. GPL v2, see third_party/mallige. */
val WordmarkFont = FontFamily(Font(R.font.mallige_bold, FontWeight.Bold))

/** The footstep marks drawn beside the wordmark ([R.drawable.ic_footsteps]), [height] tall, in [tint]. Decorative. */
@Composable
fun Footsteps(height: Dp, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.ic_footsteps), contentDescription = null, tint = tint,
        modifier = modifier.height(height).aspectRatio(FOOTSTEPS_ASPECT),
    )
}

/** Width of the footstep marks for each unit of height. */
const val FOOTSTEPS_ASPECT = 1.5f

/** Draws the footstep marks into a picture (share card, widget), bottom-left at ([left], [bottom]). */
fun Canvas.drawFootsteps(context: Context, left: Float, bottom: Float, height: Float, color: Int) {
    val marks = ContextCompat.getDrawable(context, R.drawable.ic_footsteps)?.mutate() ?: return
    marks.setTint(color)
    marks.setBounds(left.toInt(), (bottom - height).toInt(), (left + height * FOOTSTEPS_ASPECT).toInt(), bottom.toInt())
    marks.draw(this)
}
