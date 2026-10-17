package io.stamethyst.compatmod.touch;

/** Per-monster hover state, independent of game/graphics initialization. */
public final class EnemyPanelHoverDelay {
    static final long DELAY_NANOS = 2_000_000_000L;
    private boolean tracking;
    private boolean suppressed;
    private Object card;
    private long startedAt;
    private int lastX;
    private int lastY;

    public boolean update(boolean hovered, Object targetingCard, int x, int y, long now) {
        if (!hovered) {
            tracking = false;
            suppressed = false;
            card = null;
            return false;
        }
        if (targetingCard != null && (!tracking || card != targetingCard)) {
            tracking = true;
            suppressed = true;
            card = targetingCard;
            startedAt = now;
            lastX = x;
            lastY = y;
            return true;
        }
        // Releasing the card must not reveal a panel under a stationary pointer.
        if (tracking && suppressed && now - startedAt >= DELAY_NANOS
            && (x != lastX || y != lastY)) {
            suppressed = false;
        }
        lastX = x;
        lastY = y;
        return suppressed;
    }
}
