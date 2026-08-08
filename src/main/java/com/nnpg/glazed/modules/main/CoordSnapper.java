package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.utils.GlazedWebhook;
import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.core.BlockPos;


public class CoordSnapper extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chatfeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Show notification in chat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> webhook = sgGeneral.add(new BoolSetting.Builder()
        .name("webhook")
        .description("Enable webhook notifications")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL for notifications")
        .defaultValue("")
        .visible(webhook::get)
        .build()
    );

    private final Setting<Boolean> selfPing = sgGeneral.add(new BoolSetting.Builder()
        .name("Self Ping")
        .description("Ping yourself in the webhook message")
        .defaultValue(false)
        .visible(webhook::get)
        .build()
    );

    private final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("Discord ID")
        .description("Your Discord user ID for pinging")
        .defaultValue("")
        .visible(() -> webhook.get() && selfPing.get())
        .build()
    );

    public CoordSnapper() {
        super(GlazedAddon.CATEGORY, "coord-snapper", "Copies your coordinates to clipboard and optionally sends them via webhook.");
    }

    @Override
    public void onActivate() {
        // dont toggle in here, finally already does it
        // old one toggled twice so the module just turned itself back on lmao
        try {
            if (mc.player == null) {
                error("Player is null!");
                return;
            }

            BlockPos pos = mc.player.blockPosition();
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            String coords = String.format("%d %d %d", x, y, z);
            mc.keyboardHandler.setClipboard(coords);
            if (chatfeedback.get()) {
                info("Copied coordinates: " + coords);
            }

            if (webhook.get() && !webhookUrl.get().isEmpty()) {
                sendWebhook(x, y, z);
            }

        } catch (Exception e) {
            error("Failed to copy/send coordinates: " + e.getMessage());
        } finally {
            toggle();
        }
    }

    private void sendWebhook(int x, int y, int z) {
        GlazedWebhook.to(webhookUrl.get())
            .username("Glazed Webhook")
            .ping(selfPing.get() ? discordId.get() : null)
            .title("Coordsnapper Coords")
            .description(String.format("Coords: X: %d, Y: %d, Z: %d", x, y, z))
            .color(GlazedWebhook.COLOR_GLAZED)
            .onError(message -> error("Webhook failed: " + message))
            .send();
    }
}
