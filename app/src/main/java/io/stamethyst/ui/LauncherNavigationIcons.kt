package io.stamethyst.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.stamethyst.R
import io.stamethyst.ui.main.RendererIcons

/** The banner and dock must use the same vectors, not separate filled/outlined lookalikes. */
internal object LauncherNavigationIcons {
    val Game = RendererIcons.Gamepad2
    val Mods = RendererIcons.Package
    val Workshop = RendererIcons.Store
    val Settings = RendererIcons.Settings2

    fun forHeaderResource(@DrawableRes resource: Int): ImageVector? = when (resource) {
        R.drawable.ic_dock_game -> Game
        R.drawable.ic_dock_mods -> Mods
        R.drawable.ic_dock_market -> Workshop
        R.drawable.ic_dock_settings -> Settings
        else -> null
    }
}
