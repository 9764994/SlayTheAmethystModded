package io.stamethyst.compatmod.touch;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnemyPanelHoverDelayTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    public void ordinaryHoverWithoutDragIsUnaffected() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.updatePointer(0, 0, 0);
        delay.recordDrag(false, SECOND);
        delay.updatePointer(10, 10, SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void anyCardDragSuppressesEvenWithoutEnemyHover() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(10, 10, 0);
        // A self/non-target card is released outside all enemy hitboxes.
        delay.recordDrag(true, SECOND);
        delay.recordDrag(false, SECOND);
        delay.updatePointer(20, 20, SECOND);
        // Pointer enters an enemy after the card has already been released.
        delay.updatePointer(500, 500, SECOND + SECOND / 10);
        assertTrue(delay.isSuppressed());
    }

    @Test
    public void releaseFrameFirstHoverCannotLoseDragHistory() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(10, 10, 0);
        // MonsterGroup now refreshes the hitbox to hovered=true. The input prefix
        // captures dragging=true before the input handler clears it on release.
        delay.recordDrag(true, SECOND / 10);
        delay.recordDrag(false, SECOND / 10);
        delay.updatePointer(500, 500, SECOND / 10);
        assertTrue(delay.isSuppressed());
        delay.updatePointer(500, 500, 10 * SECOND);
        assertTrue("stationary pointer must stay hidden past vanilla's delay", delay.isSuppressed());
        delay.updatePointer(501, 500, 11 * SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void continuingDragRefreshesTwoSecondWindow() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(0, 0, 0);
        delay.recordDrag(true, 10 * SECOND);
        delay.updatePointer(100, 100, 10 * SECOND);
        assertTrue(delay.isSuppressed());
        delay.recordDrag(false, 10 * SECOND);
        delay.updatePointer(101, 100, 12 * SECOND - 1);
        assertTrue(delay.isSuppressed());
        delay.updatePointer(102, 100, 12 * SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void movementBeforeDeadlineDoesNotRevealStationaryPanelLater() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(10, 10, 0);
        delay.updatePointer(20, 10, 2 * SECOND - 1);
        assertTrue(delay.isSuppressed());
        delay.updatePointer(20, 10, 60 * SECOND);
        assertTrue(delay.isSuppressed());
        delay.updatePointer(21, 10, 61 * SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void crossingEnemiesDoesNotRestartWindow() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(0, 0, 0);
        delay.updatePointer(100, 100, SECOND);
        delay.updatePointer(500, 500, 2 * SECOND);
        assertFalse(delay.isSuppressed());
        delay.updatePointer(100, 100, 3 * SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void movementOutsideEnemyAlsoReleasesExpiredSuppression() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(0, 0, 0);
        delay.updatePointer(10, 0, 3 * SECOND);
        assertFalse(delay.isSuppressed());
        // Entering an enemy later does not re-hide the panel.
        delay.updatePointer(500, 500, 4 * SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void nextDragRestartsWindowEvenAtSamePointerPosition() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(0, 0, 0);
        delay.updatePointer(1, 0, 3 * SECOND);
        assertFalse(delay.isSuppressed());
        delay.recordDrag(true, 4 * SECOND);
        delay.updatePointer(1, 0, 4 * SECOND);
        assertTrue(delay.isSuppressed());
        delay.updatePointer(2, 0, 5 * SECOND);
        assertTrue(delay.isSuppressed());
        delay.updatePointer(3, 0, 6 * SECOND);
        assertFalse(delay.isSuppressed());
    }

    @Test
    public void repeatedRenderReadsCannotConsumeMovementOrRevealPanel() {
        EnemyPanelHoverDelay delay = new EnemyPanelHoverDelay();
        delay.recordDrag(true, 0);
        delay.updatePointer(0, 0, 0);
        for (int i = 0; i < 10; i++) assertTrue(delay.isSuppressed());
        delay.updatePointer(1, 0, 3 * SECOND);
        for (int i = 0; i < 10; i++) assertFalse(delay.isSuppressed());
    }

    @Test
    public void newPlayerHasNoPreviousPlayersDragState() {
        EnemyPanelHoverDelay oldPlayer = new EnemyPanelHoverDelay();
        oldPlayer.recordDrag(true, 0);
        assertTrue(oldPlayer.isSuppressed());
        assertFalse(new EnemyPanelHoverDelay().isSuppressed());
    }
}
