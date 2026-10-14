package io.stamethyst.compatmod.touch;

import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.localization.LocalizedStrings;
import com.megacrit.cardcrawl.localization.TutorialStrings;
import com.megacrit.cardcrawl.localization.UIStrings;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.ui.buttons.EndTurnButton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

public class TouchscreenButtonStateCleanupPatchesTest {
    private EndTurnButton button;
    private Hitbox hitbox;
    private Map<EndTurnButton, Boolean> waiting;
    private boolean originalMouseDown;
    private int originalX;
    private int originalY;
    private LocalizedStrings originalLanguagePack;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        originalMouseDown = InputHelper.isMouseDown;
        originalX = InputHelper.mX;
        originalY = InputHelper.mY;
        // The constructor loads GL textures; only the input fields are needed.
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        originalLanguagePack = CardCrawlGame.languagePack;
        CardCrawlGame.languagePack = (LocalizedStrings) unsafe.allocateInstance(TestLanguagePack.class);
        button = (EndTurnButton) unsafe.allocateInstance(EndTurnButton.class);
        hitbox = new Hitbox(0, 0, 100, 100);
        setField("hb", hitbox);
        Field waitingField = TouchscreenButtonStateCleanupPatches.class
            .getDeclaredField("END_TURN_WAITING_FOR_RELEASE");
        waitingField.setAccessible(true);
        waiting = (Map<EndTurnButton, Boolean>) waitingField.get(null);
        waiting.put(button, Boolean.TRUE);
        InputHelper.isMouseDown = true;
        InputHelper.mX = 50;
        InputHelper.mY = 50;
    }

    @After
    public void tearDown() {
        if (waiting != null) waiting.remove(button);
        InputHelper.isMouseDown = originalMouseDown;
        InputHelper.mX = originalX;
        InputHelper.mY = originalY;
        CardCrawlGame.languagePack = originalLanguagePack;
    }

    @Test
    public void heldPressDoesNotReenterHoverEveryFrame() throws Exception {
        hitbox.hovered = true;
        for (int frame = 0; frame < 500; frame++) {
            hitbox.clicked = true;
            hitbox.clickStarted = true;
            setField("holdProgress", 0.3f);
            TouchscreenButtonStateCleanupPatches.EndTurnButtonUpdatePatch.before(button);
            assertFalse(hitbox.clicked);
            assertFalse(hitbox.clickStarted);
            assertEquals(0f, holdProgress(), 0f);
            assertTrue("Preserve hover so vanilla does not re-enter it", hitbox.hovered);
            assertFalse(hitbox.justHovered);
        }
        assertTrue(waiting.containsKey(button));
    }

    @Test
    public void releaseConsumesOldPressAndAllowsSubsequentInput() throws Exception {
        hitbox.hovered = true;
        hitbox.clicked = true;
        hitbox.clickStarted = true;
        setField("holdProgress", 0.3f);
        InputHelper.isMouseDown = false;
        TouchscreenButtonStateCleanupPatches.EndTurnButtonUpdatePatch.before(button);
        assertFalse(waiting.containsKey(button));
        assertFalse(hitbox.clicked);
        assertFalse(hitbox.clickStarted);
        assertEquals(0f, holdProgress(), 0f);
        assertTrue(hitbox.hovered);
        assertFalse(hitbox.justHovered);

        hitbox.clickStarted = true;
        setField("holdProgress", 0.2f);
        TouchscreenButtonStateCleanupPatches.EndTurnButtonUpdatePatch.before(button);
        assertTrue(hitbox.clickStarted);
        assertEquals(0.2f, holdProgress(), 0f);
    }

    @Test
    public void lifecycleTransitionStillClearsHover() {
        hitbox.hovered = true;
        hitbox.justHovered = true;
        TouchscreenButtonStateCleanupPatches.EndTurnButtonEnablePatch.after(button);
        assertFalse(hitbox.hovered);
        assertFalse(hitbox.justHovered);
        // An outside hitbox stays outside while waiting; the patch does not force hover.
        TouchscreenButtonStateCleanupPatches.EndTurnButtonUpdatePatch.before(button);
        assertFalse(hitbox.hovered);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = EndTurnButton.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(button, value);
    }

    private float holdProgress() throws Exception {
        Field field = EndTurnButton.class.getDeclaredField("holdProgress");
        field.setAccessible(true);
        return field.getFloat(button);
    }

    public static class TestLanguagePack extends LocalizedStrings {
        @Override
        public TutorialStrings getTutorialString(String key) {
            TutorialStrings strings = new TutorialStrings();
            strings.TEXT = new String[]{"tip"};
            strings.LABEL = new String[]{"label"};
            return strings;
        }

        @Override
        public UIStrings getUIString(String key) {
            UIStrings strings = new UIStrings();
            strings.TEXT = new String[]{"End turn", "Enemy turn"};
            return strings;
        }
    }
}
