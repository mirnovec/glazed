package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.MoneyFmt;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import java.util.ArrayList;
import java.util.List;

public class FakeScoreboard extends Module {
    private static final String OBJECTIVE = "glazed_fakesb";
    private static final String TEAM_PREFIX = "glazed_sb_";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLines = settings.createGroup("Lines");

    private final Setting<String> title = sgGeneral.add(new StringSetting.Builder()
        .name("title")
        .description("Text at the top of the board.")
        .defaultValue("Drdonutt")
        .build()
    );

    private final Setting<Boolean> showMoney = sgLines.add(new BoolSetting.Builder()
        .name("money")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> money = sgLines.add(new StringSetting.Builder()
        .name("money-amount")
        .defaultValue("67B")
        .visible(showMoney::get)
        .build()
    );

    private final Setting<Boolean> showShards = sgLines.add(new BoolSetting.Builder()
        .name("shards")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> shards = sgLines.add(new StringSetting.Builder()
        .name("shards-amount")
        .defaultValue("67K")
        .visible(showShards::get)
        .build()
    );

    private final Setting<Boolean> showKills = sgLines.add(new BoolSetting.Builder()
        .name("kills")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> kills = sgLines.add(new StringSetting.Builder()
        .name("kills-amount")
        .defaultValue("67")
        .visible(showKills::get)
        .build()
    );

    private final Setting<Boolean> showDeaths = sgLines.add(new BoolSetting.Builder()
        .name("deaths")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> deaths = sgLines.add(new StringSetting.Builder()
        .name("deaths-amount")
        .defaultValue("67")
        .visible(showDeaths::get)
        .build()
    );

    private final Setting<Boolean> showPlaytime = sgLines.add(new BoolSetting.Builder()
        .name("playtime")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> playtime = sgLines.add(new StringSetting.Builder()
        .name("playtime-amount")
        .defaultValue("67h")
        .visible(showPlaytime::get)
        .build()
    );

    private Objective ours;
    private Objective serverBoard;
    private final List<String> teams = new ArrayList<>();
    private long lastRefresh;

    public FakeScoreboard() {
        super(GlazedAddon.CATEGORY, "fake-scoreboard", "Replaces the sidebar with your own numbers.");
    }

    @Override
    public void onActivate() {
        lastRefresh = 0L;
        rebuild();
    }

    @Override
    public void onDeactivate() {
        teardown();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        ours = null;
        serverBoard = null;
        teams.clear();
        lastRefresh = 0L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // nah
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 1000) return;

        lastRefresh = now;
        rebuild();
    }

    public void refresh() {
        lastRefresh = 0L;
    }

    private void rebuild() {
        if (mc.level == null || mc.player == null) return;

        Scoreboard board = mc.level.getScoreboard();

        Objective current = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (current != null && !OBJECTIVE.equals(current.getName())) serverBoard = current;

        dropTeams(board);

        Objective old = board.getObjective(OBJECTIVE);
        if (old != null) {
            try {
                board.removeObjective(old);
            } catch (Exception ignored) {
            }
        }

        ours = board.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, white(title.get()),
            ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        board.setDisplayObjective(DisplaySlot.SIDEBAR, ours);

        List<MutableComponent> lines = buildLines();
        for (int i = 0; i < lines.size(); i++) {
            String name = TEAM_PREFIX + i;
            teams.add(name);

            PlayerTeam team = board.getPlayerTeam(name);
            if (team != null) board.removePlayerTeam(team);
            team = board.addPlayerTeam(name);
            team.setPlayerPrefix(lines.get(i));

            // sus
            String holder = "§" + Integer.toHexString(i);
            ScoreHolder scoreHolder = ScoreHolder.forNameOnly(holder);

            board.resetSinglePlayerScore(scoreHolder, ours);
            ScoreAccess score = board.getOrCreatePlayerScore(scoreHolder, ours);
            score.set(lines.size() - i);
            board.addPlayerToTeam(holder, team);
        }
    }

    private List<MutableComponent> buildLines() {
        List<MutableComponent> lines = new ArrayList<>();

        if (showMoney.get()) lines.add(colored("$ ", 0x00FF00).append(white(displayedMoney())));
        if (showShards.get()) lines.add(colored("★ ", 0xA503FC).append(white(shards.get())));
        if (showKills.get()) lines.add(colored("🗡 ", 0xFF0000).append(white(kills.get())));
        if (showDeaths.get()) lines.add(colored("☠ ", 0xFC7703).append(white(deaths.get())));
        if (showPlaytime.get()) lines.add(colored("⌚ ", 0xFFE600).append(white(playtime.get())));

        if (lines.isEmpty()) lines.add(Component.literal(" "));
        return lines;
    }

    private String displayedMoney() {
        Double base = MoneyFmt.parse(money.get());
        if (base == null) return money.get();

        FakePay pay = Modules.get().get(FakePay.class);
        double spent = pay != null && pay.isActive() && pay.removesFromScoreboard() ? pay.spentOffset() : 0.0;

        return MoneyFmt.format(Math.max(0.0, base - spent));
    }

    private void teardown() {
        if (mc.level == null) {
            ours = null;
            teams.clear();
            return;
        }

        Scoreboard board = mc.level.getScoreboard();
        dropTeams(board);

        if (ours != null) {
            try {
                board.removeObjective(ours);
            } catch (Exception ignored) {
            }
            ours = null;
        }

        Objective restore = null;
        if (serverBoard != null) {
            try {
                if (board.getObjective(serverBoard.getName()) != null) restore = serverBoard;
            } catch (Exception ignored) {
            }
        }

        try {
            board.setDisplayObjective(DisplaySlot.SIDEBAR, restore);
        } catch (Exception ignored) {
        }

        serverBoard = null;
    }

    private void dropTeams(Scoreboard board) {
        for (String name : teams) {
            try {
                PlayerTeam team = board.getPlayerTeam(name);
                if (team != null) board.removePlayerTeam(team);
            } catch (Exception ignored) {
            }
        }
        teams.clear();
    }

    private MutableComponent colored(String text, int rgb) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    private MutableComponent white(String text) {
        return colored(text, 0xFFFFFF);
    }
}
