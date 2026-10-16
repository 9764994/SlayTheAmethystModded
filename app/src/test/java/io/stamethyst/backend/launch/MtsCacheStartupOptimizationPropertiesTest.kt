package io.stamethyst.backend.launch

import io.stamethyst.config.MtsCacheStartupOptimization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtsCacheStartupOptimizationPropertiesTest {
    @Test
    fun defaultsDisableAllFiveRuntimeSwitches() {
        val args = mutableListOf<String>()
        MtsPatchCacheCoordinator.appendStartupOptimizationProperties(args, emptyMap())

        assertEquals(5, args.size)
        assertEquals(
            setOf(
                "-Damethyst.runtime_compat.class_finder_scan_cache=false",
                "-Damethyst.runtime_compat.lazy_custom_card_images=false",
                "-Damethyst.runtime_compat.lazy_startup_card_descriptions=false",
                "-Damethyst.runtime_compat.lazy_card_library_screen=false",
                "-Damethyst.runtime_compat.fast_cache_splash=false",
            ),
            args.toSet(),
        )
    }

    @Test
    fun eachOptimizationCanBeEnabledIndependently() {
        MtsCacheStartupOptimization.entries.forEach { enabled ->
            val args = mutableListOf<String>()
            MtsPatchCacheCoordinator.appendStartupOptimizationProperties(args, mapOf(enabled to true))

            assertEquals(5, args.size)
            MtsCacheStartupOptimization.entries.forEach { optimization ->
                assertTrue(args.contains("-D${optimization.propertyName}=${optimization == enabled}"))
            }
        }
    }

    @Test
    fun allOptimizationsCanBeDisabledWithoutDisablingThePatchCache() {
        val args = mutableListOf<String>()
        MtsPatchCacheCoordinator.appendStartupOptimizationProperties(
            args,
            MtsCacheStartupOptimization.entries.associateWith { false },
        )

        assertEquals(5, args.size)
        assertTrue(args.all { it.endsWith("=false") })
        assertTrue(args.none { it.contains("amethyst.mts.patch_cache.") })
    }

    @Test
    fun preferencesHaveDistinctStableKeys() {
        assertEquals(5, MtsCacheStartupOptimization.entries.map { it.preferenceKey }.toSet().size)
        assertEquals(
            "mts_cache_optimization_lazy_custom_card_images",
            MtsCacheStartupOptimization.LAZY_CUSTOM_CARD_IMAGES.preferenceKey,
        )
    }
}
