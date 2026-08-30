package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.utils.RandomBetweenInt;
import java.util.Random;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Keeps /sell open and sells every stack that enters the player inventory. All inventory and
 * menu interaction goes through the same container-click path vanilla uses; no ItemStack or
 * inventory state is changed directly.
 */
public class UltraSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show status and retry messages in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RandomBetweenInt> itemDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("item-delay-range")
        .description("Random ticks between vanilla shift-clicks into the sell window.")
        .defaultRange(2, 5)
        .range(1, 100)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<RandomBetweenInt> confirmDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("confirm-delay-range")
        .description("Random ticks before clicking a green sell or confirmation control.")
        .defaultRange(6, 13)
        .range(1, 200)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<RandomBetweenInt> screenDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("screen-delay-range")
        .description("Random ticks after opening /sell or after the server changes screens.")
        .defaultRange(8, 16)
        .range(1, 200)
        .sliderRange(1, 80)
        .build()
    );

    private final Setting<RandomBetweenInt> idleDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("idle-check-range")
        .description("Random ticks between inventory checks while waiting for new items.")
        .defaultRange(4, 10)
        .range(1, 200)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Integer> hesitationChance = sgTiming.add(new IntSetting.Builder()
        .name("hesitation-chance")
        .description("Percent chance that an item click gets an extra human-like pause.")
        .defaultValue(10)
        .min(0)
        .max(50)
        .sliderMax(30)
        .build()
    );

    private final Setting<RandomBetweenInt> hesitationDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("hesitation-delay-range")
        .description("Extra random ticks added when a hesitation occurs.")
        .defaultRange(8, 28)
        .range(1, 400)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> menuTimeout = sgTiming.add(new IntSetting.Builder()
        .name("menu-timeout")
        .description("Ticks to wait for a sell-menu response before retrying.")
        .defaultValue(100)
        .min(20)
        .max(600)
        .sliderMax(240)
        .build()
    );

    private final Random random = new Random();

    private State state;
    private int delayCounter;
    private int waited;
    private int baselineContainerId;
    private int activeContainerId;
    private int clickedContainerId;
    private int failedMoves;
    private int batchMoves;
    private int sessionMoves;

    public UltraSell() {
        super(GlazedAddon.CATEGORY, "ultra-sell", "Keeps /sell open and continuously sells every item that enters your inventory. Press Escape to stop.");
    }

    @Override
    public void onActivate() {
        baselineContainerId = currentContainerId();
        activeContainerId = -1;
        clickedContainerId = -1;
        failedMoves = 0;
        batchMoves = 0;
        sessionMoves = 0;
        waited = 0;
        delayCounter = randomDelay(screenDelay);
        state = State.OPEN;

        if (notifications.get()) info("Ultra Sell started. Press Escape to leave /sell and stop.");
    }

    @Override
    public void onDeactivate() {
        // Deliberately do not close anything here. Escape is allowed to continue through the
        // normal Minecraft screen handler, so it is the user's input that closes the menu.
        state = State.OPEN;
        delayCounter = 0;
    }

    @EventHandler
    private void onKey(KeyInputEvent event) {
        if (event.action != KeyAction.Press || event.key() != GLFW.GLFW_KEY_ESCAPE) return;

        if (notifications.get()) info("Ultra Sell stopped after %d stack move(s).", sessionMoves + batchMoves);
        toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        switch (state) {
            case OPEN -> openSell();
            case WAIT_FOR_MENU -> waitForMenu();
            case FILL -> fillMenu();
            case CONFIRM -> confirmSale();
            case WAIT_FOR_RESULT -> waitForResult();
            case CONFIRM_DIALOG -> confirmDialog();
        }
    }

    private void openSell() {
        baselineContainerId = currentContainerId();
        GlazedSell.openSell();
        waited = 0;
        delayCounter = randomDelay(screenDelay);
        state = State.WAIT_FOR_MENU;
    }

    private void waitForMenu() {
        ChestMenu menu = GlazedSell.container();

        // Do not mistake a chest that was already open when the module started for /sell.
        if (menu != null && (baselineContainerId < 0 || menu.containerId != baselineContainerId)) {
            activeContainerId = menu.containerId;
            clickedContainerId = -1;
            failedMoves = 0;
            batchMoves = 0;
            waited = 0;
            state = State.FILL;
            return;
        }

        if (++waited < menuTimeout.get()) return;

        if (notifications.get()) warning("The /sell menu did not open; retrying.");
        scheduleOpen();
    }

    private void fillMenu() {
        ChestMenu menu = GlazedSell.container();
        if (menu == null) {
            scheduleOpen();
            return;
        }

        activeContainerId = menu.containerId;

        if (GlazedSell.firstEmptyUsableSlot(menu) < 0) {
            beginConfirm();
            return;
        }

        int source = randomPlayerStack(menu);
        if (source < 0) {
            if (batchMoves > 0) beginConfirm();
            else delayCounter = randomDelay(idleDelay);
            return;
        }

        ItemStack before = menu.getSlot(source).getItem().copy();
        mc.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);

        if (ItemStack.matches(before, menu.getSlot(source).getItem())) {
            failedMoves++;

            // If the server refuses every currently occupied inventory slot, do not click in a
            // tight loop. Confirm anything already accepted, otherwise wait for a later update.
            if (failedMoves >= countPlayerStacks(menu)) {
                failedMoves = 0;
                if (batchMoves > 0) beginConfirm();
                else delayCounter = randomDelay(idleDelay);
                return;
            }
        } else {
            failedMoves = 0;
            batchMoves++;
        }

        delayCounter = humanClickDelay(itemDelay);
    }

    private void beginConfirm() {
        if (batchMoves <= 0) {
            delayCounter = randomDelay(idleDelay);
            return;
        }

        waited = 0;
        delayCounter = humanClickDelay(confirmDelay);
        state = State.CONFIRM;
    }

    private void confirmSale() {
        if (GlazedSell.isDialogOpen()) {
            state = State.CONFIRM_DIALOG;
            delayCounter = humanClickDelay(confirmDelay);
            return;
        }

        ChestMenu menu = GlazedSell.container();
        if (menu == null) {
            finishClosedBatch();
            return;
        }

        int button = findGreenButton(menu);
        if (button >= 0) {
            clickedContainerId = menu.containerId;
            mc.gameMode.handleContainerInput(menu.containerId, button, 0, ContainerInput.PICKUP, mc.player);
            waited = 0;
            delayCounter = randomDelay(screenDelay);
            state = State.WAIT_FOR_RESULT;
            return;
        }

        if (++waited < menuTimeout.get()) return;

        if (notifications.get()) warning("No green sell button appeared; waiting before another attempt.");
        waited = 0;
        delayCounter = randomDelay(idleDelay);
        state = State.FILL;
    }

    private void waitForResult() {
        if (GlazedSell.isDialogOpen()) {
            waited = 0;
            delayCounter = humanClickDelay(confirmDelay);
            state = State.CONFIRM_DIALOG;
            return;
        }

        ChestMenu menu = GlazedSell.container();
        if (menu == null) {
            finishClosedBatch();
            return;
        }

        activeContainerId = menu.containerId;

        // Some server versions replace the first sell screen with another green confirmation
        // inventory. Handle that before the empty-area check because a confirmation inventory
        // can legitimately have no deposited items of its own.
        if (menu.containerId != clickedContainerId && findGreenButton(menu) >= 0) {
            waited = 0;
            delayCounter = humanClickDelay(confirmDelay);
            state = State.CONFIRM;
            return;
        }

        // A fresh/cleared sell menu means the sale completed without closing the GUI.
        if (countDepositedStacks(menu) == 0) {
            finishOpenBatch();
            return;
        }

        if (++waited < menuTimeout.get()) return;

        if (notifications.get()) warning("The sell menu did not acknowledge the click; trying the green button again.");
        waited = 0;
        delayCounter = humanClickDelay(confirmDelay);
        state = State.CONFIRM;
    }

    private void confirmDialog() {
        if (GlazedSell.isDialogOpen()) {
            if (GlazedSell.clickDialogYes()) {
                waited = 0;
                delayCounter = randomDelay(screenDelay);
                state = State.WAIT_FOR_RESULT;
                return;
            }

            if (++waited < menuTimeout.get()) return;
        }

        // The dialog disappeared on its own, or its button could not be found. Let the normal
        // result handler decide whether the sell menu survived or needs to be reopened.
        waited = 0;
        delayCounter = randomDelay(screenDelay);
        state = State.WAIT_FOR_RESULT;
    }

    private void finishOpenBatch() {
        sessionMoves += batchMoves;
        if (notifications.get()) info("Sold %d stack(s); waiting in /sell for more items.", batchMoves);
        batchMoves = 0;
        failedMoves = 0;
        clickedContainerId = -1;
        waited = 0;
        delayCounter = randomDelay(idleDelay);
        state = State.FILL;
    }

    private void finishClosedBatch() {
        sessionMoves += batchMoves;
        if (notifications.get()) info("Sold %d stack(s); reopening /sell for incoming items.", batchMoves);
        batchMoves = 0;
        failedMoves = 0;
        clickedContainerId = -1;
        scheduleOpen();
    }

    private void scheduleOpen() {
        baselineContainerId = currentContainerId();
        activeContainerId = -1;
        waited = 0;
        delayCounter = randomDelay(screenDelay);
        state = State.OPEN;
    }

    private int randomPlayerStack(ChestMenu menu) {
        int from = Math.min(GlazedSell.containerSlots(menu), menu.slots.size());
        int occupied = countPlayerStacks(menu);
        if (occupied <= 0) return -1;

        int selected = random.nextInt(occupied);
        for (int slot = from; slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).getItem().isEmpty()) continue;
            if (selected-- == 0) return slot;
        }

        return -1;
    }

    private int countPlayerStacks(ChestMenu menu) {
        int count = 0;
        int from = Math.min(GlazedSell.containerSlots(menu), menu.slots.size());

        for (int slot = from; slot < menu.slots.size(); slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) count++;
        }

        return count;
    }

    private int countDepositedStacks(ChestMenu menu) {
        int count = 0;
        int end = Math.min(GlazedSell.usableSlots(menu), menu.slots.size());

        for (int slot = 0; slot < end; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) count++;
        }

        return count;
    }

    private int findGreenButton(ChestMenu menu) {
        int end = Math.min(GlazedSell.containerSlots(menu), menu.slots.size());
        int start = Math.min(GlazedSell.usableSlots(menu), end);

        for (int slot = end - 1; slot >= start; slot--) {
            if (GlazedSell.isConfirmButton(menu.getSlot(slot).getItem())) return slot;
        }

        return -1;
    }

    private int currentContainerId() {
        if (mc.player == null || mc.player.containerMenu == mc.player.inventoryMenu) return -1;
        return mc.player.containerMenu.containerId;
    }

    private int humanClickDelay(Setting<RandomBetweenInt> range) {
        int ticks = randomDelay(range);
        if (random.nextInt(100) < hesitationChance.get()) ticks += randomDelay(hesitationDelay);
        return ticks;
    }

    private int randomDelay(Setting<RandomBetweenInt> range) {
        return Math.max(1, range.get().getRandom());
    }

    private enum State {
        OPEN,
        WAIT_FOR_MENU,
        FILL,
        CONFIRM,
        WAIT_FOR_RESULT,
        CONFIRM_DIALOG
    }
}
