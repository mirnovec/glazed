package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.VersionUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AHSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> sellPrice = sgGeneral.add(new StringSetting.Builder()
        .name("sell-price")
        .description("The price to list each hotbar item for. Supports K/M/B.")
        .defaultValue("30k")
        .build()
    );

    private final Setting<Integer> confirmDelay = sgGeneral.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Delay in ticks before clicking the confirm button.")
        .defaultValue(10)
        .min(0)
        .max(100)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-item-filter")
        .description("Only sell selected item type from the hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Item> filterItem = sgGeneral.add(new ItemSetting.Builder()
        .name("filter-item")
        .description("Only this item will be sold when filter is enabled.")
        .defaultValue(Items.DIAMOND)
        .build()
    );

    private int delayCounter = 0;
    private boolean awaitingConfirmation = false;
    private int currentSlot = 0;

    public AHSell() {
        super(GlazedAddon.CATEGORY, "ah-sell", "Automatically sells all hotbar items using /ah sell.");
    }

    @Override
    public void onActivate() {
        if (!isValidPrice(sellPrice.get())) {
            if (notifications.get()) error("Invalid price format: " + sellPrice.get());
            toggle();
            return;
        }

        if (!hasSellableItemsInHotbar()) {
            if (notifications.get()) error("No sellable items found in hotbar.");
            toggle();
            return;
        }

        currentSlot = 0;
        attemptSellCurrentSlot();
    }

    @Override
    public void onDeactivate() {
        awaitingConfirmation = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!awaitingConfirmation || mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // sometimes the confirm isnt a chest at all, its a server dialog with Yes / No buttons
        if (GlazedSell.isDialogOpen()) {
            if (GlazedSell.clickDialogYes()) {
                if (notifications.get()) info("Sold item in hotbar slot " + currentSlot + ".");
                awaitingConfirmation = false;
                moveToNextSlot();
            }
            return;
        }

        AbstractContainerMenu screenHandler = mc.player.containerMenu;

        if (screenHandler instanceof ChestMenu handler) {
            if (handler.getRowCount() == 3) {
                // slot 15 is the usual spot, but fall back to hunting the accept button since
                // the layout is not always the same
                int confirmSlot = handler.getSlot(15).getItem().isEmpty()
                    ? GlazedSell.findConfirmSlot(handler)
                    : 15;

                if (confirmSlot >= 0) {
                    mc.gameMode.handleContainerInput(handler.containerId, confirmSlot, 1, ContainerInput.QUICK_MOVE, mc.player);
                    if (notifications.get()) info("Sold item in hotbar slot " + currentSlot + ".");
                }

                awaitingConfirmation = false;
                moveToNextSlot();
            }
        }
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();
        if (msg.contains("You have too many listed items.")) {
            if (notifications.get()) warning("Sell limit reached! Disabling module.");
            toggle();
        }
    }

    private void attemptSellCurrentSlot() {
        if (currentSlot > 8) {
            if (notifications.get()) info("Finished processing hotbar. Disabling module.");
            toggle();
            return;
        }

        VersionUtil.setSelectedSlot(mc.player, currentSlot);
        ItemStack stack = mc.player.getInventory().getItem(currentSlot);

        if (enableFilter.get() && (stack.isEmpty() || !stack.is(filterItem.get()))) {
            if (notifications.get()) info("Skipping slot " + currentSlot + " (does not match filter).");
            moveToNextSlot();
            return;
        }

        if (stack.isEmpty()) {
            moveToNextSlot();
            return;
        }

        String price = sellPrice.get().trim();
        double parsedPrice = parsePrice(price);

        if (parsedPrice <= 0) {
            if (notifications.get()) error("Invalid price format: " + price);
            toggle();
            return;
        }

        if (notifications.get()) {
            info("Sending /ah sell %s for slot %d", formatPrice(parsedPrice), currentSlot);
        }

        mc.getConnection().sendCommand("ah sell " + price);
        delayCounter = confirmDelay.get();
        awaitingConfirmation = true;
    }

    private void moveToNextSlot() {
        currentSlot++;
        attemptSellCurrentSlot();
    }

    private boolean hasSellableItemsInHotbar() {
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;

            if (enableFilter.get()) {
                if (stack.is(filterItem.get())) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean isValidPrice(String priceStr) {
        return parsePrice(priceStr) > 0;
    }

    private double parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) return -1.0;

        String cleaned = priceStr.trim().toUpperCase();
        double multiplier = 1.0;

        if (cleaned.endsWith("B")) {
            multiplier = 1_000_000_000.0;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("M")) {
            multiplier = 1_000_000.0;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("K")) {
            multiplier = 1_000.0;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        try {
            return Double.parseDouble(cleaned) * multiplier;
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    private String formatPrice(double price) {
        if (price >= 1_000_000_000) {
            return String.format("%.2fB", price / 1_000_000_000);
        } else if (price >= 1_000_000) {
            return String.format("%.2fM", price / 1_000_000);
        } else if (price >= 1_000) {
            return String.format("%.2fK", price / 1_000);
        } else {
            return String.format("%.2f", price);
        }
    }
}
