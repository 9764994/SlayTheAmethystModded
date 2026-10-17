package io.stamethyst.compatmod.touch;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnemyPanelHoverDelayTest {
    @Test
    public void movementBeforeTwoSecondsDoesNotRevealPanel() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        Object card = new Object();
        assertTrue(delay.update(true, card, 10, 10, 0));
        assertTrue(delay.update(true, card, 20, 10, 1_999_000_000L));
        assertTrue(delay.update(true, card, 20, 10, 3_000_000_000L));
        assertTrue(delay.update(true, card, 20, 10, 60_000_000_000L));
        assertFalse(delay.update(true, card, 21, 10, 60_001_000_000L));
        assertFalse(delay.update(true, card, 21, 10, 61_000_000_000L));
    }

    @Test
    public void releaseAtStationaryPointerStaysHiddenUntilMovement() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        assertTrue(delay.update(true, new Object(), 10, 10, 0));
        assertTrue(delay.update(true, null, 10, 10, 3_000_000_000L));
        assertFalse(delay.update(true, null, 10, 11, 3_001_000_000L));
    }

    @Test
    public void ordinaryHoverIsUnaffectedAndLeavingResetsDelay() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        Object card = new Object();
        assertFalse(delay.update(true, null, 10, 10, 0));
        assertTrue(delay.update(true, card, 10, 10, 1));
        assertFalse(delay.update(false, card, 20, 10, 3_000_000_000L));
        assertTrue(delay.update(true, card, 20, 10, 4_000_000_000L));
        assertTrue(delay.update(true, card, 21, 10, 5_000_000_000L));
        assertFalse(delay.update(true, card, 22, 10, 6_000_000_000L));
        assertTrue(delay.update(true, new Object(), 22, 10, 7_000_000_000L));
    }

    @Test
    public void monstersHaveIndependentWindows() {
        Object card = new Object();
        EnemyPanelHoverDelay first = new EnemyPanelHoverDelay();
        EnemyPanelHoverDelay second = new EnemyPanelHoverDelay();
        assertTrue(first.update(true, card, 0, 0, 0));
        assertFalse(first.update(false, card, 50, 50, 3_000_000_000L));
        assertTrue(second.update(true, card, 50, 50, 3_000_000_000L));
        assertTrue(second.update(true, card, 51, 50, 4_000_000_000L));
    }
}
