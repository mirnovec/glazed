package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedShop;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Smooth stone out of /orders, smooth stone slabs onto the floor.
 *
 * One cycle: open the orders menu, find the order whose icon is smooth stone (paging on if it is
 * not on the first page), open its storage chest, shift out only as many stacks as the inventory
 * can still hold once they have doubled into slabs, then craft them on the crafting table you are
 * looking at and throw the slabs away.
 *
 * The recipe is shaped: three smooth stone in the top row for six slabs, so the grid has to hold
 * that row and nothing else. One stack of smooth stone is two stacks of slabs, which is the whole
 * reason the take step is capped rather than greedy.
 *
 * Everything it sends is a packet a vanilla client sends for the same action, in the same order:
 * a chat command, container clicks through {@code handleContainerInput} with their real state ids
 * and predicted slot changes, a use-on-block with the crosshair's own hit result, a swing only
 * when vanilla would have swung, and a close. It never clicks a slot while the matching screen is
 * shut, never sends two clicks in one tick, never interacts out of reach or while sneaking, and
 * every delay is randomised so the rhythm is not a fingerprint.
 *
 * Speed comes from waiting on answers rather than sleeping through them: every step that depends
 * on the server polls once a tick and moves on the moment the answer lands, and the steps the
 * client works out for itself, loading the grid above all, never wait at all. What is left is
 * click pacing, which is deliberately still there, jittered, and broken up by a pause every forty
 * to a hundred clicks.
 */
public class SmoothSlabCrafter extends Module {
    private static final int RESULT_SLOT = 0;
    private static final int GRID_FIRST = 1;
    private static final int GRID_LAST = 9;
    private static final int ROW_LAST = 3;   // the slab recipe wants three smooth stone in the top row
    private static final int INV_FIRST = 10;
    private static final int MENU_SLOTS = 46;

