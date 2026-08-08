package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import meteordevelopment.meteorclient.events.world.TickEvent;

public class HideScoreboard extends Module {
    private Objective savedObjective = null;

    public HideScoreboard() {
        super(GlazedAddon.CATEGORY, "hide-scoreboard", "Hides the sidebar scoreboard.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.level == null) return;

        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective current = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

        if (current != null) {
            if (savedObjective == null) savedObjective = current;
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.level == null || savedObjective == null) return;

        mc.level.getScoreboard().setDisplayObjective(DisplaySlot.SIDEBAR, savedObjective);
        savedObjective = null;
    }
}
