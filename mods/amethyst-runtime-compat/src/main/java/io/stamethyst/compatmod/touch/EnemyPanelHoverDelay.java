package io.stamethyst.compatmod.touch;

/**
 * Per-monster hover suppression window used while the player drags a card.
 *
 * <p>The window starts the moment a monster becomes hovered during a drag, or when a drag starts
 * over an already hovered monster, for any card regardless of its target type. It lasts at least
 * {@link #DELAY_NANOS}: moving the pointer during that period does not reveal the panel, and after
 * the period the pointer must move again. A pointer that stays still keeps the panel hidden even
 * after the card is released.
 */
public final class EnemyPanelHoverDelay {
    static final long DELAY_NANOS = 2_000_000_000L;

    private boolean wasHovered;
    private boolean dragWasActive;
    private boolean suppressed;
    private long startedAt;
    private int lastX;
    private int lastY;

    /**
     * @return {@code true} when the monster tooltip panel must stay hidden this frame.
     */
    public boolean update(boolean hovered, boolean dragActive, int x, int y, long now) {
        if (!hovered) {
            wasHovered = false;
            dragWasActive = dragActive;
            suppressed = false;
            return false;
        }
        boolean newHover = !wasHovered;
        boolean newDrag = dragActive && !dragWasActive;
        wasHovered = true;
        dragWasActive = dragActive;
        if (dragActive && (newHover || newDrag)) {
            suppressed = true;
            startedAt = now;
            lastX = x;
            lastY = y;
            return true;
        }
        if (suppressed && now - startedAt >= DELAY_NANOS && (x != lastX || y != lastY)) {
            suppressed = false;
        }
        lastX = x;
        lastY = y;
        return suppressed;
    }
}