    private static final int STACK = 64;
    private static final int PER_CRAFT_IN = 3;
    private static final int PER_CRAFT_OUT = 6;
    private static final int ROW_OUTPUT = STACK * PER_CRAFT_OUT;   // a full row is 384 slabs
    private static final int RESERVE_SLOTS = 2;                    // working room the take step never eats

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOrders = settings.createGroup("Orders menu");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Boolean> fetchStone = sgGeneral.add(new BoolSetting.Builder()
        .name("fetch-stone")
        .description("Walk the /orders menus for smooth stone. Off crafts what is already in your inventory and nothing else.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxStacks = sgGeneral.add(new IntSetting.Builder()
        .name("max-stacks")
        .description("Hard ceiling on stacks of smooth stone per trip. 0 takes as much as the inventory can hold once it has turned into slabs.")
        .defaultValue(0)
        .min(0)
        .max(36)
        .sliderMax(18)
        .build()
    );

    private final Setting<Boolean> dropSlabs = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-slabs")
        .description("Throw the slabs on the floor, the same as holding Q over them. Also lets a trip take twice as much stone, because the slabs are flushed as they pile up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireTableLook = sgGeneral.add(new BoolSetting.Builder()
        .name("require-table-look")
        .description("Only start a cycle while your crosshair is on a crafting table, which is how you pause it, and which means the rotation the server sees is your own.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> faceTable = sgGeneral.add(new BoolSetting.Builder()
        .name("face-table")
        .description("With require-table-look off, turn to look at the table before opening it so the server never sees a click at a block you were not facing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tableRange = sgGeneral.add(new IntSetting.Builder()
        .name("table-range")
        .description("How far to look for a crafting table when the crosshair is not on one.")
        .defaultValue(4)
        .min(1)
        .max(6)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> ordersCommand = sgOrders.add(new StringSetting.Builder()
        .name("orders-command")
        .description("Command that opens the orders menu.")
        .defaultValue("/orders")
        .build()
    );

    private final Setting<Integer> ordersSlotFromEnd = sgOrders.add(new IntSetting.Builder()
        .name("orders-slot-from-end")
        .description("Which chest to click in the orders menu, counted back from the last slot. 3 is the third from the end.")
        .defaultValue(3)
        .min(1)
        .max(54)
        .sliderMax(9)
        .build()
    );

    private final Setting<Integer> storageSlot = sgOrders.add(new IntSetting.Builder()
        .name("storage-slot")
        .description("Slot of the chest inside the order. If it is not a chest the module looks for the one nearest the middle instead.")
        .defaultValue(13)
        .min(0)
        .max(53)
        .sliderMax(53)
        .build()
    );

    private final Setting<Integer> nextPageSlot = sgOrders.add(new IntSetting.Builder()
        .name("next-page-slot")
        .description("Slot of the next page arrow in the order list. Only clicked when there really is an arrow in it.")
        .defaultValue(53)
        .min(0)
        .max(53)
        .sliderMax(53)
        .build()
    );

    private final Setting<Integer> maxPages = sgOrders.add(new IntSetting.Builder()
        .name("max-pages")
        .description("How many pages of the order list to search for smooth stone before giving up.")
        .defaultValue(5)
        .min(1)
        .max(20)
        .sliderMax(10)
        .build()
    );

    private final Setting<TakeMode> takeMode = sgOrders.add(new EnumSetting.Builder<TakeMode>()
        .name("take-mode")
        .description("How stone comes out of the order. Auto shift clicks and switches to a plain click if the server ignores that.")
        .defaultValue(TakeMode.Auto)
        .build()
    );

    private final Setting<Integer> menuTimeout = sgOrders.add(new IntSetting.Builder()
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

    private final Setting<Integer> menuDelay = sgTiming.add(new IntSetting.Builder()
        .name("menu-delay")
        .description("Ticks between clicks while walking the orders menus. Each menu is waited for by polling, so this is only the gap between clicks.")
        .defaultValue(4)
        .min(1)
        .max(60)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> takeDelayMin = sgTiming.add(new IntSetting.Builder()
        .name("take-delay-min")
        .description("Fastest gap between clicks while pulling stone out. Each click picks a fresh number between min and max.")
        .defaultValue(2)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> takeDelayMax = sgTiming.add(new IntSetting.Builder()
        .name("take-delay-max")
        .description("Slowest gap between clicks while pulling stone out. Keep it above the min or the rhythm is dead flat.")
        .defaultValue(5)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> dropDelayMin = sgTiming.add(new IntSetting.Builder()
        .name("drop-delay-min")
        .description("Fastest gap between dropped stacks. Holding Q repeats faster than this, so one tick is still under the vanilla rate.")
        .defaultValue(1)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> dropDelayMax = sgTiming.add(new IntSetting.Builder()
        .name("drop-delay-max")
        .description("Slowest gap between dropped stacks. Keep it above the min or the rhythm is dead flat.")
        .defaultValue(3)
        .min(1)
        .max(40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> craftDelay = sgTiming.add(new IntSetting.Builder()
        .name("craft-delay")
        .description("Ticks after a craft before the grid is loaded again. The result slot is waited for by polling, so this no longer has to cover a round trip.")
        .defaultValue(2)
        .min(1)
        .max(60)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> gridDelay = sgTiming.add(new IntSetting.Builder()
        .name("grid-delay")
        .description("Ticks between clicks while loading the crafting grid. The client works these moves out itself, so they never wait on the server; this is purely how fast you are willing to look like you click.")
        .defaultValue(2)
        .min(1)
        .max(20)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> settleTimeout = sgTiming.add(new IntSetting.Builder()
        .name("settle-timeout")
        .description("Ceiling on how long a click waits for the server to answer. It carries on the instant the answer lands, so raising this costs nothing and only buys patience on a laggy server.")
        .defaultValue(20)
        .min(4)
        .max(120)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> humanPauses = sgTiming.add(new BoolSetting.Builder()
        .name("human-pauses")
        .description("Slip a short pause in every forty to a hundred clicks. Nobody holds one steady rate for minutes, and a run that does is the easiest thing to pick out of a click log.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> screenDelay = sgTiming.add(new IntSetting.Builder()
        .name("screen-delay")
        .description("Ticks to wait after a menu opens or closes.")
        .defaultValue(3)
        .min(1)
        .max(60)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> craftWatchdog = sgTiming.add(new IntSetting.Builder()
        .name("craft-watchdog")
        .description("Ticks the crafting half is allowed to run before it gives up. A full inventory or a server that refuses a click cannot turn into an endless loop.")
        .defaultValue(3000)
        .min(200)
        .max(20000)
        .sliderMax(6000)
        .build()
    );

    private final Setting<Integer> cycleGap = sgTiming.add(new IntSetting.Builder()
        .name("cycle-gap")
        .description("Ticks between one batch finishing and the next trip starting.")
        .defaultValue(20)
        .min(5)
        .max(600)
        .sliderMax(300)
        .build()
    );

    private final Setting<Integer> idleBackoff = sgTiming.add(new IntSetting.Builder()
        .name("idle-backoff")
        .description("Ticks to wait before retrying when a cycle did no work, so an empty order or a full inventory cannot turn into command spam.")
        .defaultValue(300)
        .min(40)
        .max(6000)
        .sliderMax(1200)
        .build()
    );

    public enum TakeMode { Auto, ShiftClick, Click }

    private enum State {
        IDLE,
        ORDERS_SEND, ORDERS_WAIT, ORDERS_CLICK,
        LIST_WAIT, LIST_FIND, PAGE_WAIT,
        DETAIL_WAIT, DETAIL_CLICK,
        STORAGE_WAIT, TAKE, TAKE_SETTLE,
        ORDERS_CLOSE,
        TABLE_FACE, TABLE_OPEN, TABLE_WAIT,
        GRID_FILL, SPLIT_PUT, GRID_WAIT, GRID_CRAFT, CRAFT_SETTLE,
        DROP,
        TABLE_CLOSE,
        COOLDOWN
    }

    private final Random random = new Random();

    private State state = State.IDLE;
    private State dropReturn = State.TABLE_CLOSE;
    private int delayCounter = 0;
    private int waited = 0;
    private int stalled = 0;

    private int lastMenuId = Integer.MIN_VALUE;
    private long lastSignature = 0;

    private int targetStone = 0;
    private int grabbed = 0;
    private int stoneBefore = 0;
    private int takeSource = -1;
    private boolean plainClick = false;
    private int pagesSeen = 0;

    private BlockPos tablePos = null;

    private int craftStoneSnapshot = -1;
    private int craftSlabSnapshot = -1;
    private int splitTarget = -1;
    private int splitTries = 0;
    private int carrySource = -1;
    private int carryTries = 0;
    private int tidySlot = -1;
    private int tidyCount = -1;
    private int tidyTries = 0;
    private int craftTicks = 0;
    private int settleTicks = 0;
    private int idleNags = 0;

    private int actionsSinceBreak = 0;
    private int nextBreakAt = 0;

    private int dropSlot = -1;
    private int dropCount = -1;
    private int dropTries = 0;
    private int dropped = 0;

    private int madeSlabs = 0;

    public SmoothSlabCrafter() {
        super(GlazedAddon.CATEGORY, "smooth-slab-crafter", "Pulls smooth stone from /orders, crafts it into smooth stone slabs on the crafting table you are looking at, and drops them.");
    }

    @Override
    public void onActivate() {
        resetCycle();
        state = State.IDLE;
        delayCounter = 0;
        madeSlabs = 0;
        idleNags = 0;
    }

    @Override
    public void onDeactivate() {
        closeAnyMenu();
        state = State.IDLE;
    }

    private void resetCycle() {
        waited = 0;
        stalled = 0;
        grabbed = 0;
        targetStone = 0;
        stoneBefore = 0;
        takeSource = -1;
        plainClick = false;
        pagesSeen = 0;
        tablePos = null;
        craftStoneSnapshot = -1;
        craftSlabSnapshot = -1;
        splitTarget = -1;
        splitTries = 0;
        carrySource = -1;
        carryTries = 0;
        tidySlot = -1;
        tidyCount = -1;
        tidyTries = 0;
        craftTicks = 0;
        settleTicks = 0;
        actionsSinceBreak = 0;
        nextBreakAt = 40 + random.nextInt(60);
        dropSlot = -1;
        dropCount = -1;
        dropTries = 0;
        dropped = 0;
        dropReturn = State.TABLE_CLOSE;
        lastMenuId = Integer.MIN_VALUE;
        lastSignature = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // a table that never finishes is worse than one that gives up, so put a ceiling on it
        if (isCrafting() && ++craftTicks > craftWatchdog.get()) {
            if (notifications.get()) warning("Crafting is taking far too long, backing off.");
            endCycleBackoff();
            return;
        }

        switch (state) {
            case IDLE -> tickIdle();
            case ORDERS_SEND -> tickOrdersSend();
            case ORDERS_WAIT -> tickOrdersWait();
            case ORDERS_CLICK -> tickOrdersClick();
            case LIST_WAIT -> tickMenuWait(State.LIST_FIND, "order list");
            case LIST_FIND -> tickListFind();
            case PAGE_WAIT -> tickMenuWait(State.LIST_FIND, "next page");
            case DETAIL_WAIT -> tickMenuWait(State.DETAIL_CLICK, "order");
            case DETAIL_CLICK -> tickDetailClick();
            case STORAGE_WAIT -> tickMenuWait(State.TAKE, "order storage");
            case TAKE -> tickTake();
            case TAKE_SETTLE -> tickTakeSettle();
            case ORDERS_CLOSE -> tickOrdersClose();
            case TABLE_FACE -> tickTableFace();
            case TABLE_OPEN -> tickTableOpen();
            case TABLE_WAIT -> tickTableWait();
            case GRID_FILL -> tickGridFill();
            case SPLIT_PUT -> tickSplitPut();
            case GRID_WAIT -> tickGridWait();
            case GRID_CRAFT -> tickGridCraft();
            case CRAFT_SETTLE -> tickCraftSettle();
            case DROP -> tickDrop();
            case TABLE_CLOSE -> tickTableClose();
            case COOLDOWN -> {
                resetCycle();
                state = State.IDLE;
            }
        }
    }

    // ---------------------------------------------------------------- cycle start

    private void tickIdle() {
        BlockPos target = resolveTable(true);

        if (target == null) {
            // say so. a module that waits without a word is indistinguishable from a broken one
            announceWaiting();
            delayCounter = jitter(12, 4);
            return;
        }

        tablePos = target;
        idleNags = 0;

        int free = freeSlots();

        if (free < RESERVE_SLOTS + 2) {
            if (notifications.get()) warning("Not enough room to craft, waiting for space.");
            endCycleBackoff();
            return;
        }

        targetStone = stoneBudget(free);

        if (targetStone < PER_CRAFT_IN) {
            if (notifications.get()) warning("No room for a batch, waiting for space.");
            endCycleBackoff();
            return;
        }

        if (!fetchStone.get() || countStone() >= targetStone) {
            if (countStone() < PER_CRAFT_IN) {
                if (notifications.get()) info("No smooth stone to craft.");
                endCycleBackoff();
                return;
            }

            state = State.TABLE_FACE;
            return;
        }

        state = State.ORDERS_SEND;
    }

    /**
     * How much smooth stone this trip may end up holding, in items.
     *
     * Three stone are six slabs, so a stack in is two stacks out. With the slabs being dropped the
     * pile is flushed as it grows and only the stone itself has to fit; without dropping, every
     * stack taken has to leave a second free slot behind for its own output.
     */
    private int stoneBudget(int free) {
        int usable = free - RESERVE_SLOTS;
        if (usable < 1) return 0;

        int slots = dropSlabs.get() ? usable : usable / 2;
        if (slots < 1) return 0;

        if (maxStacks.get() > 0) slots = Math.min(slots, maxStacks.get());

        return slots * STACK;
    }

    /** The crafting table the crosshair is on, or null. */
    private BlockPos lookedAtTable() {
        if (!(mc.hitResult instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;

        return isTable(hit.getBlockPos()) ? hit.getBlockPos() : null;
    }

    /** Identity first: the block a server hands you is the real one whatever class it maps to. */
    private boolean isTable(BlockPos pos) {
        Block block = mc.level.getBlockState(pos).getBlock();
        return block == Blocks.CRAFTING_TABLE || block instanceof CraftingTableBlock;
    }

    /**
     * The table to work at. The crosshair wins, and starting a cycle needs it. Once a cycle is
     * under way the table has not moved, so a wobble mid run does not throw away the trip.
     */
    private BlockPos resolveTable(boolean starting) {
        BlockPos looked = lookedAtTable();
        if (looked != null) return looked;

        if (!starting && tablePos != null && isTable(tablePos)) return tablePos;
        if (starting && requireTableLook.get()) return null;

        return nearbyTable();
    }

    private BlockPos nearbyTable() {
        BlockPos origin = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int range = tableRange.get();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!isTable(pos)) continue;

                    double distance = pos.distSqr(origin);

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    /** Every few seconds, and named: knowing what the crosshair is on is the whole answer. */
    private void announceWaiting() {
        if (!notifications.get()) return;
        if (idleNags++ % 8 != 0) return;

        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            info("Waiting: your crosshair is on %s, not a crafting table.",
                mc.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString());
            return;
        }

        info("Waiting: point your crosshair at a crafting table.");
    }

    // ---------------------------------------------------------------- the orders menus

    private void tickOrdersSend() {
        markMenu();
        ChatUtils.sendPlayerMsg(ordersCommand.get());

        waited = 0;
        pagesSeen = 0;
        delayCounter = jitter(screenDelay.get(), 2);
        state = State.ORDERS_WAIT;
    }

    private void tickOrdersWait() {
        if (GlazedShop.openContainer() != null) {
            delayCounter = jitter(menuDelay.get(), 2);
            state = State.ORDERS_CLICK;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("Orders menu never opened, backing off.");
            endCycleBackoff();
        }
    }

    /** The chest near the end of the orders menu, the same one Order Dropper clicks. */
    private void tickOrdersClick() {
        ChestMenu menu = GlazedShop.openContainer();

        if (menu == null) {
            endCycleBackoff();
            return;
        }

        int total = containerSlots(menu);
        int wanted = total - ordersSlotFromEnd.get();
        int slot = isChestItem(itemAt(menu, wanted)) ? wanted : lastChestSlot(menu, total);

        if (slot < 0) {
            if (notifications.get()) warning("No chest in the orders menu, backing off.");
            endCycleBackoff();
            return;
        }

        clickMenu(menu, slot, State.LIST_WAIT);
    }

    /**
     * Find the order, do not assume it. The list shows every order as its own item, so smooth
     * stone is looked up by identity and the page is turned when it is not on this one.
     */
    private void tickListFind() {
        ChestMenu menu = GlazedShop.openContainer();

        if (menu == null) {
            endCycleBackoff();
            return;
        }

        int slot = GlazedShop.findSlot(menu, stack -> stack.is(Items.SMOOTH_STONE));

        if (slot >= 0) {
            clickMenu(menu, slot, State.DETAIL_WAIT);
            return;
        }

        pagesSeen++;

        int arrow = nextPageSlot.get();

        if (pagesSeen < maxPages.get() && arrow < containerSlots(menu) && itemAt(menu, arrow).is(Items.ARROW)) {
            if (notifications.get() && pagesSeen == 1) info("No smooth stone on this page, turning it.");
            clickMenu(menu, arrow, State.PAGE_WAIT);
            return;
        }

        if (notifications.get()) warning("No smooth stone order found in %d page(s), backing off.", pagesSeen);
        endCycleBackoff();
    }

    private void tickDetailClick() {
        ChestMenu menu = GlazedShop.openContainer();

        if (menu == null) {
            endCycleBackoff();
            return;
        }

        int slot = chestNearMiddle(menu);

        if (slot < 0) {
            if (notifications.get()) warning("No chest to open in that order, backing off.");
            endCycleBackoff();
            return;
        }

        clickMenu(menu, slot, State.STORAGE_WAIT);
    }

    /** Shared wait: a menu counts as new when the id changes, or when the contents do. */
    private void tickMenuWait(State next, String what) {
        ChestMenu menu = GlazedShop.openContainer();

        if (menu != null && isNewMenu(menu)) {
            waited = 0;
            stalled = 0;
            delayCounter = jitter(menuDelay.get(), 2);
            state = next;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("The %s never opened, backing off.", what);
            endCycleBackoff();
        }
    }

    // ---------------------------------------------------------------- taking the stone

    private void tickTake() {
        ChestMenu menu = GlazedShop.openContainer();

        if (menu == null) {
            if (grabbed > 0) {
                state = State.TABLE_FACE;
                return;
            }

            if (notifications.get()) warning("Order storage closed early, backing off.");
            endCycleBackoff();
            return;
        }

        // the two ways this trip can be full: the budget is spent, or the slots simply ran out
        if (countStone() >= targetStone || freeSlots() <= RESERVE_SLOTS) {
            state = State.ORDERS_CLOSE;
            return;
        }

        int source = findStone(menu, 0, containerSlots(menu));

        if (source < 0) {
            if (notifications.get() && grabbed == 0) info("No smooth stone left in that order.");
            state = State.ORDERS_CLOSE;
            return;
        }

        stoneBefore = countStone();
        takeSource = source;

        boolean plain = takeMode.get() == TakeMode.Click || (takeMode.get() == TakeMode.Auto && plainClick);

        if (!click(menu, source, 0, plain ? ContainerInput.PICKUP : ContainerInput.QUICK_MOVE)) {
            state = State.ORDERS_CLOSE;
            return;
        }

        settleTicks = 0;
        state = State.TAKE_SETTLE;
    }

    /**
     * Server menus answer clicks a tick or two later and resync whatever they refuse, so what
     * landed in the inventory is the only honest measure of whether that click did anything.
     */
    private void tickTakeSettle() {
        ChestMenu menu = GlazedShop.openContainer();

        // a plain click leaves the stack on the cursor. put it straight back where it came from
        if (menu != null && !menu.getCarried().isEmpty() && takeSource >= 0) {
            click(menu, takeSource, 0, ContainerInput.PICKUP);
            settleTicks = 0;
            delayCounter = takeDelay();
            state = State.TAKE;
            return;
        }

        // carry on the tick the stack lands rather than sleeping through an answer already given
        if (countStone() > stoneBefore) {
            grabbed++;
            stalled = 0;
            settleTicks = 0;
            delayCounter = takeDelay();
            state = State.TAKE;
            return;
        }

        if (++settleTicks < settleTimeout.get()) return;

        settleTicks = 0;
        stalled++;
        delayCounter = takeDelay();

        // the server ignored a shift click twice, so try it the other way before giving up
        if (takeMode.get() == TakeMode.Auto && !plainClick && stalled >= 2) {
            plainClick = true;
            stalled = 0;
            if (notifications.get()) info("Shift clicking did nothing, trying a plain click.");
            state = State.TAKE;
            return;
        }

        if (stalled >= 3) {
            if (notifications.get()) warning("Smooth stone is not coming out of the order, stopping the pull.");
            state = State.ORDERS_CLOSE;
            return;
        }

        state = State.TAKE;
    }

    private void tickOrdersClose() {
        ChestMenu menu = GlazedShop.openContainer();

        // a menu closed on a held stack throws it on the floor, so hand it back first
        if (menu != null && !menu.getCarried().isEmpty() && takeSource >= 0) {
            click(menu, takeSource, 0, ContainerInput.PICKUP);
            takeSource = -1;
            delayCounter = takeDelay();
            return;
        }

        closeAnyMenu();
        delayCounter = jitter(screenDelay.get(), 2);

        if (countStone() < PER_CRAFT_IN) {
            // nothing came out, do not come straight back and re-run the command
            endCycleBackoff();
            return;
        }

        if (notifications.get()) info("Pulled %d stack(s) of smooth stone, crafting.", grabbed);
        state = State.TABLE_FACE;
    }

    // ---------------------------------------------------------------- the crafting table

    /**
     * Look at the table before touching it. With require-table-look on the rotation is already
     * yours and nothing is sent; otherwise this turns the player for real, because a use-on-block
     * aimed at a block the server never saw you facing is the easiest thing in the world to flag.
     */
    private void tickTableFace() {
        tablePos = resolveTable(false);

        if (tablePos == null) {
            if (notifications.get()) warning("No crafting table to work at any more, backing off.");
            endCycleBackoff();
            return;
        }

        if (lookedAtTable() != null || !faceTable.get()) {
            state = State.TABLE_OPEN;
            return;
        }

        Rotations.rotate(Rotations.getYaw(tablePos), Rotations.getPitch(tablePos), 100);
        delayCounter = jitter(5, 3);
        state = State.TABLE_OPEN;
    }

    private void tickTableOpen() {
        tablePos = resolveTable(false);

        if (tablePos == null) {
            if (notifications.get()) warning("No crafting table to work at any more, backing off.");
            endCycleBackoff();
            return;
        }

        // a sneaking right click places the held block instead of opening the table
        if (mc.player.isShiftKeyDown()) {
            if (notifications.get()) info("Waiting: let go of sneak, a sneaking right click would place a block.");
            delayCounter = jitter(20, 5);
            return;
        }

        if (!mc.player.isWithinBlockInteractionRange(tablePos, 1.0)) {
            if (notifications.get()) warning("The crafting table is out of reach, backing off.");
            endCycleBackoff();
            return;
        }

        // the real hit when the crosshair is still on it, otherwise aim at the middle of the block
        BlockHitResult hit = mc.hitResult instanceof BlockHitResult looked
            && looked.getType() == HitResult.Type.BLOCK
            && looked.getBlockPos().equals(tablePos)
                ? looked
                : new BlockHitResult(Vec3.atCenterOf(tablePos), Direction.UP, tablePos, false);

        // the same call a right click makes, and the swing only where vanilla would have swung
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);

        if (result instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        waited = 0;
        stalled = 0;
        craftStoneSnapshot = -1;
        craftSlabSnapshot = -1;
        splitTarget = -1;
        splitTries = 0;
        carryTries = 0;
        tidyTries = 0;
        craftTicks = 0;
        dropped = 0;
        dropSlot = -1;
        dropCount = -1;
        dropTries = 0;
        dropReturn = State.TABLE_CLOSE;
        delayCounter = jitter(screenDelay.get(), 2);
        state = State.TABLE_WAIT;
    }

    private void tickTableWait() {
        if (craftingMenu() != null) {
            stalled = 0;
            state = State.GRID_FILL;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("Crafting table never opened, backing off.");
            endCycleBackoff();
        }
    }

    private boolean isCrafting() {
        return switch (state) {
            case GRID_FILL, SPLIT_PUT, GRID_WAIT, GRID_CRAFT, CRAFT_SETTLE, DROP, TABLE_CLOSE -> true;
            default -> false;
        };
    }

    private CraftingMenu craftingMenu() {
        if (!(mc.player.containerMenu instanceof CraftingMenu menu)) return null;
        return menu.slots.size() >= MENU_SLOTS ? menu : null;
    }

    /**
     * Loads the top row and only the top row. The recipe is shaped, so a fourth stack anywhere in
     * the grid stops it matching, and the tidy pass is what keeps that from happening.
     */
    private void tickGridFill() {
        CraftingMenu menu = craftingMenu();

        if (menu == null) {
            endCraftEarly();
            return;
        }

        if (putCarriedDown(menu)) return;

        if (stalled >= 3) {
            if (notifications.get()) warning("Slabs stopped coming out, closing up.");
            state = finishState();
            return;
        }

        // grid holds nothing but smooth stone, and only in the row
        if (tidyGrid(menu, ROW_LAST, stack -> stack.is(Items.SMOOTH_STONE))) return;

        // flush before the pile can reach the brim, never after
        if (dropSlabs.get() && slabRoom() < ROW_OUTPUT && countSlabs() > 0) {
            dropReturn = State.GRID_FILL;
            state = State.DROP;
            return;
        }

        if (rowFilled(menu)) {
            waited = 0;
            state = State.GRID_WAIT;
            return;
        }

        int source = findStone(menu, INV_FIRST, MENU_SLOTS);

        if (source >= 0) {
            // from the inventory a shift click lands in the grid, nowhere else
            if (!click(menu, source, 0, ContainerInput.QUICK_MOVE)) {
                endCraftEarly();
                return;
            }

            delayCounter = gridDelay();
            return;
        }

        // nothing left to shift in, so halve what is already there into the empty slot
        if (beginSplit(menu)) return;

        // fewer than three left, this batch is done
        state = finishState();
    }

    /**
     * Second half of a split. Right click took half a stack last tick, this puts it down, which is
     * two clicks a couple of hundred milliseconds apart exactly like dragging items by hand.
     */
    private void tickSplitPut() {
        CraftingMenu menu = craftingMenu();

        if (menu == null) {
            endCraftEarly();
            return;
        }

        if (menu.getCarried().isEmpty()) {
            // the server refused the pick up, so there is nothing to place
            state = State.GRID_FILL;
            return;
        }

        if (!click(menu, splitTarget, 0, ContainerInput.PICKUP)) {
            endCraftEarly();
            return;
        }

        carrySource = -1;
        splitTarget = -1;
        delayCounter = gridDelay();
        state = State.GRID_FILL;
    }

    private void tickGridWait() {
        CraftingMenu menu = craftingMenu();

        if (menu == null) {
            endCraftEarly();
            return;
        }

        if (!itemAt(menu, RESULT_SLOT).isEmpty()) {
            state = State.GRID_CRAFT;
            return;
        }

        if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("The server is not offering a slab recipe for that row, closing up.");
            state = finishState();
        }
    }

    private void tickGridCraft() {
        CraftingMenu menu = craftingMenu();

        if (menu == null) {
            endCraftEarly();
            return;
        }

        // one shift click on the result repeats the craft until the row runs out or you fill up
        int crafts = Math.min(ROW_OUTPUT, craftsAvailable(menu) * PER_CRAFT_OUT);

        if (dropSlabs.get() && slabRoom() < crafts && countSlabs() > 0) {
            dropReturn = State.GRID_CRAFT;
            state = State.DROP;
            return;
        }

        craftStoneSnapshot = countStone();
        craftSlabSnapshot = countSlabs();

        if (!click(menu, RESULT_SLOT, 0, ContainerInput.QUICK_MOVE)) {
            endCraftEarly();
            return;
        }

        settleTicks = 0;
        state = State.CRAFT_SETTLE;
    }

    /**
     * Did the shift click on the result actually consume anything? A full inventory is the usual
     * reason it did not, and without this check the module would happily click forever.
     *
     * This polls for the answer rather than sleeping a fixed number of ticks over it. The client
     * works the whole repeat out itself, so the usual case resolves on the very next tick, and the
     * timeout only ever bites on a server that really did refuse the click.
     */
    private void tickCraftSettle() {
        CraftingMenu menu = craftingMenu();

        if (menu == null) {
            endCraftEarly();
            return;
        }

        int stoneNow = countStone();
        int slabsNow = countSlabs();

        if (stoneNow < craftStoneSnapshot || slabsNow > craftSlabSnapshot) {
            // one shift click is many crafts, so count what arrived, not the stack size
            if (slabsNow > craftSlabSnapshot) madeSlabs += slabsNow - craftSlabSnapshot;

            stalled = 0;
            splitTries = 0;
            settleTicks = 0;
            craftStoneSnapshot = -1;
            craftSlabSnapshot = -1;
            delayCounter = jitter(craftDelay.get(), 1) + humanBreak();
            state = State.GRID_FILL;
            return;
        }

        if (++settleTicks < settleTimeout.get()) return;

        settleTicks = 0;
        craftStoneSnapshot = -1;
        craftSlabSnapshot = -1;
        stalled++;

        if (notifications.get() && stalled == 1) info("No stone was used that time, retrying.");

        delayCounter = gridDelay();
        state = State.GRID_FILL;
    }

    /**
     * Throws the slabs on the floor, a stack a click. In an open menu that is what Q on a slot
     * does, so it goes out as one throw packet rather than a swap into the hotbar.
     */
    private void tickDrop() {
        CraftingMenu menu = craftingMenu();

        if (menu == null) {
            endCraftEarly();
            return;
        }

        if (putCarriedDown(menu)) return;

        int slot = findItem(menu, INV_FIRST, MENU_SLOTS, stack -> stack.is(Items.SMOOTH_STONE_SLAB));

        if (slot < 0) {
            if (notifications.get() && dropped > 0 && dropReturn == State.TABLE_CLOSE) {
                info("Dropped %d stack(s) of slabs.", dropped);
            }

            state = dropReturn;
            dropReturn = State.TABLE_CLOSE;
            return;
        }

        ItemStack stack = itemAt(menu, slot);

        // same slot, same count, over and over means the server is refusing it
        if (slot == dropSlot && stack.getCount() == dropCount) {
            if (++dropTries >= 5) {
                if (notifications.get()) warning("Slabs will not drop, closing the table.");
                state = State.TABLE_CLOSE;
                dropReturn = State.TABLE_CLOSE;
                return;
            }
        } else {
            dropSlot = slot;
            dropCount = stack.getCount();
            dropTries = 0;
            dropped++;
        }

        // button 1 throws the whole stack, button 0 would be one item at a time
        if (!click(menu, slot, 1, ContainerInput.THROW)) {
            endCraftEarly();
            return;
        }

        delayCounter = dropDelay();
    }

    private State finishState() {
        if (!dropSlabs.get()) return State.TABLE_CLOSE;

        dropReturn = State.TABLE_CLOSE;
        return State.DROP;
    }

    private void tickTableClose() {
        CraftingMenu menu = craftingMenu();

        // never close on a held stack or a loaded grid, that is how items end up on the floor
        if (menu != null) {
            if (putCarriedDown(menu)) return;
            if (tidyGrid(menu, 0, stack -> false)) return;
        }

        closeAnyMenu();

        if (notifications.get()) info("Crafted %d smooth stone slab(s) this session.", madeSlabs);

        endCycle();
    }

    private void endCraftEarly() {
        if (notifications.get()) warning("Crafting table closed early, backing off.");
        endCycleBackoff();
    }

    // ---------------------------------------------------------------- crafting helpers

    /** How many times the row could be crafted right now, which is its emptiest slot. */
    private int craftsAvailable(CraftingMenu menu) {
        int least = Integer.MAX_VALUE;

        for (int slot = GRID_FIRST; slot <= ROW_LAST; slot++) {
            ItemStack stack = itemAt(menu, slot);
            if (stack.isEmpty()) return 0;
            least = Math.min(least, stack.getCount());
        }

        return least == Integer.MAX_VALUE ? 0 : least;
    }

    /**
     * Returns items the grid should not be holding, one stack a tick. True means it did something
     * and the caller should let the next tick pick up where this left off.
     *
     * @param keepUntil grid slots up to and including this may keep matching stacks
     */
    private boolean tidyGrid(CraftingMenu menu, int keepUntil, java.util.function.Predicate<ItemStack> keep) {
        for (int slot = GRID_FIRST; slot <= GRID_LAST; slot++) {
            ItemStack stack = itemAt(menu, slot);

            if (stack.isEmpty()) continue;
            if (slot <= keepUntil && keep.test(stack)) continue;

            // same slot, same count, over and over means the server is refusing it
            if (slot == tidySlot && stack.getCount() == tidyCount) {
                if (++tidyTries >= 5) {
                    if (notifications.get()) warning("The grid will not empty, closing the table.");
                    endCycleBackoff();
                    return true;
                }
            } else {
                tidySlot = slot;
                tidyCount = stack.getCount();
                tidyTries = 0;
            }

            if (!click(menu, slot, 0, ContainerInput.QUICK_MOVE)) {
                endCraftEarly();
                return true;
            }

            delayCounter = gridDelay();
            return true;
        }

        tidySlot = -1;
        tidyCount = -1;
        tidyTries = 0;

        return false;
    }

    /** All three row slots loaded, which is what the slab recipe wants. */
    private boolean rowFilled(CraftingMenu menu) {
        for (int slot = GRID_FIRST; slot <= ROW_LAST; slot++) {
            if (itemAt(menu, slot).isEmpty()) return false;
        }

        return true;
    }

    /**
     * Right click takes half a stack so the tail of a batch can still fill a row instead of being
     * stranded one slot short. The place half lands next tick, in {@link #tickSplitPut()}.
     */
    private boolean beginSplit(CraftingMenu menu) {
        if (!menu.getCarried().isEmpty()) return false;

        if (++splitTries > 8) {
            if (notifications.get()) warning("Cannot fill a row out of what is left, closing up.");
            return false;
        }

        int empty = -1;
        int fullest = -1;
        int most = 1;

        for (int slot = GRID_FIRST; slot <= ROW_LAST; slot++) {
            ItemStack stack = itemAt(menu, slot);

            if (stack.isEmpty()) {
                if (empty < 0) empty = slot;
                continue;
            }

            if (stack.getCount() > most) {
                most = stack.getCount();
                fullest = slot;
            }
        }

        if (empty < 0 || fullest < 0) return false;

        if (!click(menu, fullest, 1, ContainerInput.PICKUP)) {
            endCraftEarly();
            return true;
        }

        carrySource = fullest;
        splitTarget = empty;
        delayCounter = gridDelay();
        state = State.SPLIT_PUT;

        return true;
    }

    /** Safety net: anything left on the cursor goes back where it came from before we move on. */
    private boolean putCarriedDown(CraftingMenu menu) {
        if (menu.getCarried().isEmpty()) {
            carrySource = -1;
            carryTries = 0;
            return false;
        }

        if (++carryTries > 3) {
            if (notifications.get()) warning("Could not put a held stack down, closing the table.");
            endCycleBackoff();
            return true;
        }

        int target = carrySource >= 0 ? carrySource : firstEmptySlot(menu, INV_FIRST, MENU_SLOTS);
        if (target < 0) target = GRID_FIRST;

        if (!click(menu, target, 0, ContainerInput.PICKUP)) {
            endCraftEarly();
            return true;
        }

        carrySource = -1;
        delayCounter = gridDelay();

        return true;
    }

    // ---------------------------------------------------------------- menu helpers

    /**
     * Every click goes through here. A vanilla client can only click a slot while that menu's own
     * screen is the one on screen, so this refuses anything else rather than sending a packet no
     * real client would have sent.
     */
    private boolean click(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        if (slot < 0 || slot >= menu.slots.size()) return false;
        if (mc.player.containerMenu != menu) return false;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() != menu) return false;

        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);

        return true;
    }

    private void clickMenu(ChestMenu menu, int slot, State next) {
        markMenu();

        if (!click(menu, slot, 0, ContainerInput.PICKUP)) {
            endCycleBackoff();
            return;
        }

        waited = 0;
        delayCounter = jitter(menuDelay.get(), 2);
        state = next;
    }

    /** Snapshot of the open menu, so the next state can tell when the server swapped it out. */
    private void markMenu() {
        ChestMenu menu = GlazedShop.openContainer();

        if (menu == null) {
            lastMenuId = Integer.MIN_VALUE;
            lastSignature = 0;
            return;
        }

        lastMenuId = menu.containerId;
        lastSignature = signature(menu);
    }

    /** Some menus swap for a fresh id, others repaint in place, so watch for either. */
    private boolean isNewMenu(ChestMenu menu) {
        if (menu.containerId != lastMenuId) return true;
        return signature(menu) != lastSignature;
    }

    private long signature(ChestMenu menu) {
        long hash = 1;
        int total = containerSlots(menu);

        for (int slot = 0; slot < total; slot++) {
            ItemStack stack = itemAt(menu, slot);
            hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode() * 31L + stack.getCount());
        }

        return hash;
    }

    private int containerSlots(ChestMenu menu) {
        return Math.min(GlazedShop.containerSlotCount(menu), menu.slots.size());
    }

    private ItemStack itemAt(AbstractContainerMenu menu, int slot) {
        if (slot < 0 || slot >= menu.slots.size()) return ItemStack.EMPTY;
        return menu.getSlot(slot).getItem();
    }

    private int lastChestSlot(ChestMenu menu, int total) {
        for (int slot = total - 1; slot >= 0; slot--) {
            if (isChestItem(itemAt(menu, slot))) return slot;
        }

        return -1;
    }

    /** The chest closest to the centre of the menu, unless the configured slot already is one. */
    private int chestNearMiddle(ChestMenu menu) {
        int total = containerSlots(menu);
        int wanted = storageSlot.get();

        if (wanted < total && isChestItem(itemAt(menu, wanted))) return wanted;

        int middle = total / 2;
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int slot = 0; slot < total; slot++) {
            if (!isChestItem(itemAt(menu, slot))) continue;

            int distance = Math.abs(slot - middle);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }

        return best;
    }

    private int findStone(AbstractContainerMenu menu, int from, int to) {
        return findItem(menu, from, to, stack -> stack.is(Items.SMOOTH_STONE));
    }

    private int findItem(AbstractContainerMenu menu, int from, int to, java.util.function.Predicate<ItemStack> test) {
        for (int slot = from; slot < to && slot < menu.slots.size(); slot++) {
            ItemStack stack = itemAt(menu, slot);
            if (!stack.isEmpty() && test.test(stack)) return slot;
        }

        return -1;
    }

    private int firstEmptySlot(AbstractContainerMenu menu, int from, int to) {
        for (int slot = from; slot < to && slot < menu.slots.size(); slot++) {
            if (itemAt(menu, slot).isEmpty()) return slot;
        }

        return -1;
    }

    private boolean isChestItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        return stack.is(Items.CHEST) || stack.is(Items.TRAPPED_CHEST)
            || stack.is(Items.ENDER_CHEST) || stack.is(Items.BARREL);
    }

    // ---------------------------------------------------------------- inventory maths

    private int countStone() {
        return countInInventory(stack -> stack.is(Items.SMOOTH_STONE));
    }

    private int countSlabs() {
        return countInInventory(stack -> stack.is(Items.SMOOTH_STONE_SLAB));
    }

    private int countInInventory(java.util.function.Predicate<ItemStack> test) {
        int total = 0;
        int size = mc.player.getInventory().getContainerSize();

        for (int slot = 0; slot < 36 && slot < size; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && test.test(stack)) total += stack.getCount();
        }

        return total;
    }

    private int freeSlots() {
        int free = 0;
        int size = mc.player.getInventory().getContainerSize();

        for (int slot = 0; slot < 36 && slot < size; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) free++;
        }

        return free;
    }

    /** Slabs the inventory could still take, counting the room left in the stacks it already has. */
    private int slabRoom() {
        int room = 0;
        int size = mc.player.getInventory().getContainerSize();

        for (int slot = 0; slot < 36 && slot < size; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);

            if (stack.isEmpty()) room += STACK;
            else if (stack.is(Items.SMOOTH_STONE_SLAB)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }

        return room;
    }

    // ---------------------------------------------------------------- timing

    /** Fresh number per click, so emptying an order has no fixed rhythm. */
    private int dropDelay() {
        return randomBetween(dropDelayMin.get(), dropDelayMax.get()) + humanBreak();
    }

    private int takeDelay() {
        return randomBetween(takeDelayMin.get(), takeDelayMax.get()) + humanBreak();
    }

    /** Loading the grid never waits on the server, so this is pure click pacing. */
    private int gridDelay() {
        return jitter(gridDelay.get(), 1) + humanBreak();
    }

    /**
     * Every so often, stop for a beat. Speed is fine, a rate held perfectly flat for minutes on
     * end is not: it is the one thing in a click log that no hand produces.
     */
    private int humanBreak() {
        if (!humanPauses.get()) return 0;
        if (++actionsSinceBreak < nextBreakAt) return 0;

        actionsSinceBreak = 0;
        nextBreakAt = 40 + random.nextInt(60);

        return 8 + random.nextInt(18);
    }

    private int randomBetween(int low, int high) {
        int min = Math.max(1, low);
        int max = Math.max(min, high);

        return min + random.nextInt(max - min + 1);
    }

    private void endCycle() {
        closeAnyMenu();
        delayCounter = jitter(cycleGap.get(), 5);
        state = State.COOLDOWN;
    }

    /** Cycle that did no work. Longer, still randomised, so a dry order does not spam commands. */
    private void endCycleBackoff() {
        closeAnyMenu();
        delayCounter = jitter(idleBackoff.get(), 40);
        state = State.COOLDOWN;
    }

    /** Close the way escape closes: the packet first, then the screen. */
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

    @Override
    public String getInfoString() {
        return state.toString().toLowerCase().replace("_", " ");
    }
}
