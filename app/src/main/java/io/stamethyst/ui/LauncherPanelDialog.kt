package io.stamethyst.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.LauncherDialog as Dialog
import androidx.compose.ui.window.DialogProperties

/** Modal panels share the launcher's content motion instead of sheet slide animations. */
@Composable
internal fun LauncherPanelDialog(
    onDismissRequest: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    maxWidth: Dp = 640.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.9f).coerceAtMost(760.dp)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(0.92f).heightIn(max = maxHeight),
            shape = shape,
            color = containerColor,
            tonalElevation = 6.dp,
        ) {
            Column(content = content)
        }
    }
}
