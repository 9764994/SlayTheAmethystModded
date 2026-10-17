package io.stamethyst.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherBannerWeightTest {
    @Test
    fun contentSlidesOnAFullWidthRailAndReversesWithTheFinger() {
        assertEquals(-540f, bannerPageTranslation(0.5f, 0, 1080), 0f)
        assertEquals(540f, bannerPageTranslation(0.5f, 1, 1080), 0f)
        assertEquals(-270f, bannerPageTranslation(0.25f, 0, 1080), 0f)
        assertEquals(810f, bannerPageTranslation(0.25f, 1, 1080), 0f)
        assertEquals(0f, bannerPageTranslation(1f, 1, 1080), 0f)
    }

    @Test
    fun adjacentPagesInterpolateContinuouslyAcrossPagerCurrentPageChange() {
        assertEquals(1f, bannerPageWeight(0f, 0), 0f)
        assertEquals(0f, bannerPageWeight(0f, 1), 0f)
        assertEquals(0.51f, bannerPageWeight(0.49f, 0), 0.0001f)
        assertEquals(0.49f, bannerPageWeight(0.49f, 1), 0.0001f)
        assertEquals(0.49f, bannerPageWeight(0.51f, 0), 0.0001f)
        assertEquals(0.51f, bannerPageWeight(0.51f, 1), 0.0001f)
    }

    @Test
    fun weightsStayNormalizedAndNonAdjacentPagesNeverLeak() {
        for (step in 0..300) {
            val position = step / 100f
            val weights = (0..3).map { bannerPageWeight(position, it) }
            assertEquals(1f, weights.sum(), 0.0001f)
            weights.forEachIndexed { page, weight ->
                if (kotlin.math.abs(position - page) >= 1f) assertEquals(0f, weight, 0f)
            }
        }
    }
}
