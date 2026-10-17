package io.stamethyst.compatmod.touch;

import com.evacipated.cardcrawl.modthespire.lib.SpireField;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

public final class EnemyPanelWhileTargetingPatches {
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty(
            "amethyst.runtime_compat.hide_enemy_panel_while_targeting", "true"));

    private EnemyPanelWhileTargetingPatches() {
    }

    @SpirePatch2(clz = AbstractPlayer.class, method = SpirePatch.CLASS)
    public static class DragState {
        public static SpireField<EnemyPanelHoverDelay> delay = new SpireField<>(EnemyPanelHoverDelay::new);
    }

    @SpirePatch2(clz = AbstractPlayer.class, method = "updateInput")
    public static class PlayerInput {
        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance) {
            recordDrag(__instance);
        }

        @SpirePostfixPatch
        public static void Postfix(AbstractPlayer __instance) {
            recordDrag(__instance);
        }
    }

    // Capture the pre-cleanup state even when a card is played/released inside another patch.
    @SpirePatch2(clz = AbstractPlayer.class, method = "releaseCard")
    public static class ReleaseCard {
        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance) {
            recordDrag(__instance);
        }
    }

    @SpirePatch2(clz = AbstractPlayer.class, method = "playCard")
    public static class PlayCard {
        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance) {
            recordDrag(__instance);
        }
    }

    @SpirePatch2(clz = AbstractRoom.class, method = "update")
    public static class RoomUpdate {
        @SpirePostfixPatch
        public static void Postfix() {
            AbstractPlayer player = AbstractDungeon.player;
            if (!ENABLED || player == null) return;
            recordDrag(player);
            DragState.delay.get(player).updatePointer(InputHelper.mX, InputHelper.mY, System.nanoTime());
        }
    }

    private static void recordDrag(AbstractPlayer player) {
        if (!ENABLED) return;
        DragState.delay.get(player).recordDrag(player.isDraggingCard || player.inSingleTargetMode,
            System.nanoTime());
    }

    @SpirePatch2(clz = AbstractMonster.class, method = "renderTip")
    public static class RenderTip {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            AbstractPlayer player = AbstractDungeon.player;
            return ENABLED && player != null && DragState.delay.get(player).isSuppressed()
                ? SpireReturn.Return() : SpireReturn.Continue();
        }
    }
}
