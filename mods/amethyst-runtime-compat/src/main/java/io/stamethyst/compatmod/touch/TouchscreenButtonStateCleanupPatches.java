package io.stamethyst.compatmod.touch;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.mainMenu.MenuCancelButton;
import com.megacrit.cardcrawl.ui.buttons.CancelButton;
import com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.ConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.EndTurnButton;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.PeekButton;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.ui.buttons.SkipCardButton;
import com.megacrit.cardcrawl.ui.buttons.UnlockConfirmButton;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

public final class TouchscreenButtonStateCleanupPatches {
    private static final Field PROCEED_BUTTON_HB_FIELD = resolveProceedButtonHitboxField();
    private static final Field END_TURN_BUTTON_HB_FIELD = resolveEndTurnButtonHitboxField();
    private static final Field END_TURN_BUTTON_HOLD_PROGRESS_FIELD = resolveEndTurnButtonHoldProgressField();
    private static final Map<EndTurnButton, Boolean> END_TURN_WAITING_FOR_RELEASE =
        new WeakHashMap<EndTurnButton, Boolean>();

    private TouchscreenButtonStateCleanupPatches() {
    }

    @SpirePatch2(
        clz = ConfirmButton.class,
        method = "hide"
    )
    public static class ConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(ConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = ConfirmButton.class,
        method = "hideInstantly"
    )
    public static class ConfirmButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(ConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = GridSelectConfirmButton.class,
        method = "hide"
    )
    public static class GridSelectConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(GridSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = GridSelectConfirmButton.class,
        method = "hideInstantly"
    )
    public static class GridSelectConfirmButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(GridSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CardSelectConfirmButton.class,
        method = "hide"
    )
    public static class CardSelectConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(CardSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CardSelectConfirmButton.class,
        method = "hideInstantly"
    )
    public static class CardSelectConfirmButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(CardSelectConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CancelButton.class,
        method = "hide"
    )
    public static class CancelButtonHidePatch {
        @SpirePostfixPatch
        public static void after(CancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = CancelButton.class,
        method = "hideInstantly"
    )
    public static class CancelButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(CancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = MenuCancelButton.class,
        method = "hide"
    )
    public static class MenuCancelButtonHidePatch {
        @SpirePostfixPatch
        public static void after(MenuCancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = MenuCancelButton.class,
        method = "hideInstantly"
    )
    public static class MenuCancelButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(MenuCancelButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = ProceedButton.class,
        method = "hide"
    )
    public static class ProceedButtonHidePatch {
        @SpirePostfixPatch
        public static void after(ProceedButton __instance) {
            resetHitbox(getProceedButtonHitbox(__instance));
        }
    }

    @SpirePatch2(
        clz = ProceedButton.class,
        method = "hideInstantly"
    )
    public static class ProceedButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(ProceedButton __instance) {
            resetHitbox(getProceedButtonHitbox(__instance));
        }
    }

    @SpirePatch2(
        clz = EndTurnButton.class,
        method = "disable",
        paramtypez = {boolean.class}
    )
    public static class EndTurnButtonDisableWithEndTurnPatch {
        @SpirePostfixPatch
        public static void after(EndTurnButton __instance) {
            markEndTurnButtonWaitingForReleaseIfPressed(__instance);
            resetEndTurnButton(__instance);
        }
    }

    @SpirePatch2(
        clz = EndTurnButton.class,
        method = "disable",
        paramtypez = {}
    )
    public static class EndTurnButtonDisablePatch {
        @SpirePostfixPatch
        public static void after(EndTurnButton __instance) {
            markEndTurnButtonWaitingForReleaseIfPressed(__instance);
            resetEndTurnButton(__instance);
        }
    }

    @SpirePatch2(
        clz = EndTurnButton.class,
        method = "hide"
    )
    public static class EndTurnButtonHidePatch {
        @SpirePostfixPatch
        public static void after(EndTurnButton __instance) {
            markEndTurnButtonWaitingForReleaseIfPressed(__instance);
            resetEndTurnButton(__instance);
        }
    }

    @SpirePatch2(
        clz = EndTurnButton.class,
        method = "enable"
    )
    public static class EndTurnButtonEnablePatch {
        @SpirePostfixPatch
        public static void after(EndTurnButton __instance) {
            resetEndTurnButton(__instance);
            markEndTurnButtonWaitingForReleaseIfPressed(__instance);
        }
    }

    @SpirePatch2(
        clz = EndTurnButton.class,
        method = "update"
    )
    public static class EndTurnButtonUpdatePatch {
        @SpirePrefixPatch
        public static void before(EndTurnButton __instance) {
            if (!CompatRuntimeState.isTouchscreenStateCleanupEnabled()) {
                return;
            }
            if (!isEndTurnButtonWaitingForRelease(__instance)) {
                return;
            }
            // Keep hover continuity: clearing hovered here makes vanilla report a
            // new hover every frame and continuously replay UI_HOVER next turn.
            resetHitbox(getEndTurnButtonHitbox(__instance));
            resetEndTurnButtonHoldProgress(__instance);
            if (InputHelper.isMouseDown) {
                return;
            }
            clearEndTurnButtonWaitingForRelease(__instance);
        }
    }

