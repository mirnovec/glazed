package com.nnpg.glazed.modules.main;


import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedScheduler;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

public class HomeReset extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Show chat messages when running commands")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> homeSlot = sgGeneral.add(new IntSetting.Builder()
        .name("home-slot")
        .description("Which home slot to reset (1–5).")
        .defaultValue(1)
        .min(1).max(5)
        .build()
    );

    public HomeReset() {
        super(GlazedAddon.CATEGORY, "home-reset", "Automatically runs /delhome and /sethome for a selected slot.");
    }

    @Override
    public void onActivate() {
        // used to just return and sit there still enabled doing nothing
        if (mc.player == null || mc.level == null) {
            if (chatFeedback.get()) ChatUtils.warning("Not in a world.");
            toggle();
            return;
        }

        int slot = homeSlot.get();
        sendServerCommand("/delhome " + slot);

        GlazedScheduler.scheduleSeconds(() -> {
            // you can dc during the 1s wait
            if (mc.player == null || mc.level == null) {
                toggle();
                return;
            }

            sendServerCommand("/sethome " + slot);

            if (chatFeedback.get()) {
                ChatUtils.info("§aHome " + slot + " deleted and set successfully!");
            }

            toggle();
        }, 1);
    }

    private void sendServerCommand(String command) {
        ChatUtils.sendPlayerMsg(command);
    }
}
