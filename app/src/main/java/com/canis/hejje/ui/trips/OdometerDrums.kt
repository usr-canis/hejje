package com.canis.hejje.ui.trips

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withClip
import kotlin.math.floor

/**
 * Draws one odometer drum's digits inside [r] at [position] (4.3 = the 4 has rolled 30% of the way
 * to 5), as on a real drum: the digit slides up and the next one comes in from below. [paint] must
 * be centre-aligned; [baselineOffset] places the digit's baseline relative to the drum's centre.
 */
internal fun Canvas.drawDrumDigits(r: RectF, position: Float, paint: Paint, baselineOffset: Float) {
    val digit = floor(position).toInt().mod(10)
    val shift = (position - floor(position)) * r.height()
    withClip(r) {
        drawText(digit.toString(), r.centerX(), r.centerY() + baselineOffset - shift, paint)
        drawText(((digit + 1) % 10).toString(), r.centerX(), r.centerY() + baselineOffset - shift + r.height(), paint)
    }
}
