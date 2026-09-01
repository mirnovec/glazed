package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.utils.MarketUtils;
import com.nnpg.glazed.utils.MoneyFmt;
import com.nnpg.glazed.utils.RandomBetweenInt;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

public class AdvancedOrderSniper extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRefill = settings.createGroup("Refill");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("The inventory item to sell into matching orders.")
        .defaultValue(Items.DIAMOND)
        .build()
    );

    private final Setting<String> searchTerm = sgGeneral.add(new StringSetting.Builder()
        .name("search-term")
        .description("Text used by /orders and as an optional order name/tooltip filter. Blank derives the item name.")
        .defaultValue("diamond")
        .build()
    );

    private final Setting<String> commandOverride = sgGeneral.add(new StringSetting.Builder()
        .name("command-override")
        .description("Optional search command without a leading slash.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> threshold = sgGeneral.add(new StringSetting.Builder()
        .name("money-threshold")
        .description("Only fulfil the biggest order when it is at least this valuable. Supports K/M/B.")
        .defaultValue("1m")
        .build()
    );

    private final Setting<MarketUtils.PriceMode> priceMode = sgGeneral.add(new EnumSetting.Builder<MarketUtils.PriceMode>()
        .name("threshold-mode")
        .description("Compare the threshold against total payout or per-item price.")
        .defaultValue(MarketUtils.PriceMode.Total)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show sales, refill activity, and recovery messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RefillSource> refillSource = sgRefill.add(new EnumSetting.Builder<RefillSource>()
        .name("refill-source")
        .description("After inventory is sold: wait, take more from the looked-at chest, or withdraw from your saved order.")
        .defaultValue(RefillSource.None)
        .build()
    );

    private final Setting<Boolean> allowBarrels = sgRefill.add(new BoolSetting.Builder()
        .name("allow-barrels")
        .description("Allow a looked-at barrel as the Chest refill source.")
        .defaultValue(true)
        .visible(() -> refillSource.get() == RefillSource.Chest)
        .build()
    );

    private final Setting<Integer> ordersSlotFromEnd = sgRefill.add(new IntSetting.Builder()
        .name("orders-slot-from-end")
        .description("Saved-orders chest position counted backward from the end of /orders.")
        .defaultValue(3)
        .min(1)
        .max(54)
        .sliderMax(9)
        .visible(() -> refillSource.get() == RefillSource.ExistingOrder)
        .build()
    );

    private final Setting<Integer> sourceOrderSlot = sgRefill.add(new IntSetting.Builder()
        .name("source-order-slot")
        .description("Exact item-order slot, or -1 to find the selected item automatically.")
        .defaultValue(-1)
        .min(-1)
        .max(53)
        .sliderMin(-1)
        .sliderMax(53)
        .visible(() -> refillSource.get() == RefillSource.ExistingOrder)
        .build()
    );

    private final Setting<Integer> sourceStorageSlot = sgRefill.add(new IntSetting.Builder()
        .name("source-storage-slot")
        .description("Storage chest slot inside the selected saved order; falls back to the chest nearest the middle.")
        .defaultValue(13)
        .min(0)
        .max(53)
        .sliderMax(53)
        .visible(() -> refillSource.get() == RefillSource.ExistingOrder)
        .build()
    );

    private final Setting<Integer> menuTimeout = sgSafety.add(new IntSetting.Builder()
        .name("menu-timeout")
        .description("Ticks to wait for a menu transition before backing off.")
        .defaultValue(140)
        .min(20)
        .max(800)
        .sliderMax(300)
        .build()
    );

    private final Setting<Integer> verifyTimeout = sgSafety.add(new IntSetting.Builder()
        .name("sale-verify-timeout")
        .description("Ticks to wait after confirming an order sale.")
        .defaultValue(100)
        .min(20)
        .max(600)
        .sliderMax(240)
        .build()
    );

    private final Setting<Integer> maxStalledMoves = sgSafety.add(new IntSetting.Builder()
        .name("max-stalled-moves")
        .description("Failed inventory moves allowed before closing the transaction safely.")
        .defaultValue(3)
        .min(1)
        .max(20)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> rateLimitCooldownSeconds = sgSafety.add(new IntSetting.Builder()
        .name("rate-limit-cooldown")
        .description("Seconds to pause if the server asks for slower actions.")
        .defaultValue(120)
        .min(20)
        .max(1200)
        .sliderMax(300)
        .build()
    );

    private final Setting<String> unavailableRegex = sgSafety.add(new StringSetting.Builder()
        .name("unavailable-order-message")
        .description("Message shown when an order was filled or disappeared during the transaction.")
        .defaultValue("(already (been )?(filled|completed|fulfilled)|order.*(filled|complete|no longer|unavailable)|no longer exists|not found|cannot (fill|fulfil|fulfill|deliver))")
        .build()
    );

    private final Setting<RandomBetweenInt> menuDelay = timing("menu-delay-range", "Delay after opening or changing a menu.", 12, 24, 5, 500);
    private final Setting<RandomBetweenInt> actionDelay = timing("action-delay-range", "Delay between every inventory or confirmation click.", 10, 20, 5, 500);
    private final Setting<RandomBetweenInt> scanDelay = timing("scan-delay-range", "Delay between order searches when nothing qualifies.", 200, 340, 80, 6000);
    private final Setting<RandomBetweenInt> cycleDelay = timing("cycle-delay-range", "Delay after a sale or refill before searching again.", 140, 240, 40, 6000);
    private final Setting<RandomBetweenInt> errorBackoff = timing("error-backoff-range", "Delay after a failed or stale transaction.", 400, 700, 100, 12000);

    private final Random random = new Random();
    private State state = State.IDLE;
    private int delayCounter;
    private int waited;
    private int stalledMoves;
    private int countBeforeMove;
    private int inventoryBeforeTransaction;
    private int transferred;
    private int soldThisSession;
    private int lastMenuId = Integer.MIN_VALUE;
    private long lastSignature;
    private long selectedOrderValue;
    private boolean saleAcknowledged;
    private BlockPos refillPos;

    public AdvancedOrderSniper() {
        super(GlazedAddon.CATEGORY, "advanced-order-sniper", "Fulfils valuable orders and optionally refills from a chest or saved order.");
    }

    @Override
    public void onActivate() {
        if (targetItem.get() == null || targetItem.get() == Items.AIR) {
            error("Choose the exact item to sell.");
            toggle();
            return;
        }
        if (MarketUtils.parseConfiguredMoney(threshold.get()) <= 0) {
            error("Invalid order threshold: %s.", threshold.get());
            toggle();
            return;
        }

        refillPos = refillSource.get() == RefillSource.Chest ? lookedAtStorage() : null;
        if (refillSource.get() == RefillSource.Chest && refillPos == null) {
            error("Look directly at the refill chest or barrel before enabling Advanced Order Sniper.");
            toggle();
            return;
        }

        resetTransaction();
        soldThisSession = 0;
        delayCounter = random(20, 40);
        state = State.DECIDE;
    }

    @Override
    public void onDeactivate() {
        closeMenu();
        MarketUtils.releaseMenu(this);
        resetTransaction();
        state = State.IDLE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        switch (state) {
            case IDLE -> {}
            case DECIDE -> decide();
            case SCAN_SEND -> sendOrderSearch();
            case SCAN_WAIT -> waitForOrderResults();
            case SCAN -> scanAndSelect();
            case DEPOSIT_WAIT -> waitForDeposit();
            case DEPOSIT -> depositNext();
            case DEPOSIT_SETTLE -> settleDeposit();
            case CONFIRM_WAIT -> waitForConfirm();
            case VERIFY -> verifySale();
            case CHEST_OPEN -> openRefillChest();
            case CHEST_WAIT -> waitRefillChest();
            case CHEST_TAKE -> takeFromChest();
            case CHEST_SETTLE -> settleChestTake();
            case SOURCE_ORDER_SEND -> sendSavedOrders();
            case SOURCE_MAIN_WAIT -> waitNewMenu(State.SOURCE_MAIN_CLICK, "saved-orders menu");
            case SOURCE_MAIN_CLICK -> clickSavedOrders();
            case SOURCE_ITEM_WAIT -> waitNewMenu(State.SOURCE_ITEM_CLICK, "saved-order item menu");
            case SOURCE_ITEM_CLICK -> clickSavedItem();
            case SOURCE_STORAGE_WAIT -> waitNewMenu(State.SOURCE_STORAGE_CLICK, "saved-order storage menu");
            case SOURCE_STORAGE_CLICK -> clickSavedStorage();
            case SOURCE_ITEMS_WAIT -> waitNewMenu(State.SOURCE_TAKE, "saved-order contents");
            case SOURCE_TAKE -> takeFromSavedOrder();
            case SOURCE_TAKE_SETTLE -> settleSavedOrderTake();
            case COOLDOWN -> state = State.DECIDE;
        }
    }

    private void decide() {
        resetTransaction();
        if (!MarketUtils.acquireMenu(this)) {
            delayCounter = random(20, 40);
            return;
        }
        if (countTarget() > 0) {
            state = State.SCAN_SEND;
            return;
        }

        switch (refillSource.get()) {
            case Chest -> state = State.CHEST_OPEN;
            case ExistingOrder -> state = State.SOURCE_ORDER_SEND;
            case None -> {
                MarketUtils.releaseMenu(this);
                delayCounter = delay(scanDelay);
                state = State.COOLDOWN;
            }
        }
    }

    private void sendOrderSearch() {
        closeMenu();
        String override = MarketUtils.stripSlash(commandOverride.get());
        String query = MarketUtils.query(searchTerm.get(), targetItem.get());
        mc.getConnection().sendCommand(override.isBlank() ? (query.isBlank() ? "orders" : "orders " + query) : override);
        waited = 0;
        delayCounter = delay(menuDelay);
        state = State.SCAN_WAIT;
    }

    private void waitForOrderResults() {
        if (mc.player.containerMenu instanceof ChestMenu) {
            delayCounter = delay(menuDelay);
            state = State.SCAN;
        } else if (++waited >= menuTimeout.get()) {
            fail("Order results did not open.");
        }
    }

    private void scanAndSelect() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            fail("Order results closed before they could be scanned.");
            return;
        }

        long minimum = MarketUtils.parseConfiguredMoney(threshold.get());
        MarketUtils.Listing biggest = null;
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!MarketUtils.matches(stack, targetItem.get(), searchTerm.get())) continue;
            MarketUtils.Price price = MarketUtils.readPrice(stack);
            if (price == null || price.forMode(priceMode.get()) < minimum) continue;
            if (biggest == null || price.forMode(priceMode.get()) > biggest.price().forMode(priceMode.get())) {
                biggest = new MarketUtils.Listing(slot, stack.copy(), price);
            }
        }

        if (biggest == null) {
            closeMenu();
            MarketUtils.releaseMenu(this);
            delayCounter = delay(scanDelay);
            state = State.COOLDOWN;
            return;
        }

        inventoryBeforeTransaction = countTarget();
        selectedOrderValue = biggest.price().forMode(priceMode.get());
        transferred = 0;
        saleAcknowledged = false;
        markMenu(menu);
        mc.gameMode.handleContainerInput(menu.containerId, biggest.slot(), 0, ContainerInput.PICKUP, mc.player);
        waited = 0;
        delayCounter = delay(actionDelay);
        state = State.DEPOSIT_WAIT;
    }

    private void waitForDeposit() {
        if (GlazedSell.isDialogOpen()) {
            state = State.CONFIRM_WAIT;
            return;
        }
        if (mc.player.containerMenu instanceof ChestMenu menu && isNewMenu(menu)) {
            waited = 0;
            state = GlazedSell.isConfirmScreen(menu) ? State.CONFIRM_WAIT : State.DEPOSIT;
        } else if (++waited >= menuTimeout.get()) {
            fail("The order deposit menu did not open.");
        }
    }

    private void depositNext() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            waited = 0;
            state = State.CONFIRM_WAIT;
            return;
        }
        if (GlazedSell.isConfirmScreen(menu)) {
            state = State.CONFIRM_WAIT;
            return;
        }

        int source = findPlayerItem(menu);
        if (source < 0 || firstEmptyContainerSlot(menu) < 0) {
            closeMenu();
            waited = 0;
            delayCounter = delay(menuDelay);
            state = State.CONFIRM_WAIT;
            return;
        }

        countBeforeMove = countTarget();
        mc.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);
        delayCounter = delay(actionDelay);
        state = State.DEPOSIT_SETTLE;
    }

    private void settleDeposit() {
        int now = countTarget();
        if (now < countBeforeMove) {
            transferred += countBeforeMove - now;
            stalledMoves = 0;
            state = State.DEPOSIT;
            return;
        }
        if (++stalledMoves >= maxStalledMoves.get()) {
            if (transferred <= 0) {
                fail("Items would not move into the order.");
            } else {
                closeMenu();
                waited = 0;
                delayCounter = delay(menuDelay);
                state = State.CONFIRM_WAIT;
            }
        } else {
            state = State.DEPOSIT;
        }
    }

    private void waitForConfirm() {
        if (saleAcknowledged) {
            finishSale();
            return;
        }
        if (transferred <= 0 && countTarget() >= inventoryBeforeTransaction) {
            if (++waited >= menuTimeout.get()) fail("No items entered the selected order.");
            return;
        }

        if (GlazedSell.isDialogOpen() && GlazedSell.clickDialogYes()) {
            waited = 0;
            delayCounter = delay(actionDelay);
            state = State.VERIFY;
            return;
        }
        if (mc.player.containerMenu instanceof ChestMenu menu && GlazedSell.clickConfirm(menu)) {
            waited = 0;
            delayCounter = delay(actionDelay);
            state = State.VERIFY;
            return;
        }
        if (++waited >= menuTimeout.get()) fail("The order confirmation did not appear.");
    }

    private void verifySale() {
        if (saleAcknowledged || ++waited >= verifyTimeout.get()) finishSale();
    }

    private void finishSale() {
        int sold = Math.max(transferred, Math.max(0, inventoryBeforeTransaction - countTarget()));
        soldThisSession += sold;
        if (notifications.get()) {
            info("Filled order at %s with %d item(s); %d sold this session.", MoneyFmt.format(selectedOrderValue), sold, soldThisSession);
        }
        closeMenu();
        MarketUtils.releaseMenu(this);
        resetTransaction();
        delayCounter = delay(cycleDelay);
        state = State.COOLDOWN;
    }

    private void openRefillChest() {
        closeMenu();
        if (refillPos == null || !isStorage(refillPos)) {
            fail("The saved refill chest is missing or out of reach.");
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(refillPos), Direction.UP, refillPos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        waited = 0;
        delayCounter = delay(menuDelay);
        state = State.CHEST_WAIT;
    }

    private void waitRefillChest() {
        if (mc.player.containerMenu instanceof ChestMenu) {
            stalledMoves = 0;
            state = State.CHEST_TAKE;
        } else if (++waited >= menuTimeout.get()) {
            fail("The refill chest did not open.");
        }
    }

    private void takeFromChest() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            fail("The refill chest closed early.");
            return;
        }
        if (inventoryFull()) {
            finishRefill();
            return;
        }
        int source = findContainerItem(menu);
        if (source < 0) {
            finishRefill();
            return;
        }

        countBeforeMove = countTarget();
        mc.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);
        delayCounter = delay(actionDelay);
        state = State.CHEST_SETTLE;
    }

    private void settleChestTake() {
        if (countTarget() > countBeforeMove) {
            stalledMoves = 0;
            state = State.CHEST_TAKE;
        } else if (++stalledMoves >= maxStalledMoves.get()) {
            finishRefill();
        } else {
            state = State.CHEST_TAKE;
        }
    }

    private void sendSavedOrders() {
        closeMenu();
        markMenu(null);
        mc.getConnection().sendCommand("orders");
        waited = 0;
        delayCounter = delay(menuDelay);
        state = State.SOURCE_MAIN_WAIT;
    }

    private void clickSavedOrders() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            fail("Saved-orders menu closed early.");
            return;
        }
        int wanted = MarketUtils.containerSlots(menu) - ordersSlotFromEnd.get();
        int slot = isChest(menu, wanted) ? wanted : lastChest(menu);
        if (slot < 0) {
            fail("No saved-orders chest was found.");
            return;
        }
        clickForNext(menu, slot, State.SOURCE_ITEM_WAIT);
    }

    private void clickSavedItem() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            fail("Saved-order item menu closed early.");
            return;
        }
        int configured = sourceOrderSlot.get();
        int slot = configured >= 0 && configured < MarketUtils.containerSlots(menu) && !menu.getSlot(configured).getItem().isEmpty()
            ? configured : findContainerItem(menu);
        if (slot < 0) {
            fail("No saved order for %s was found.".formatted(targetItem.get().getDefaultInstance().getHoverName().getString()));
            return;
        }
        clickForNext(menu, slot, State.SOURCE_STORAGE_WAIT);
    }

    private void clickSavedStorage() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            fail("Saved-order storage menu closed early.");
            return;
        }
        int configured = sourceStorageSlot.get();
        int slot = isChest(menu, configured) ? configured : chestNearMiddle(menu);
        if (slot < 0) {
            fail("No storage chest was found for the saved order.");
            return;
        }
        clickForNext(menu, slot, State.SOURCE_ITEMS_WAIT);
    }

    private void takeFromSavedOrder() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            fail("Saved-order contents closed early.");
            return;
        }
        if (inventoryFull()) {
            finishRefill();
            return;
        }
        int source = findContainerItem(menu);
        if (source < 0) {
            finishRefill();
            return;
        }
        countBeforeMove = countTarget();
        mc.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);
        delayCounter = delay(actionDelay);
        state = State.SOURCE_TAKE_SETTLE;
    }

    private void settleSavedOrderTake() {
        if (countTarget() > countBeforeMove) {
            stalledMoves = 0;
            state = State.SOURCE_TAKE;
        } else if (++stalledMoves >= maxStalledMoves.get()) {
            finishRefill();
        } else {
            state = State.SOURCE_TAKE;
        }
    }

    private void finishRefill() {
        int count = countTarget();
        closeMenu();
        MarketUtils.releaseMenu(this);
        if (notifications.get()) info("Refill finished with %d target item(s) in inventory.", count);
        delayCounter = count > 0 ? delay(cycleDelay) : delay(errorBackoff);
        state = State.COOLDOWN;
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;
        String message = event.getMessage().getString();
        if (message == null || message.isBlank() || message.contains("[Meteor]")) return;
        String lower = message.toLowerCase(Locale.ROOT);

        if (inSaleTransaction() && (lower.contains("you sold") || lower.contains("you delivered") || lower.contains("order completed") || lower.contains("order fulfilled"))) {
            saleAcknowledged = true;
            return;
        }
        if (inSaleTransaction() && matches(unavailableRegex.get(), message, "already filled", "order completed", "no longer", "not found")) {
            fail("The selected order was filled by someone else.");
            return;
        }
        if (matches("(slow down|too fast|please wait|rate.?limit|cooldown|try again later)", message, "slow down", "too fast", "please wait")) {
            closeMenu();
            MarketUtils.releaseMenu(this);
            resetTransaction();
            delayCounter = rateLimitCooldownSeconds.get() * 20 + random(0, 100);
            state = State.COOLDOWN;
            if (notifications.get()) warning("Server requested slower actions; pausing for about %d seconds.", rateLimitCooldownSeconds.get());
        }
    }

    private boolean inSaleTransaction() {
        return state == State.DEPOSIT_WAIT || state == State.DEPOSIT || state == State.DEPOSIT_SETTLE
            || state == State.CONFIRM_WAIT || state == State.VERIFY;
    }

    private boolean matches(String regex, String message, String... fallback) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(message).find();
        } catch (Exception ignored) {
            String lower = message.toLowerCase(Locale.ROOT);
            for (String value : fallback) if (lower.contains(value)) return true;
            return false;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        resetTransaction();
        MarketUtils.releaseMenu(this);
        delayCounter = 60;
        state = State.COOLDOWN;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        resetTransaction();
        MarketUtils.releaseMenu(this);
        delayCounter = 80;
        state = State.COOLDOWN;
    }

    private void fail(String message) {
        if (notifications.get()) warning(message);
        closeMenu();
        MarketUtils.releaseMenu(this);
        resetTransaction();
        delayCounter = delay(errorBackoff);
        state = State.COOLDOWN;
    }

    private void resetTransaction() {
        waited = 0;
        stalledMoves = 0;
        countBeforeMove = 0;
        inventoryBeforeTransaction = 0;
        transferred = 0;
        selectedOrderValue = 0;
        saleAcknowledged = false;
        lastMenuId = Integer.MIN_VALUE;
        lastSignature = 0;
    }

    private int countTarget() {
        int count = 0;
        for (int slot = 0; slot < 36 && slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(targetItem.get())) count += stack.getCount();
        }
        return count;
    }

    private boolean inventoryFull() {
        for (int slot = 0; slot < 36 && slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) return false;
            if (stack.is(targetItem.get()) && stack.getCount() < stack.getMaxStackSize()) return false;
        }
        return true;
    }

    private int findContainerItem(ChestMenu menu) {
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            if (menu.getSlot(slot).getItem().is(targetItem.get())) return slot;
        }
        return -1;
    }

    private int findPlayerItem(ChestMenu menu) {
        for (int slot = MarketUtils.containerSlots(menu); slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).getItem().is(targetItem.get())) return slot;
        }
        return -1;
    }

    private int firstEmptyContainerSlot(ChestMenu menu) {
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            if (menu.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private void closeMenu() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
    }

    private BlockPos lookedAtStorage() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        return isStorage(hit.getBlockPos()) ? hit.getBlockPos().immutable() : null;
    }

    private boolean isStorage(BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        var block = mc.level.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || allowBarrels.get() && block instanceof BarrelBlock;
    }

    private void clickForNext(ChestMenu menu, int slot, State next) {
        markMenu(menu);
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        waited = 0;
        delayCounter = delay(menuDelay);
        state = next;
    }

    private void waitNewMenu(State next, String name) {
        if (mc.player.containerMenu instanceof ChestMenu menu && isNewMenu(menu)) {
            waited = 0;
            state = next;
        } else if (++waited >= menuTimeout.get()) {
            fail("The " + name + " did not open.");
        }
    }

    private void markMenu(ChestMenu menu) {
        if (menu == null) {
            lastMenuId = Integer.MIN_VALUE;
            lastSignature = 0;
        } else {
            lastMenuId = menu.containerId;
            lastSignature = signature(menu);
        }
    }

    private boolean isNewMenu(ChestMenu menu) {
        return menu.containerId != lastMenuId || signature(menu) != lastSignature;
    }

    private long signature(ChestMenu menu) {
        long hash = 1;
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode() * 31L + stack.getCount() * 17L + stack.getHoverName().getString().hashCode());
        }
        return hash;
    }

    private boolean isChest(ChestMenu menu, int slot) {
        return slot >= 0 && slot < MarketUtils.containerSlots(menu) && menu.getSlot(slot).getItem().is(Items.CHEST);
    }

    private int lastChest(ChestMenu menu) {
        for (int slot = MarketUtils.containerSlots(menu) - 1; slot >= 0; slot--) if (isChest(menu, slot)) return slot;
        return -1;
    }

    private int chestNearMiddle(ChestMenu menu) {
        int middle = MarketUtils.containerSlots(menu) / 2;
        int best = -1;
        int distance = Integer.MAX_VALUE;
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            if (!isChest(menu, slot)) continue;
            int candidate = Math.abs(slot - middle);
            if (candidate < distance) {
                best = slot;
                distance = candidate;
            }
        }
        return best;
    }

    private Setting<RandomBetweenInt> timing(String name, String description, int min, int max, int absoluteMin, int absoluteMax) {
        return sgTiming.add(new RandomBetweenIntSetting.Builder()
            .name(name)
            .description(description)
            .defaultRange(min, max)
            .range(absoluteMin, absoluteMax)
            .sliderRange(absoluteMin, Math.min(absoluteMax, 800))
            .build()
        );
    }

    private int delay(Setting<RandomBetweenInt> setting) {
        return Math.max(1, setting.get().getRandom());
    }

    private int random(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    @Override
    public String getInfoString() {
        return state.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " • " + soldThisSession;
    }

    public enum RefillSource {
        None,
        Chest,
        ExistingOrder
    }

    private enum State {
        IDLE,
        DECIDE,
        SCAN_SEND,
        SCAN_WAIT,
        SCAN,
        DEPOSIT_WAIT,
        DEPOSIT,
        DEPOSIT_SETTLE,
        CONFIRM_WAIT,
        VERIFY,
        CHEST_OPEN,
        CHEST_WAIT,
        CHEST_TAKE,
        CHEST_SETTLE,
        SOURCE_ORDER_SEND,
        SOURCE_MAIN_WAIT,
        SOURCE_MAIN_CLICK,
        SOURCE_ITEM_WAIT,
        SOURCE_ITEM_CLICK,
        SOURCE_STORAGE_WAIT,
        SOURCE_STORAGE_CLICK,
        SOURCE_ITEMS_WAIT,
        SOURCE_TAKE,
        SOURCE_TAKE_SETTLE,
        COOLDOWN
    }
}
