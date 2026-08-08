package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.hud.AdminBoard;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AdminHud extends Module {
    private static final int POLL_TICKS = 10;
    private static final long TIMED_SCAN_MS = 90_000L;
    private static final long TIMED_JITTER_MS = 15_000L;
    private static final long TRIGGER_COOLDOWN_MS = 30_000L;
    private static final double TRIGGER_Y = -10.0;
    private static final double DROP_BLOCKS = 15.0;
    private static final int JOIN_GRACE_TICKS = 60;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWho = settings.createGroup("Who");
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Boolean> alerts = sgGeneral.add(new BoolSetting.Builder()
        .name("alerts")
        .description("Play the arrow hit ding when one of them joins or leaves tab.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> panel = sgGeneral.add(new BoolSetting.Builder()
        .name("panel")
        .description("Show the online list. Drag it by the header with chat open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatMessage = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message")
        .description("Also print it in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> names = sgWho.add(new StringListSetting.Builder()
        .name("names")
        .description("Names to watch for in tab.")
        .defaultValue(Arrays.asList(
            "drdonutt", "archivePedro", "Frwost", "W1zoX_", "Fluffymaster07",
            "bautiedgar", "Showered", "PastaGamer08", "Itszdeath", "0Gsummer", "Munkerlich"
        ))
        .build()
    );

    private final Setting<Boolean> pullAdminList = sgWho.add(new BoolSetting.Builder()
        .name("pull-admin-list")
        .description("Also watch everything in the admin-list module.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> hudX = sgHud.add(new IntSetting.Builder()
        .name("hud-x")
        .description("Panel X. -1 parks it top right.")
        .defaultValue(-1)
        .min(-1)
        .sliderRange(-1, 4000)
        .build()
    );

    private final Setting<Integer> hudY = sgHud.add(new IntSetting.Builder()
        .name("hud-y")
        .description("Panel Y. -1 parks it top right.")
        .defaultValue(-1)
        .min(-1)
        .sliderRange(-1, 4000)
        .build()
    );

    private final Setting<Integer> scale = sgHud.add(new IntSetting.Builder()
        .name("scale")
        .description("Size of the panel, faces and text.")
        .defaultValue(100)
        .min(70)
        .max(150)
        .sliderRange(70, 150)
        .build()
    );

    private final AdminBoard board = new AdminBoard();

    private final Map<String, String> onlineNow = new HashMap<>();

    private long nextTimedScan;
    private long lastTriggerScan;
    private double highestY = -999.0;
    private boolean scannedOnJoin;
    private boolean seeded;
    private int ticks;

    public AdminHud() {
        super(GlazedAddon.CATEGORY, "admin-hud", "Tells you when staff are in tab.");
    }

    @Override
    public void onActivate() {
        wipe();
    }

    @Override
    public void onDeactivate() {
        wipe();
    }

    @Override
    public String getInfoString() {
        return onlineNow.isEmpty() ? "clear" : onlineNow.size() + " online";
    }

    private void wipe() {
        onlineNow.clear();
        board.clear();

        nextTimedScan = System.currentTimeMillis() + 3000L;
        lastTriggerScan = 0L;
        highestY = -999.0;
        scannedOnJoin = false;
        seeded = false;
        ticks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        ticks++;
        if (ticks % POLL_TICKS != 0 && !triggered()) return;

        Map<String, String> found = scanTab();

        if (!seeded) {
            onlineNow.clear();
            onlineNow.putAll(found);
            board.sync(found, List.of(), List.of(), true);
            seeded = true;
            return;
        }

        List<String> joined = new ArrayList<>();
        for (String key : found.keySet()) {
            if (!onlineNow.containsKey(key)) joined.add(key);
        }

        List<String> left = new ArrayList<>();
        for (String key : onlineNow.keySet()) {
            if (!found.containsKey(key)) left.add(key);
        }

        board.sync(found, joined, left, false);

        for (String key : joined) announce(found.get(key), true);
        for (String key : left) announce(onlineNow.get(key), false);

        onlineNow.clear();
        onlineNow.putAll(found);
    }

    private void announce(String display, boolean online) {
        if (display == null) return;

        if (alerts.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ARROW_HIT_PLAYER, 1.0f, 1.0f);
        }
        if (chatMessage.get()) {
            info(Component.literal(display + (online ? " joined tab." : " left tab.")));
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!panel.get()) return;

        board.drag(hudX, hudY, scale);
        board.render(event.drawContext, hudX, hudY, scale);
    }

    // ayo
    private boolean triggered() {
        long now = System.currentTimeMillis();
        double y = mc.player.getY();

        if (now >= nextTimedScan) {
            nextTimedScan = now + TIMED_SCAN_MS - TIMED_JITTER_MS + (long) (Math.random() * TIMED_JITTER_MS * 2.0);
            return true;
        }

        if (y <= TRIGGER_Y && now - lastTriggerScan >= TRIGGER_COOLDOWN_MS) {
            lastTriggerScan = now;
            return true;
        }

        if (y > highestY) highestY = y;
        if (highestY - y >= DROP_BLOCKS && now - lastTriggerScan >= TRIGGER_COOLDOWN_MS) {
            lastTriggerScan = now;
            highestY = y;
            return true;
        }

        if (!scannedOnJoin && mc.player.tickCount > JOIN_GRACE_TICKS) {
            scannedOnJoin = true;
            return true;
        }

        return false;
    }

    // huh
    private Map<String, String> scanTab() {
        Map<String, String> found = new HashMap<>();

        ClientPacketListener handler = mc.getConnection();
        if (handler == null) return found;

        Set<String> watching = watchList();
        if (watching.isEmpty()) return found;

        for (PlayerInfo entry : handler.getOnlinePlayers()) {
            String profile = entry.getProfile().name();
            if (profile != null && watching.contains(profile.toLowerCase(Locale.ROOT))) {
                found.put(profile.toLowerCase(Locale.ROOT), profile);
                continue;
            }

            if (entry.getTabListDisplayName() == null) continue;
            String shown = strip(entry.getTabListDisplayName().getString());
            if (watching.contains(shown)) found.put(shown, profile != null && !profile.isEmpty() ? profile : shown);
        }

        for (String name : watching) {
            if (found.containsKey(name)) continue;
            PlayerInfo entry = handler.getPlayerInfoIgnoreCase(name);
            if (entry != null) found.put(name, entry.getProfile().name());
        }

        return found;
    }

    private Set<String> watchList() {
        Set<String> watching = new HashSet<>();

        for (String name : names.get()) {
            if (name != null && !name.isBlank()) watching.add(name.trim().toLowerCase(Locale.ROOT));
        }

        if (pullAdminList.get()) {
            AdminList list = Modules.get().get(AdminList.class);
            if (list != null) {
                for (String name : list.admins.get()) {
                    if (name != null && !name.isBlank()) watching.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        return watching;
    }

    private static String strip(String text) {
        return text.replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
    }
}
