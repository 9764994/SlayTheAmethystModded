package io.stamethyst.compatmod.touch;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnemyPanelHoverDelayTest {
    private static final long TWO_SECONDS = 2_000_000_000L;

    @Test
    public void draggingAnyCardHidesPanelForTwoSeconds() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        assertTrue("pointer enters enemy during drag", delay.update(true, true, 10, 10, 0));
        assertTrue("movement during the window stays hidden",
            delay.update(true, true, 20, 10, 1_999_000_000L));
        assertTrue("stationary pointer after the window stays hidden",
            delay.update(true, true, 20, 10, 3_000_000_000L));
        assertFalse("movement after the window reveals the panel",
            delay.update(true, true, 21, 10, 3_100_000_000L));
        assertFalse(delay.update(true, true, 21, 10, 4_000_000_000L));
    }

    @Test
    public void nonTargetingCardDragAlsoStartsTheWindow() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        // The player is dragging a skill; the hovered monster must not show its buff panel.
        assertTrue(delay.update(true, true, 0, 0, 0));
        assertTrue(delay.update(true, true, 100, 100, 1_000_000_000L));
        assertFalse(delay.update(true, true, 101, 100, TWO_SECONDS + 1));
    }

    @Test
    public void dragStartingOverHoveredEnemyStartsWindow() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        assertFalse("plain hover is unaffected", delay.update(true, false, 0, 0, 0));
        assertFalse(delay.update(true, false, 5, 0, 100));
        assertTrue("starting a drag over the enemy hides the panel",
            delay.update(true, true, 5, 0, 200));
        assertTrue(delay.update(true, true, 6, 0, TWO_SECONDS));
        assertFalse(delay.update(true, true, 7, 0, TWO_SECONDS + 201));
    }

    @Test
    public void releaseWithStationaryPointerStaysHiddenUntilMovement() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        assertTrue(delay.update(true, true, 10, 10, 0));
        assertTrue("release at the same point keeps the panel hidden",
            delay.update(true, false, 10, 10, 3_000_000_000L));
        assertTrue(delay.update(true, false, 10, 10, 9_000_000_000L));
        assertFalse(delay.update(true, false, 10, 11, 9_100_000_000L));
    }

    @Test
    public void leavingAndReenteringResetsTheWindow() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        assertTrue(delay.update(true, true, 10, 10, 0));
        assertFalse(delay.update(false, true, 50, 50, 3_000_000_000L));
        assertTrue("re-entering during the same drag starts a fresh window",
            delay.update(true, true, 50, 50, 3_100_000_000L));
        assertTrue(delay.update(true, true, 51, 50, 4_000_000_000L));
        assertFalse(delay.update(true, true, 52, 50, 5_100_000_000L));
    }

    @Test
    public void secondDragRestartsTheWindow() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        assertTrue(delay.update(true, true, 0, 0, 0));
        assertFalse(delay.update(true, true, 1, 0, TWO_SECONDS + 1));
        assertFalse("release keeps the current state", delay.update(true, false, 1, 0, 9_000_000_000L));
        assertTrue("a new drag re-hides the panel",
            delay.update(true, true, 1, 0, 10_000_000_000L));
        assertTrue(delay.update(true, true, 2, 0, 11_000_000_000L));
        assertFalse(delay.update(true, true, 3, 0, 12_000_000_001L));
    }

    @Test
    public void monstersHaveIndependentWindows() {
        EnemyPanelHoverDelay first = new EnemyPanelHoverDelay();
        EnemyPanelHoverDelay second = new EnemyPanelHoverDelay();
        assertTrue(first.update(true, true, 0, 0, 0));
        assertFalse(first.update(false, true, 50, 50, 3_000_000_000L));
        assertTrue(second.update(true, true, 50, 50, 3_000_000_000L));
        assertTrue(second.update(true, true, 51, 50, 4_000_000_000L));
        assertFalse(second.update(true, true, 52, 50, 5_100_000_000L));
    }
}
