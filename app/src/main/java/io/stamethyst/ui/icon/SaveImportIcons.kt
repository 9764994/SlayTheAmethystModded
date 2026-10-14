package io.stamethyst.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object SaveImportIcons {
    private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val Add = outline("SaveImport.Add") {
        moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f)
    }
    val Sync = outline("SaveImport.Replace") {
        moveTo(4f, 8f); horizontalLineTo(19f); lineTo(15f, 4f)
        moveTo(20f, 16f); horizontalLineTo(5f); lineTo(9f, 20f)
    }
    val DragHandle = outline("SaveImport.Same") {
        moveTo(5f, 9f); horizontalLineTo(19f); moveTo(5f, 15f); horizontalLineTo(19f)
    }
    val CheckBox = outline("SaveImport.Select") {
        moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }
    val EmojiEvents = outline("SaveImport.Trophy") {
        moveTo(8f, 3f); horizontalLineTo(16f); verticalLineTo(8f)
        curveTo(16f, 13.3f, 8f, 13.3f, 8f, 8f); close()
        moveTo(8f, 5f); horizontalLineTo(4f); verticalLineTo(7f); curveTo(4f, 10f, 6f, 11f, 8f, 11f)
        moveTo(16f, 5f); horizontalLineTo(20f); verticalLineTo(7f); curveTo(20f, 10f, 18f, 11f, 16f, 11f)
        moveTo(12f, 12f); verticalLineTo(19f); moveTo(8f, 21f); horizontalLineTo(16f)
        moveTo(10f, 19f); horizontalLineTo(14f)
    }
    val HourglassEmpty = outline("SaveImport.Hourglass") {
        moveTo(6f, 3f); horizontalLineTo(18f); moveTo(6f, 21f); horizontalLineTo(18f)
        moveTo(7f, 3f); verticalLineTo(7f); curveTo(7f, 9f, 9f, 10f, 12f, 12f)
        curveTo(9f, 14f, 7f, 15f, 7f, 17f); verticalLineTo(21f)
        moveTo(17f, 3f); verticalLineTo(7f); curveTo(17f, 9f, 15f, 10f, 12f, 12f)
        curveTo(15f, 14f, 17f, 15f, 17f, 17f); verticalLineTo(21f)
    }
    val History = outline("SaveImport.History") {
        moveTo(4f, 4f); verticalLineTo(9f); horizontalLineTo(9f)
        moveTo(4f, 9f); curveTo(6f, 2f, 18f, 2f, 20f, 10f)
        curveTo(23f, 20f, 8f, 25f, 4f, 15f)
        moveTo(12f, 7f); verticalLineTo(12f); lineTo(15f, 14f)
    }
}
