package com.nnpg.glazed.mixins;

import com.nnpg.glazed.modules.main.FakePay;
import com.nnpg.glazed.modules.main.FakeScoreboard;
import com.nnpg.glazed.utils.MoneyFmt;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(Gui.class)
public abstract class InGameHudMixin {

    @Shadow @Final private Minecraft minecraft;

    @Unique private static final Pattern glazed$MONEY = Pattern.compile("\\$\\s*([0-9][0-9.,]*\\s*[kKmMbB]?)");

    @Unique private PlayerTeam glazed$patchedTeam;
    @Unique private Component glazed$oldPrefix;
    @Unique private Component glazed$oldSuffix;

    // lol
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void glazed$beforeScoreboard(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        FakeScoreboard board = Modules.get().get(FakeScoreboard.class);
        if (board != null && board.isActive()) return;

        FakePay pay = Modules.get().get(FakePay.class);
        if (pay == null || !pay.isActive() || !pay.removesFromScoreboard() || pay.spentOffset() <= 0) return;

        glazed$patchMoney(pay.spentOffset());
    }

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void glazed$afterScoreboard(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        glazed$restoreMoney();
    }

    @Unique
    private void glazed$patchMoney(double offset) {
        try {
            if (glazed$patchedTeam != null) return;
            if (minecraft.level == null) return;

            Scoreboard board = minecraft.level.getScoreboard();
            if (board == null) return;

            Objective objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (objective == null) return;

            for (PlayerScoreEntry entry : board.listPlayerScores(objective)) {
                String owner = entry.owner();
                PlayerTeam team = board.getPlayersTeam(owner);
                if (team == null) continue;

                String prefix = team.getPlayerPrefix() != null ? team.getPlayerPrefix().getString() : "";
                String suffix = team.getPlayerSuffix() != null ? team.getPlayerSuffix().getString() : "";

                Double current = glazed$readMoney(prefix + (owner == null ? "" : owner) + suffix);
                if (current == null) continue;

                glazed$patchedTeam = team;
                glazed$oldPrefix = team.getPlayerPrefix();
                glazed$oldSuffix = team.getPlayerSuffix();

                team.setPlayerPrefix(Component.literal("$ " + MoneyFmt.format(Math.max(0.0, current - offset))));
                team.setPlayerSuffix(Component.literal(""));
                // duh
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void glazed$restoreMoney() {
        if (glazed$patchedTeam == null) return;

        try {
            glazed$patchedTeam.setPlayerPrefix(glazed$oldPrefix);
            glazed$patchedTeam.setPlayerSuffix(glazed$oldSuffix);
        } catch (Throwable ignored) {
        }

        glazed$patchedTeam = null;
        glazed$oldPrefix = null;
        glazed$oldSuffix = null;
    }

    @Unique
    private Double glazed$readMoney(String visible) {
        if (visible == null || !visible.contains("$")) return null;

        Matcher matcher = glazed$MONEY.matcher(visible);
        return matcher.find() ? MoneyFmt.parse(matcher.group(1).replace(" ", "")) : null;
    }
}
