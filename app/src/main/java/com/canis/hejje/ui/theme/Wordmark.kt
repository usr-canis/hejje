package com.canis.hejje.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.core.content.ContextCompat
import com.canis.hejje.R

/** Mallige Bold, used only for the name ಹೆಜ್ಜೆ. GPL v2, see third_party/mallige. */
val WordmarkFont = FontFamily(Font(R.font.mallige_bold, FontWeight.Bold))

/** The footstep mark ([R.drawable.ic_footsteps]): width for each unit of height. */
const val FOOTSTEPS_ASPECT = 38f / 36f

/** The mark is this many times the font size tall, standing on the baseline like the letters. */
private const val FEET_EM = 0.6f

/** Space between the name and the mark, in font sizes. */
private const val FEET_GAP_EM = 0.06f

private const val FEET_ID = "feet"

/**
 * The name ಹೆಜ್ಜೆ in Mallige with the footstep mark after it, on the same baseline and scaled with the
 * text, so it lines up at any size (including [autoSize]). The mark is decorative.
 */
@Composable
fun Wordmark(style: TextStyle, footColor: Color, modifier: Modifier = Modifier, autoSize: TextAutoSize? = null) {
    val name = stringResource(R.string.brand_name)
    val text = remember(name) { buildAnnotatedString { append(name); appendInlineContent(FEET_ID, " ") } }
    val feet = mapOf(
        FEET_ID to InlineTextContent(
            Placeholder((FEET_GAP_EM + FEET_EM * FOOTSTEPS_ASPECT).em, FEET_EM.em, PlaceholderVerticalAlign.AboveBaseline),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    painterResource(R.drawable.ic_footsteps), contentDescription = null, tint = footColor,
                    modifier = Modifier.fillMaxHeight().aspectRatio(FOOTSTEPS_ASPECT),
                )
            }
        },
    )
    BasicText(
        text, modifier,
        style = style.copy(fontFamily = WordmarkFont, fontWeight = FontWeight.Bold, color = style.color.takeOrElse { LocalContentColor.current }),
        maxLines = 1, inlineContent = feet, autoSize = autoSize,
    )
}

/** Width of [name] plus its footstep mark when drawn with [paint] (already set to the wordmark's font and size). */
fun wordmarkWidth(paint: Paint, name: String): Float = paint.measureText(name) + paint.textSize * (FEET_GAP_EM + FEET_EM * FOOTSTEPS_ASPECT)

/** Draws [name] with [paint] at ([x], [baseline]) and the footstep mark after it, standing on the same baseline. */
fun Canvas.drawWordmark(context: Context, name: String, x: Float, baseline: Float, paint: Paint, footColor: Int) {
    drawText(name, x, baseline, paint)
    val marks = ContextCompat.getDrawable(context, R.drawable.ic_footsteps)?.mutate() ?: return
    val h = paint.textSize * FEET_EM
    val left = x + paint.measureText(name) + paint.textSize * FEET_GAP_EM
    marks.setTint(footColor)
    marks.setBounds(left.toInt(), (baseline - h).toInt(), (left + h * FOOTSTEPS_ASPECT).toInt(), baseline.toInt())
    marks.draw(this)
}
