package com.canis.hejje.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** One small family of line icons (24 dp grid, 1.75 stroke, round ends) so every icon matches. */
object HejjeIcons {
    private fun icon(name: String, vararg filled: PathBuilder.() -> Unit = emptyArray(), stroke: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = stroke,
            )
            filled.forEach { path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero, pathBuilder = it) }
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, false, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, false, -2 * r, 0f)
        close()
    }

    val Trips = icon("Trips") {
        circle(6f, 18f, 2f); circle(18f, 6f, 2f)
        moveTo(8f, 18f); horizontalLineTo(14.5f); arcToRelative(3f, 3f, 0f, false, false, 0f, -6f)
        horizontalLineTo(9.5f); arcToRelative(3f, 3f, 0f, false, true, 0f, -6f); horizontalLineTo(16f)
    }
    val Insights = icon("Insights") {
        moveTo(5f, 20f); verticalLineTo(13f); moveTo(12f, 20f); verticalLineTo(5f); moveTo(19f, 20f); verticalLineTo(10f)
    }
    val Settings = icon("Settings") {
        moveTo(4f, 7f); horizontalLineTo(12f); moveTo(16f, 7f); horizontalLineTo(20f); circle(14f, 7f, 2f)
        moveTo(4f, 17f); horizontalLineTo(8f); moveTo(12f, 17f); horizontalLineTo(20f); circle(10f, 17f, 2f)
    }
    val Back = icon("Back") { moveTo(19f, 12f); horizontalLineTo(5f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
    val ChevronRight = icon("ChevronRight") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val More = icon("More", { circle(12f, 5f, 1.4f) }, { circle(12f, 12f, 1.4f) }, { circle(12f, 19f, 1.4f) }) { }
    val Close = icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = icon("Check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Plus = icon("Plus") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) }
    val Search = icon("Search") { circle(11f, 11f, 6f); moveTo(15.5f, 15.5f); lineTo(20f, 20f) }
    val Share = icon("Share") {
        moveTo(12f, 15f); verticalLineTo(4f); moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f)
        moveTo(5f, 13f); verticalLineTo(18f); arcToRelative(2f, 2f, 0f, false, false, 2f, 2f); horizontalLineTo(17f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); verticalLineTo(13f)
    }
    val Car = icon("Car") {
        moveTo(4f, 16f); verticalLineTo(12.5f); lineTo(6f, 7.5f); arcToRelative(2f, 2f, 0f, false, true, 1.9f, -1.5f)
        horizontalLineTo(16.1f); arcToRelative(2f, 2f, 0f, false, true, 1.9f, 1.5f); lineTo(20f, 12.5f); verticalLineTo(16f); close()
        moveTo(4.5f, 12f); horizontalLineTo(19.5f); moveTo(6.5f, 16f); verticalLineTo(18f); moveTo(17.5f, 16f); verticalLineTo(18f)
    }
    val Navigate = icon("Navigate") { moveTo(20f, 4f); lineTo(4f, 11f); lineTo(11f, 13f); lineTo(13f, 20f); close() }
    val Pin = icon("Pin") {
        moveTo(12f, 21f); curveTo(12f, 21f, 5.5f, 15f, 5.5f, 10f); arcToRelative(6.5f, 6.5f, 0f, true, true, 13f, 0f)
        curveTo(18.5f, 15f, 12f, 21f, 12f, 21f); close(); circle(12f, 10f, 2.2f)
    }
    val Tag = icon("Tag") {
        moveTo(4f, 5f); horizontalLineTo(11f); lineTo(20f, 14f); lineTo(14f, 20f); lineTo(5f, 11f); verticalLineTo(5f); close(); circle(8.5f, 8.5f, 0.6f)
    }
    val Trash = icon("Trash") {
        moveTo(5f, 7f); horizontalLineTo(19f); moveTo(9f, 7f); verticalLineTo(4.5f); horizontalLineTo(15f); verticalLineTo(7f)
        moveTo(7f, 7f); lineTo(7.8f, 19f); arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, 1.4f); horizontalLineTo(14.7f)
        arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, -1.4f); lineTo(17f, 7f)
    }
    val Bluetooth = icon("Bluetooth") { moveTo(7f, 8f); lineTo(17f, 16f); lineTo(12f, 20f); verticalLineTo(4f); lineTo(17f, 8f); lineTo(7f, 16f) }
    val Warning = icon("Warning", { circle(12f, 16.5f, 0.5f) }) {
        moveTo(12f, 4f); lineTo(21f, 19.5f); horizontalLineTo(3f); close(); moveTo(12f, 10f); verticalLineTo(14f)
    }
    val Ok = icon("Ok") { circle(12f, 12f, 8.5f); moveTo(8.2f, 12.3f); lineTo(11f, 15f); lineTo(16f, 9.3f) }
    val Info = icon("Info", { circle(12f, 8f, 0.5f) }) { circle(12f, 12f, 8.5f); moveTo(12f, 11.5f); verticalLineTo(16f) }
    val Stop = icon("Stop") { moveTo(7f, 7f); horizontalLineTo(17f); verticalLineTo(17f); horizontalLineTo(7f); close() }
    val Record = icon("Record", { circle(12f, 12f, 3.2f) }) { circle(12f, 12f, 8.5f) }
    val Layers = icon("Layers") {
        moveTo(12f, 4f); lineTo(20f, 8.5f); lineTo(12f, 13f); lineTo(4f, 8.5f); close(); moveTo(4f, 13f); lineTo(12f, 17.5f); lineTo(20f, 13f)
    }
    val Download = icon("Download") {
        moveTo(12f, 4f); verticalLineTo(15f); moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f); moveTo(5f, 19.5f); horizontalLineTo(19f)
    }
}
