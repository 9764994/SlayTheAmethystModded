package io.stamethyst.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

@Composable
fun FloatingGlassHeader(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    onHeightChanged: (Int) -> Unit = {},
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    shadowElevation: Dp = 6.dp,
    contentSpacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalLauncherBannerPage.current != null) {
        RegisterLauncherBanner {
            Column(
                modifier = Modifier.fillMaxWidth().padding(contentPadding)
                    .onSizeChanged { onHeightChanged(it.height) },
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                content = content,
            )
        }
        return
    }
    FrostedGlassChrome(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) },
        hazeState = hazeState,
        shape = shape,
        shadowElevation = shadowElevation,
        contentPadding = contentPadding,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
    }
}

@Composable
fun CollapsibleFloatingGlassHeader(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    onHeightChanged: (Int) -> Unit = {},
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    shadowElevation: Dp = 6.dp,
    expandedContentTopPadding: Dp = 14.dp,
    pinnedContent: @Composable ColumnScope.() -> Unit,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * Always-visible trailing slot, rendered below [expandedContent].
     *
     * Unlike [expandedContent] this survives collapse, so transient status such as load progress
     * stays readable while the user scrolls the list.
     */
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    FloatingGlassHeader(
        modifier = modifier,
        hazeState = hazeState,
        onHeightChanged = onHeightChanged,
        shape = shape,
        contentPadding = contentPadding,
        shadowElevation = shadowElevation,
        contentSpacing = 0.dp,
    ) {
        pinnedContent()
        if (expandedContent != null) {
            BannerReveal(LocalLauncherBannerCollapseFraction.current) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = expandedContentTopPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = expandedContent,
                )
            }
        }
        if (footerContent != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = footerContent,
            )
        }
    }
}
