package io.stamethyst.compatmod.touch;

/**
 * Player-wide tooltip suppression, independent of monster hover and game initialization.
 * Drag observations and pointer sampling are separate so input handlers cannot lose a release
 * frame, and rendering never consumes a pointer movement a second time.
 */
public final class EnemyPanelHoverDelay {
    static final long DELAY_NANOS = 2_000_000_000L;

    private boolean suppressed;
    private boolean pointerKnown;
    private long lastDragAt;
    private int lastX;
    private int lastY;

    /** Observe both before and after player input, including the frame that releases a card. */
    public void recordDrag(boolean dragActive, long now) {
        if (dragActive) {
            suppressed = true;
            lastDragAt = now;
        }
    }

    /** Sample once after room/input updates, even if no monster is currently hovered. */
    public void updatePointer(int x, int y, long now) {
        boolean moved = pointerKnown && (x != lastX || y != lastY);
        if (suppressed && now - lastDragAt >= DELAY_NANOS && moved) {
            suppressed = false;
        }
        pointerKnown = true;
        lastX = x;
        lastY = y;
    }

    /** Read-only: every monster rendered this frame sees the same suppression decision. */
    public boolean isSuppressed() {
        return suppressed;
    }
}
