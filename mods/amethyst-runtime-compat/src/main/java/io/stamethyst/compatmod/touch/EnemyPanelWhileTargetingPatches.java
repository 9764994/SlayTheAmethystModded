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

public final class EnemyPanelWhileTargetingPatches {
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty(
            "amethyst.runtime_compat.hide_enemy_panel_while_targeting", "true"));

    private EnemyPanelWhileTargetingPatches() {
    }

    @SpirePatch2(clz = AbstractMonster.class, method = SpirePatch.CLASS)
    public static class HoverState {
        public static SpireField<EnemyPanelHoverDelay> delay = new SpireField<>(EnemyPanelHoverDelay::new);
    }

    @SpirePatch2(clz = AbstractMonster.class, method = "update")
    public static class Update {
        @SpirePostfixPatch
        public static void Postfix(AbstractMonster __instance) {
            if (ENABLED) update(__instance);
        }
    }

    private static boolean update(AbstractMonster monster) {
        AbstractPlayer player = AbstractDungeon.player;
        // Any card drag (or an active target arrow) starts the suppression window, regardless of
        // the dragged card's target type.
        boolean dragActive = player != null && (player.isDraggingCard || player.inSingleTargetMode);
        boolean hovered = !monster.isDeadOrEscaped() && (monster.hb.hovered
            || monster.healthHb.hovered || monster.intentHb.hovered);
        return HoverState.delay.get(monster).update(hovered, dragActive,
            InputHelper.mX, InputHelper.mY, System.nanoTime());
    }

    @SpirePatch2(clz = AbstractMonster.class, method = "renderTip")
    public static class RenderTip {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(AbstractMonster __instance) {
            // Also sample here: player drag state may change after monster.update.
            return ENABLED && update(__instance) ? SpireReturn.Return() : SpireReturn.Continue();
        }
    }
}
