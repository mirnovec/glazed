package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;

/**
 * Sells slabs through /sell instead of the auction house.
 *
 * One cycle: fill up out of the chest you are looking at, run /sell, shift the slabs into the
 * chest that opens, click the confirm pane, then go back for more. The confirm click sells but
 * leaves the menu open, so the module closes it itself once the chest has actually emptied.
 */
public class SlabSeller extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSell = settings.createGroup("Sell menu");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Boolean> anySlab = sgGeneral.add(new BoolSetting.Builder()
        .name("any-slab")
        .description("Take every kind of slab. Off restricts it to the item below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Item> item = sgGeneral.add(new ItemSetting.Builder()
        .name("item")
        .description("The only item taken when any-slab is off.")
        .defaultValue(Items.SMOOTH_STONE_SLAB)
        .build()
    );

    private final Setting<Boolean> requireChestLook = sgGeneral.add(new BoolSetting.Builder()
        .name("require-chest-look")
        .description("Only start a cycle while your crosshair is on a chest. That chest is where the slabs come from, and looking away is how you pause it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowBarrels = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-barrels")
        .description("Count barrels as chests for the look check.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> sellCommand = sgSell.add(new StringSetting.Builder()
        .name("sell-command")
        .description("Command that opens the sell chest. No leading slash.")
        .defaultValue("sell")
        .build()
    );

    private final Setting<Item> confirmButton = sgSell.add(new ItemSetting.Builder()
        .name("confirm-button")
        .description("The pane you click to sell. Switch this to lime stained glass if that is what your server uses.")
        .defaultValue(Items.GREEN_STAINED_GLASS_PANE)
        .build()
    );

    private final Setting<Boolean> confirmFromEnd = sgSell.add(new BoolSetting.Builder()
        .name("confirm-bottom-right")
        .description("Search for the confirm pane from the last slot backwards, so the bottom right one wins when the menu has several.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> menuTimeout = sgSell.add(new IntSetting.Builder()
        .name("menu-timeout")
        .description("Ticks to wait for a menu before giving up on this cycle.")
        .defaultValue(80)
        .min(20)
        .max(400)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> timingJitter = sgTiming.add(new IntSetting.Builder()
        .name("timing-jitter")
        .description("Randomly varies every delay by up to this percent, so no two cycles have the same rhythm.")
        .defaultValue(25)
        .min(0)
        .max(60)
        .sliderMax(50)
        .build()
    );

    private final Setting<Integer> clickDelay = sgTiming.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Ticks between each slot click while moving slabs around.")
        .defaultValue(4)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> screenDelay = sgTiming.add(new IntSetting.Builder()
        .name("screen-delay")
        .description("Ticks to wait after a menu opens or closes.")
        .defaultValue(6)
        .min(1)
        .max(60)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> confirmDelay = sgTiming.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Ticks to wait after the chest is filled before clicking the confirm pane.")
        .defaultValue(8)
        .min(1)
        .max(100)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> verifyDelay = sgTiming.add(new IntSetting.Builder()
        .name("verify-delay")
        .description("Ticks after the confirm click before checking the chest actually emptied.")
        .defaultValue(12)
        .min(1)
        .max(100)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> cycleGap = sgTiming.add(new IntSetting.Builder()
        .name("cycle-gap")
        .description("Ticks between one sale finishing and the next fill starting.")
        .defaultValue(40)
        .min(5)
        .max(400)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> idleBackoff = sgTiming.add(new IntSetting.Builder()
        .name("idle-backoff")
        .description("Ticks to wait before retrying when a cycle did no work, so an empty chest cannot turn into command spam.")
        .defaultValue(400)
        .min(40)
        .max(6000)
        .sliderMax(1200)
        .build()
    );

    private enum State {
        IDLE,
        CHEST_OPEN, CHEST_WAIT, GRAB, CHEST_CLOSE,
        SELL_SEND, SELL_WAIT, FILL, CONFIRM, VERIFY, SELL_CLOSE,
        COOLDOWN
    }

    private final Random random = new Random();

    private State state = State.IDLE;
    private int delayCounter = 0;
    private int waited = 0;
    private int grabbed = 0;
    private int filled = 0;
    private int sold = 0;
    private int stalled = 0;
    private BlockPos chestPos = null;

    public SlabSeller() {
        super(GlazedAddon.CATEGORY, "slab-seller", "Fills up on slabs from a chest and sells them through /sell, over and over.");
    }

    @Override
    public void onActivate() {
        resetCycle();
        state = State.IDLE;
        delayCounter = 0;
        sold = 0;
    }

    @Override
    public void onDeactivate() {
        closeAnyMenu();
        state = State.IDLE;
    }

    private void resetCycle() {
        grabbed = 0;
        filled = 0;
        waited = 0;
        stalled = 0;
        chestPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        switch (state) {
            case IDLE -> tickIdle();
            case CHEST_OPEN -> tickChestOpen();
            case CHEST_WAIT -> tickChestWait();
            case GRAB -> tickGrab();
            case CHEST_CLOSE -> tickChestClose();
            case SELL_SEND -> tickSellSend();
            case SELL_WAIT -> tickSellWait();
            case FILL -> tickFill();
            case CONFIRM -> tickConfirm();
            case VERIFY -> tickVerify();
            case SELL_CLOSE -> tickSellClose();
            case COOLDOWN -> {
                resetCycle();
                state = State.IDLE;
            }
        }
    }

    // ---------------------------------------------------------------- cycle start

    private void tickIdle() {
        BlockPos target = lookedAtChest();

        if (requireChestLook.get() && target == null) {
            // waiting, silently. looking away is how you pause this thing
            delayCounter = jitter(12, 4);
            return;
        }

        chestPos = target;
        grabbed = 0;
        filled = 0;
        waited = 0;
        state = State.CHEST_OPEN;
    }

    /** The chest the crosshair is on, or null. */
    private BlockPos lookedAtChest() {
        if (!(mc.hitResult instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;

        Block block = mc.level.getBlockState(hit.getBlockPos()).getBlock();

        if (block instanceof ChestBlock) return hit.getBlockPos();
        if (allowBarrels.get() && block instanceof BarrelBlock) return hit.getBlockPos();

        return null;
    }

    // ---------------------------------------------------------------- filling up

    private void tickChestOpen() {
        BlockPos target = lookedAtChest();

        if (target == null) {
            if (requireChestLook.get()) {
                if (notifications.get()) warning("Not looking at a chest any more, backing off.");
                endCycleBackoff();
                return;
            }
            target = chestPos;
        }

        if (target == null || !(mc.hitResult instanceof BlockHitResult hit)) {
            endCycleBackoff();
            return;
        }

        // the same call a right click makes, swing included
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);

        waited = 0;
        delayCounter = jitter(screenDelay.get(), 1);
        state = State.CHEST_WAIT;
    }

    private void tickChestWait() {
        if (openChest() != null) {
            stalled = 0;
            state = State.GRAB;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("Chest never opened, backing off.");
            endCycleBackoff();
        }
    }

    private ChestMenu openChest() {
        if (mc.player.containerMenu == mc.player.inventoryMenu) return null;
        return mc.player.containerMenu instanceof ChestMenu menu ? menu : null;
    }

    /** Shift-clicks slabs out of the chest until the inventory has no room left. */
    private void tickGrab() {
        ChestMenu menu = openChest();

        if (menu == null) {
            endCycleBackoff();
            return;
        }

        if (firstEmptyPlayerSlot(menu) < 0) {
            // inventory is full, that is the load
            state = State.CHEST_CLOSE;
            return;
        }

        int source = findSlab(menu, 0, containerSlots(menu));

        if (source < 0) {
            if (notifications.get() && grabbed == 0) info("No slabs in the chest.");
            state = State.CHEST_CLOSE;
            return;
        }

        ItemStack before = menu.getSlot(source).getItem().copy();
        mc.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);

        if (ItemStack.matches(before, menu.getSlot(source).getItem())) {
            if (++stalled >= 4) {
                if (notifications.get()) warning("Slabs are not moving out of the chest, stopping the grab.");
                state = State.CHEST_CLOSE;
                return;
            }
        } else {
            stalled = 0;
            grabbed++;
        }

        delayCounter = jitter(clickDelay.get(), 1);
    }

    private void tickChestClose() {
        closeAnyMenu();
        delayCounter = jitter(screenDelay.get(), 1);

        if (countInInventory() <= 0) {
            // nothing to sell, do not come straight back and re-run the command
            endCycleBackoff();
            return;
        }

        if (notifications.get()) info("Took %d stack(s) of slabs, selling.", grabbed);
        state = State.SELL_SEND;
    }

    // ---------------------------------------------------------------- the sell menu

    private void tickSellSend() {
        mc.getConnection().sendCommand(sellCommand.get().trim());
        waited = 0;
        delayCounter = jitter(screenDelay.get(), 1);
        state = State.SELL_WAIT;
    }

    /**
     * Waits for the sell chest. The confirm pane is what tells it apart from any other container,
     * so a menu without one is never filled or clicked.
     */
    private void tickSellWait() {
        ChestMenu menu = openChest();

        if (menu != null && findConfirmSlot(menu) >= 0) {
            stalled = 0;
            filled = 0;
            state = State.FILL;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("Sell menu never opened, backing off.");
            endCycleBackoff();
        }
    }

    /** Shift-clicks slabs from the inventory into the sell chest, one click at a time. */
    private void tickFill() {
        ChestMenu menu = openChest();

        if (menu == null) {
            if (notifications.get()) warning("Sell menu closed early, backing off.");
            endCycleBackoff();
            return;
        }

        int total = containerSlots(menu);
        int source = findSlab(menu, total, menu.slots.size());

        if (source < 0) {
            // nothing left to move
            if (filled == 0) {
                if (notifications.get()) warning("Nothing went into the sell chest, backing off.");
                endCycleBackoff();
                return;
            }

            delayCounter = jitter(confirmDelay.get(), 1);
            state = State.CONFIRM;
            return;
        }

        if (firstEmptyContainerSlot(menu) < 0) {
            // chest is full, sell what is in it and come back for the rest
            delayCounter = jitter(confirmDelay.get(), 1);
            state = State.CONFIRM;
            return;
        }

        ItemStack before = mc.player.getInventory().getItem(playerSlotIndex(menu, source)).copy();
        mc.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);

        if (ItemStack.matches(before, mc.player.getInventory().getItem(playerSlotIndex(menu, source)))) {
            if (++stalled >= 4) {
                if (notifications.get()) warning("Slabs are not going into the sell chest.");

                if (filled == 0) {
                    endCycleBackoff();
                    return;
                }

                delayCounter = jitter(confirmDelay.get(), 1);
                state = State.CONFIRM;
                return;
            }
        } else {
            stalled = 0;
            filled++;
        }

        delayCounter = jitter(clickDelay.get(), 1);
    }

    private void tickConfirm() {
        ChestMenu menu = openChest();

        if (menu == null) {
            if (notifications.get()) warning("Sell menu closed before the confirm click.");
            endCycleBackoff();
            return;
        }

        int button = findConfirmSlot(menu);

        if (button < 0) {
            if (notifications.get()) warning("No %s in the sell menu, backing off.", buttonName());
            endCycleBackoff();
            return;
        }

        mc.gameMode.handleContainerInput(menu.containerId, button, 0, ContainerInput.PICKUP, mc.player);
        waited = 0;
        delayCounter = jitter(verifyDelay.get(), 1);
        state = State.VERIFY;
    }

    /**
     * The click sells but leaves the menu open, so an emptied chest is the signal it worked. The
     * inventory is no use here, the slabs left it during the fill.
     */
    private void tickVerify() {
        ChestMenu menu = openChest();

        if (menu == null) {
            // server closed it for us, treat that as done
            sold += filled;
            endCycle();
            return;
        }

        if (findSlab(menu, 0, containerSlots(menu)) < 0) {
            sold += filled;
            if (notifications.get()) info("Sold %d stack(s), %d this session.", filled, sold);
            state = State.SELL_CLOSE;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("Slabs are still sitting in the sell chest, leaving it alone.");
            state = State.SELL_CLOSE;
        }
    }

    private void tickSellClose() {
        // same thing pressing E does, which the server also treats as a sell
        closeAnyMenu();
        endCycle();
    }

    // ---------------------------------------------------------------- helpers

    private int containerSlots(ChestMenu menu) {
        return Math.min(menu.getRowCount() * 9, menu.slots.size());
    }

    /** Menu slot index converted back to an inventory index, for before/after comparisons. */
    private int playerSlotIndex(ChestMenu menu, int menuSlot) {
        int offset = menuSlot - containerSlots(menu);

        // the menu lists the three storage rows first, then the hotbar
        return offset < 27 ? offset + 9 : offset - 27;
    }

    /** First slab in the menu slot range [from, to). */
    private int findSlab(ChestMenu menu, int from, int to) {
        for (int slot = from; slot < to && slot < menu.slots.size(); slot++) {
            if (isSlab(menu.getSlot(slot).getItem())) return slot;
        }
        return -1;
    }

    private boolean isSlab(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!anySlab.get()) return stack.is(item.get());

        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof SlabBlock;
    }

    private int findConfirmSlot(ChestMenu menu) {
        int total = containerSlots(menu);

        if (confirmFromEnd.get()) {
            for (int slot = total - 1; slot >= 0; slot--) {
                if (menu.getSlot(slot).getItem().is(confirmButton.get())) return slot;
            }
            return -1;
        }

        for (int slot = 0; slot < total; slot++) {
            if (menu.getSlot(slot).getItem().is(confirmButton.get())) return slot;
        }

        return -1;
    }

    private int firstEmptyPlayerSlot(ChestMenu menu) {
        for (int slot = containerSlots(menu); slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private int firstEmptyContainerSlot(ChestMenu menu) {
        for (int slot = 0; slot < containerSlots(menu); slot++) {
            if (menu.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private int countInInventory() {
        int total = 0;
        int size = mc.player.getInventory().getContainerSize();

        for (int slot = 0; slot < 36 && slot < size; slot++) {
            if (isSlab(mc.player.getInventory().getItem(slot))) total++;
        }

        return total;
    }

    private String buttonName() {
        return confirmButton.get().getDefaultInstance().getHoverName().getString();
    }

    private void endCycle() {
        closeAnyMenu();
        delayCounter = jitter(cycleGap.get(), 5);
        state = State.COOLDOWN;
    }

    /** Cycle that did no work. Longer, still randomised, so an empty chest does not spam commands. */
    private void endCycleBackoff() {
        closeAnyMenu();
        delayCounter = jitter(idleBackoff.get(), 40);
        state = State.COOLDOWN;
    }

    private void closeAnyMenu() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
    }

    private int jitter(int ticks, int floor) {
        int pct = timingJitter.get();
        if (pct <= 0) return Math.max(floor, ticks);

        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * (pct / 100.0);
        return Math.max(floor, (int) Math.round(ticks * factor));
    }
}
