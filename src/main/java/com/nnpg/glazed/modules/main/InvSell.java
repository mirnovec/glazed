package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.VersionUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Random;

/**
 * Sells the whole inventory through /ah sell. Works the hotbar the same way AHSell does, then
 * opens the inventory and pulls the next batch down into the empty hotbar, until nothing sellable
 * is left.
 */
public class InvSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<String> sellPrice = sgGeneral.add(new StringSetting.Builder()
        .name("sell-price")
        .description("The price to list each item for. Supports K/M/B.")
        .defaultValue("30k")
        .build()
    );

    private final Setting<Boolean> openInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("open-inventory")
        .description("Open the inventory screen before moving items, the way a player would. Off sends the slot clicks with no screen open, which no real client does.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-item-filter")
        .description("Only sell the selected item type. Leave this off to sell everything.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Item> filterItem = sgGeneral.add(new ItemSetting.Builder()
        .name("filter-item")
        .description("Only this item will be sold when filter is enabled.")
        .defaultValue(Items.DIAMOND)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> verifySales = sgGeneral.add(new BoolSetting.Builder()
        .name("verify-sales")
        .description("After each sale, check the item actually left your inventory. Stops the module if it comes back.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> timingJitter = sgTiming.add(new IntSetting.Builder()
        .name("timing-jitter")
        .description("Randomly varies every delay by up to this percent, so the intervals are not identical every cycle.")
        .defaultValue(25)
        .min(0)
        .max(60)
        .sliderMax(50)
        .build()
    );

    private final Setting<Integer> verifyDelay = sgTiming.add(new IntSetting.Builder()
        .name("verify-delay")
        .description("Ticks to wait after a sale before checking the item left, so a refund has time to arrive.")
        .defaultValue(15)
        .min(1)
        .max(100)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> slotDelay = sgTiming.add(new IntSetting.Builder()
        .name("slot-delay")
        .description("Ticks to wait after switching hotbar slot before sending /ah sell. Must be at least 1 so the server sees the slot change first.")
        .defaultValue(2)
        .min(1)
        .max(20)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> confirmDelay = sgTiming.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Delay in ticks before clicking the confirm button.")
        .defaultValue(10)
        .min(0)
        .max(100)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> confirmTimeout = sgTiming.add(new IntSetting.Builder()
        .name("confirm-timeout")
        .description("Give up waiting for the confirm screen after this many ticks and move on.")
        .defaultValue(60)
        .min(10)
        .max(200)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> gapDelay = sgTiming.add(new IntSetting.Builder()
        .name("gap-delay")
        .description("Ticks to wait after a sale completes before starting the next slot.")
        .defaultValue(5)
        .min(0)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> screenDelay = sgTiming.add(new IntSetting.Builder()
        .name("screen-delay")
        .description("Ticks to wait after opening or closing the inventory screen before doing anything with it.")
        .defaultValue(4)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> refillDelay = sgTiming.add(new IntSetting.Builder()
        .name("refill-delay")
        .description("Ticks between each item moved down from the main inventory into the hotbar.")
        .defaultValue(3)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private enum State { SELECT, SEND, AWAIT_CONFIRM, VERIFY, GAP, REFILL_OPEN, REFILL, REFILL_CLOSE }

    private State state = State.SELECT;
    private int delayCounter = 0;
    private int currentSlot = 0;
    private int waited = 0;
    private int sold = 0;
    private int refilled = 0;
    private int stalledRefills = 0;
    private boolean finishAfterClose = false;
    private final Random random = new Random();
    private ItemStack soldRef = ItemStack.EMPTY;
    private int countBeforeSale = 0;

    public InvSell() {
        super(GlazedAddon.CATEGORY, "inv-sell", "Sells your entire inventory on the ah, pulling items down into the hotbar as it goes.");
    }

    @Override
    public void onActivate() {
        if (parsePrice(sellPrice.get()) <= 0) {
            if (notifications.get()) error("Invalid price format: " + sellPrice.get());
            toggle();
            return;
        }

        if (!hasSellableItem(0, 36)) {
            if (notifications.get()) error("No sellable items found in inventory.");
            toggle();
            return;
        }

        currentSlot = 0;
        sold = 0;
        refilled = 0;
        stalledRefills = 0;
        waited = 0;
        delayCounter = 0;
        finishAfterClose = false;
        soldRef = ItemStack.EMPTY;
        countBeforeSale = 0;
        state = State.SELECT;
    }

    @Override
    public void onDeactivate() {
        // never leave the inventory hanging open because the module was switched off mid-refill
        closeInventoryScreen();
        state = State.SELECT;
        delayCounter = 0;
        waited = 0;
        finishAfterClose = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        switch (state) {
            case SELECT -> tickSelect();
            case SEND -> tickSend();
            case AWAIT_CONFIRM -> tickAwaitConfirm();
            case VERIFY -> tickVerify();
            case GAP -> {
                currentSlot++;
                state = State.SELECT;
            }
            case REFILL_OPEN -> tickRefillOpen();
            case REFILL -> tickRefill();
            case REFILL_CLOSE -> tickRefillClose();
        }
    }

    private void tickSelect() {
        // hotbar exhausted, go pull the next batch down
        if (currentSlot > 8) {
            state = State.REFILL_OPEN;
            return;
        }

        // the sell flow runs with no screen up, same as typing the command by hand
        if (isInventoryScreenOpen()) {
            closeInventoryScreen();
            delayCounter = jitter(screenDelay.get(), 1);
            return;
        }

        ItemStack stack = mc.player.getInventory().getItem(currentSlot);

        if (!shouldSell(stack)) {
            currentSlot++;
            return;
        }

        // client-side only. the carried item packet is not flushed until MultiPlayerGameMode.tick(),
        // which runs after TickEvent.Pre, so yield a tick before the command or the server sells
        // whatever slot it still thinks is held
        VersionUtil.setSelectedSlot(mc.player, currentSlot);
        delayCounter = jitter(slotDelay.get(), 1);
        state = State.SEND;
    }

    private void tickSend() {
        String price = sellPrice.get().trim();
        double parsedPrice = parsePrice(price);

        if (parsedPrice <= 0) {
            if (notifications.get()) error("Invalid price format: " + price);
            toggle();
            return;
        }

        if (!shouldSell(mc.player.getInventory().getItem(currentSlot))) {
            currentSlot++;
            state = State.SELECT;
            return;
        }

        soldRef = mc.player.getInventory().getItem(currentSlot).copy();
        countBeforeSale = countMatching(soldRef);

        if (notifications.get()) info("Sending /ah sell %s for slot %d", formatPrice(parsedPrice), currentSlot);

        mc.getConnection().sendCommand("ah sell " + price);
        delayCounter = jitter(confirmDelay.get(), 0);
        waited = 0;
        state = State.AWAIT_CONFIRM;
    }

    private void tickAwaitConfirm() {
        if (GlazedSell.isDialogOpen()) {
            if (GlazedSell.clickDialogYes()) {
                afterSale();
            }
            return;
        }

        AbstractContainerMenu screenHandler = mc.player.containerMenu;

        if (screenHandler instanceof ChestMenu handler && handler.getRowCount() == 3) {
            if (GlazedSell.clickConfirm(handler)) {
                afterSale();
                return;
            }
        }

        if (++waited >= confirmTimeout.get()) {
            if (notifications.get()) warning("No confirm screen for slot " + currentSlot + ", skipping.");
            GlazedSell.close();
            delayCounter = jitter(gapDelay.get(), 0);
            state = State.GAP;
        }
    }

    /** Gets the inventory actually open before any slot gets clicked. */
    private void tickRefillOpen() {
        // a swap only lands in the player's own inventory menu, so make sure a shop menu is gone
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            GlazedSell.close();
            delayCounter = jitter(screenDelay.get(), 1);
            return;
        }

        if (!openInventory.get()) {
            state = State.REFILL;
            return;
        }

        if (isInventoryScreenOpen()) {
            state = State.REFILL;
            return;
        }

        // opening your own inventory sends nothing by itself, but it is what stops you sprinting
        // and puts the client in the state the following slot clicks are supposed to come from
        mc.setScreen(new InventoryScreen(mc.player));
        delayCounter = jitter(screenDelay.get(), 1);
        state = State.REFILL;
    }

    /**
     * Moves one stack from the main inventory into a free hotbar slot. Runs once per refill-delay
     * until the hotbar is stocked again, then closes up and restarts the hotbar pass.
     */
    private void tickRefill() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            state = State.REFILL_OPEN;
            return;
        }

        // something closed the screen under us, reopen before clicking anything
        if (openInventory.get() && !isInventoryScreenOpen()) {
            state = State.REFILL_OPEN;
            return;
        }

        int source = firstSellable(InventoryMenu.INV_SLOT_START, InventoryMenu.INV_SLOT_END);

        if (source < 0) {
            // nothing left upstairs. if the hotbar somehow refilled anyway, take another pass
            finishAfterClose = !hasSellableItem(0, 9);
            state = State.REFILL_CLOSE;
            return;
        }

        int target = firstEmptyHotbarSlot();

        if (target < 0) {
            // hotbar is stocked, go sell it before pulling anything else down
            finishAfterClose = false;
            state = State.REFILL_CLOSE;
            return;
        }

        ItemStack before = mc.player.getInventory().getItem(source).copy();

        // main inventory slots map 1:1 onto menu slots, and SWAP's button arg is the hotbar index,
        // exactly like pressing 1-9 over a stack
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, source, target, ContainerInput.SWAP, mc.player);

        // the swap is client-predicted, so this reads back immediately. if it did not take, stop
        // rather than spinning on the same slot forever
        if (ItemStack.matches(before, mc.player.getInventory().getItem(source))) {
            if (++stalledRefills >= 3) {
                if (notifications.get()) warning("Could not move items out of the inventory, stopping.");
                finishAfterClose = true;
                state = State.REFILL_CLOSE;
                return;
            }
        } else {
            stalledRefills = 0;
            refilled++;
        }

        delayCounter = jitter(refillDelay.get(), 1);
    }

    private void tickRefillClose() {
        closeInventoryScreen();
        delayCounter = jitter(screenDelay.get(), 1);

        if (finishAfterClose) {
            finish();
            return;
        }

        currentSlot = 0;
        state = State.SELECT;
    }

    private void afterSale() {
        sold++;
        if (notifications.get()) info("Sold item in hotbar slot " + currentSlot + ".");

        if (verifySales.get() && !soldRef.isEmpty()) {
            delayCounter = jitter(verifyDelay.get(), 1);
            state = State.VERIFY;
            return;
        }

        delayCounter = jitter(gapDelay.get(), 0);
        state = State.GAP;
    }

    /**
     * A listing that fails server side hands the item straight back. If we still hold as much of it
     * as before the sale, nothing actually left, so stop rather than hammering the same item.
     */
    private void tickVerify() {
        int after = countMatching(soldRef);

        if (after >= countBeforeSale) {
            if (notifications.get()) {
                warning("Item from slot %d came back to your inventory (%d -> %d), stopping.", currentSlot, countBeforeSale, after);
            }
            soldRef = ItemStack.EMPTY;
            toggle();
            return;
        }

        soldRef = ItemStack.EMPTY;
        delayCounter = jitter(gapDelay.get(), 0);
        state = State.GAP;
    }

    /** Total count of everything in the inventory that matches ref, item and components alike. */
    private int countMatching(ItemStack ref) {
        if (ref.isEmpty()) return 0;

        int total = 0;
        int size = mc.player.getInventory().getContainerSize();

        for (int slot = 0; slot < 36 && slot < size; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, ref)) total += stack.getCount();
        }

        return total;
    }

    /** Spreads a delay by +/- timing-jitter percent, never below floor. */
    private int jitter(int ticks, int floor) {
        int pct = timingJitter.get();
        if (pct <= 0) return Math.max(floor, ticks);

        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * (pct / 100.0);
        return Math.max(floor, (int) Math.round(ticks * factor));
    }

    private boolean isInventoryScreenOpen() {
        return mc.screen instanceof InventoryScreen;
    }

    /** Closes it the way the key bind does, so the same ContainerClose the server expects goes out. */
    private void closeInventoryScreen() {
        if (mc.screen instanceof InventoryScreen screen) screen.onClose();
    }

    private void finish() {
        if (notifications.get()) {
            info("Finished. Sold %d item(s), pulled %d down from the inventory.", sold, refilled);
        }
        toggle();
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();

        // never react to our own chat output, it re-enters this handler
        if (msg.contains("[Meteor]")) return;

        if (msg.contains("You have too many listed items.")) {
            if (notifications.get()) warning("Sell cap reached, disabling module.");
            toggle();
        }
    }

    private boolean shouldSell(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return !enableFilter.get() || stack.is(filterItem.get());
    }

    /** First inventory index in [from, to) holding something we want to sell, or -1. */
    private int firstSellable(int from, int to) {
        for (int slot = from; slot < to && slot < mc.player.getInventory().getContainerSize(); slot++) {
            if (shouldSell(mc.player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    private boolean hasSellableItem(int from, int to) {
        return firstSellable(from, to) >= 0;
    }

    private int firstEmptyHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
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