    @SpirePatch2(
        clz = PeekButton.class,
        method = "hide"
    )
    public static class PeekButtonHidePatch {
        @SpirePostfixPatch
        public static void after(PeekButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = PeekButton.class,
        method = "hideInstantly"
    )
    public static class PeekButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(PeekButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = SkipCardButton.class,
        method = "hide"
    )
    public static class SkipCardButtonHidePatch {
        @SpirePostfixPatch
        public static void after(SkipCardButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = SkipCardButton.class,
        method = "hideInstantly"
    )
    public static class SkipCardButtonHideInstantlyPatch {
        @SpirePostfixPatch
        public static void after(SkipCardButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = SkipCardButton.class,
        method = "show",
        paramtypez = {}
    )
    public static class SkipCardButtonShowPatch {
        @SpirePostfixPatch
        public static void after(SkipCardButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = SkipCardButton.class,
        method = "show",
        paramtypez = {boolean.class}
    )
    public static class SkipCardButtonShowWithBowlPatch {
        @SpirePostfixPatch
        public static void after(SkipCardButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    @SpirePatch2(
        clz = SkipCardButton.class,
        method = "update"
    )
    public static class SkipCardButtonUpdatePatch {
        @SpirePrefixPatch
        public static void before(SkipCardButton __instance) {
            if (__instance.screenDisabled) {
                resetHitbox(__instance.hb);
            }
        }
    }

    @SpirePatch2(
        clz = UnlockConfirmButton.class,
        method = "hide"
    )
    public static class UnlockConfirmButtonHidePatch {
        @SpirePostfixPatch
        public static void after(UnlockConfirmButton __instance) {
            resetHitbox(__instance.hb);
        }
    }

    private static void resetHitbox(Hitbox hitbox) {
        if (!CompatRuntimeState.isTouchscreenStateCleanupEnabled() || hitbox == null) {
            return;
        }
        hitbox.clicked = false;
        hitbox.clickStarted = false;
    }

    private static Hitbox getProceedButtonHitbox(ProceedButton button) {
        if (button == null || PROCEED_BUTTON_HB_FIELD == null) {
            return null;
        }
        try {
            return (Hitbox) PROCEED_BUTTON_HB_FIELD.get(button);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void resetEndTurnButton(EndTurnButton button) {
        if (!CompatRuntimeState.isTouchscreenStateCleanupEnabled() || button == null) {
            return;
        }
        resetEndTurnButtonHitbox(getEndTurnButtonHitbox(button));
        resetEndTurnButtonHoldProgress(button);
    }

    private static void markEndTurnButtonWaitingForReleaseIfPressed(EndTurnButton button) {
        if (!CompatRuntimeState.isTouchscreenStateCleanupEnabled() || button == null) {
            return;
        }
        Hitbox hitbox = getEndTurnButtonHitbox(button);
        if (InputHelper.isMouseDown || hasClickState(hitbox)) {
            synchronized (END_TURN_WAITING_FOR_RELEASE) {
                END_TURN_WAITING_FOR_RELEASE.put(button, Boolean.TRUE);
            }
        }
    }

    private static boolean isEndTurnButtonWaitingForRelease(EndTurnButton button) {
        if (button == null) {
            return false;
        }
        synchronized (END_TURN_WAITING_FOR_RELEASE) {
            return Boolean.TRUE.equals(END_TURN_WAITING_FOR_RELEASE.get(button));
        }
    }

    private static void clearEndTurnButtonWaitingForRelease(EndTurnButton button) {
        if (button == null) {
            return;
        }
        synchronized (END_TURN_WAITING_FOR_RELEASE) {
            END_TURN_WAITING_FOR_RELEASE.remove(button);
        }
    }

    private static boolean hasClickState(Hitbox hitbox) {
        return hitbox != null && (hitbox.clicked || hitbox.clickStarted);
    }

    private static void resetEndTurnButtonHitbox(Hitbox hitbox) {
        resetHitbox(hitbox);
        if (hitbox == null) {
            return;
        }
        hitbox.hovered = false;
        hitbox.justHovered = false;
    }

    private static Hitbox getEndTurnButtonHitbox(EndTurnButton button) {
        if (button == null || END_TURN_BUTTON_HB_FIELD == null) {
            return null;
        }
        try {
            return (Hitbox) END_TURN_BUTTON_HB_FIELD.get(button);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void resetEndTurnButtonHoldProgress(EndTurnButton button) {
        if (END_TURN_BUTTON_HOLD_PROGRESS_FIELD == null) {
            return;
        }
        try {
            END_TURN_BUTTON_HOLD_PROGRESS_FIELD.setFloat(button, 0.0f);
        } catch (IllegalAccessException e) {
            // Ignore unavailable runtime fields; hitbox cleanup still prevents stale releases.
        }
    }

    private static Field resolveProceedButtonHitboxField() {
        try {
            Field field = ProceedButton.class.getDeclaredField("hb");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Field resolveEndTurnButtonHitboxField() {
        try {
            Field field = EndTurnButton.class.getDeclaredField("hb");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Field resolveEndTurnButtonHoldProgressField() {
        try {
            Field field = EndTurnButton.class.getDeclaredField("holdProgress");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
