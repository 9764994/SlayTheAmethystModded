package io.stamethyst.ui

import io.stamethyst.R
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LauncherNavigationIconsTest {
    @Test
    fun bannerAndDockUseIdenticalVectorInstancesForAllFourPages() {
        assertSame(LauncherNavigationIcons.Game, LauncherNavigationIcons.forHeaderResource(R.drawable.ic_dock_game))
        assertSame(LauncherNavigationIcons.Mods, LauncherNavigationIcons.forHeaderResource(R.drawable.ic_dock_mods))
        assertSame(LauncherNavigationIcons.Workshop, LauncherNavigationIcons.forHeaderResource(R.drawable.ic_dock_market))
        assertSame(LauncherNavigationIcons.Settings, LauncherNavigationIcons.forHeaderResource(R.drawable.ic_dock_settings))
    }

    @Test
    fun childPagesRetainTheirOwnCategoryIcons() {
        assertNull(LauncherNavigationIcons.forHeaderResource(R.drawable.ic_settings_native_library))
    }
}
