package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.utils.MarketUtils;
import com.nnpg.glazed.utils.RandomBetweenInt;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/** Slow, transaction-based AH sniper for the current Donut auction menus. */
public class AHSniper extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("Item to buy. Set AIR to use only the search term.")
        .defaultValue(Items.DIAMOND)
        .build()
    );

    private final Setting<String> searchTerm = sgGeneral.add(new StringSetting.Builder()
        .name("search-term")
        .description("AH search text and optional tooltip/name filter. Blank derives the item name.")
        .defaultValue("diamond")
        .build()
    );

    private final Setting<String> commandOverride = sgGeneral.add(new StringSetting.Builder()
        .name("command-override")
        .description("Optional command without a leading slash. Blank uses /ah plus the search term.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> minPrice = sgGeneral.add(new StringSetting.Builder()
        .name("min-price")
        .description("Ignore listings below this price. Supports K/M/B; 0 disables the minimum.")
        .defaultValue("0")
        .build()
    );

    private final Setting<String> maxPrice = sgGeneral.add(new StringSetting.Builder()
        .name("max-price")
        .description("Never buy above this price. Supports K/M/B.")
        .defaultValue("1k")
        .build()
    );

    private final Setting<MarketUtils.PriceMode> priceMode = sgGeneral.add(new EnumSetting.Builder<MarketUtils.PriceMode>()
        .name("price-mode")
        .description("Compare the limit against the whole listing or the per-item price.")
        .defaultValue(MarketUtils.PriceMode.Total)
        .build()
    );

    private final Setting<Boolean> autoConfirm = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-confirm")
        .description("Confirm a matching purchase automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BuyClickMode> buyClickMode = sgGeneral.add(new EnumSetting.Builder<BuyClickMode>()
        .name("buy-click-mode")
        .description("How to click a listing. ShiftRightClick matches the historical Donut AH menu; LeftClick is available if the server changes.")
        .defaultValue(BuyClickMode.ShiftRightClick)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show purchase and recovery messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> purchaseSound = sgGeneral.add(new BoolSetting.Builder()
        .name("purchase-sound")
        .description("Play a sound after a confirmed purchase.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> confirmTimeout = sgSafety.add(new IntSetting.Builder()
        .name("confirm-timeout")
        .description("Ticks to wait for a purchase confirmation.")
        .defaultValue(120)
        .min(20)
        .max(600)
        .sliderMax(240)
        .build()
    );

    private final Setting<Integer> verifyTimeout = sgSafety.add(new IntSetting.Builder()
        .name("verify-timeout")
        .description("Ticks to wait for inventory or chat proof that the purchase completed.")
        .defaultValue(120)
        .min(20)
        .max(600)
        .sliderMax(240)
        .build()
    );

    private final Setting<Integer> rateLimitCooldownSeconds = sgSafety.add(new IntSetting.Builder()
        .name("rate-limit-cooldown")
        .description("Seconds to stop after the server asks the module to slow down.")
        .defaultValue(90)
        .min(20)
        .max(900)
        .sliderMax(300)
        .build()
    );

    private final Setting<String> goneRegex = sgSafety.add(new StringSetting.Builder()
        .name("gone-message")
        .description("Message shown when another player bought the listing first.")
        .defaultValue("(already (been )?(bought|sold|purchased)|no longer (available|exists|listed)|item.*(gone|unavailable)|not found)")
        .build()
    );

    private final Setting<String> slowDownRegex = sgSafety.add(new StringSetting.Builder()
        .name("slow-down-message")
        .description("Server messages that trigger the long safety cooldown.")
        .defaultValue("(slow down|too fast|please wait|rate.?limit|cooldown|try again later)")
        .build()
    );

    private final Setting<RandomBetweenInt> screenDelay = timing("screen-delay-range", "Delay after an AH screen opens.", 12, 24, 5, 400);
    private final Setting<RandomBetweenInt> clickDelay = timing("click-delay-range", "Delay before a buy or confirm click.", 10, 20, 5, 400);
    private final Setting<RandomBetweenInt> refreshDelay = timing("refresh-delay-range", "Delay between AH searches when nothing qualifies.", 160, 260, 40, 2400);
    private final Setting<RandomBetweenInt> purchaseCooldown = timing("purchase-cooldown-range", "Delay after each successful purchase.", 120, 220, 40, 2400);
    private final Setting<RandomBetweenInt> failureBackoff = timing("failure-backoff-range", "Delay after a stale menu or failed transaction.", 300, 500, 40, 6000);

    private State state = State.IDLE;
    private final Random random = new Random();
    private int delayCounter;
    private int waited;
    private int verifyTicks;
    private int bought;
    private ItemStack attempted = ItemStack.EMPTY;
    private int countBefore;
    private long attemptedPrice;
    private boolean purchaseAcknowledged;
    private int listingMenuId = Integer.MIN_VALUE;
    private long listingSignature;

    public AHSniper() {
        super(GlazedAddon.CATEGORY, "ah-sniper", "Safely buys matching AH listings below your configured price.");
    }

    @Override
    public void onActivate() {
        long minimum = MarketUtils.parseConfiguredMoney(minPrice.get());
        long maximum = MarketUtils.parseConfiguredMoney(maxPrice.get());
        if (minimum < 0 || maximum <= 0 || minimum > maximum) {
            error("Invalid AH price range: %s to %s.", minPrice.get(), maxPrice.get());
            toggle();
            return;
        }
        if (targetItem.get() == Items.AIR && searchTerm.get().isBlank()) {
            error("Choose a target item or enter a search term.");
            toggle();
            return;
        }

        resetTransaction();
        bought = 0;
        delayCounter = random(20, 40);
        state = State.SEND_SEARCH;
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
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        switch (state) {
            case IDLE -> {}
            case SEND_SEARCH -> sendSearch();
            case WAIT_RESULTS -> waitResults();
            case SCAN -> scanResults();
            case WAIT_CONFIRM -> waitConfirm();
            case VERIFY -> verifyPurchase();
            case COOLDOWN -> state = State.SEND_SEARCH;
        }
    }

    private void sendSearch() {
        if (!MarketUtils.acquireMenu(this)) {
            delayCounter = random(20, 40);
            return;
        }
        if (inventoryFull()) {
            if (notifications.get()) warning("Inventory is full; AH Sniper is waiting for room.");
            cooldown(failureBackoff);
            return;
        }

        closeMenu();
        String override = MarketUtils.stripSlash(commandOverride.get());
        String query = MarketUtils.query(searchTerm.get(), targetItem.get());
        mc.getConnection().sendCommand(override.isBlank() ? (query.isBlank() ? "ah" : "ah " + query) : override);
        waited = 0;
        delayCounter = delay(screenDelay);
        state = State.WAIT_RESULTS;
    }

    private void waitResults() {
        if (mc.player.containerMenu instanceof ChestMenu) {
            delayCounter = delay(screenDelay);
            state = State.SCAN;
        } else if (++waited >= confirmTimeout.get()) {
            if (notifications.get()) warning("AH results did not open; backing off.");
            cooldown(failureBackoff);
        }
    }

    private void scanResults() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            cooldown(failureBackoff);
            return;
        }

        long minimum = MarketUtils.parseConfiguredMoney(minPrice.get());
        long maximum = MarketUtils.parseConfiguredMoney(maxPrice.get());
        MarketUtils.Listing best = null;

        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!MarketUtils.matches(stack, targetItem.get(), searchTerm.get())) continue;
            MarketUtils.Price price = MarketUtils.readPrice(stack);
            if (price == null) continue;
            long compared = price.forMode(priceMode.get());
            if (compared < minimum || compared > maximum) continue;
            if (best == null || compared < best.price().forMode(priceMode.get())) {
                best = new MarketUtils.Listing(slot, stack.copy(), price);
            }
        }

        if (best == null) {
            closeMenu();
            MarketUtils.releaseMenu(this);
            delayCounter = delay(refreshDelay);
            state = State.COOLDOWN;
            return;
        }

        attempted = best.stack().copy();
        attemptedPrice = best.price().forMode(priceMode.get());
        countBefore = countMatching(attempted);
        purchaseAcknowledged = false;
        listingMenuId = menu.containerId;
        listingSignature = signature(menu);
        int buySlot = best.slot();
        if (menu.getRowCount() == 3 && best.slot() == 13 && menu.slots.size() > 15 && !menu.getSlot(15).getItem().isEmpty()) buySlot = 15;
        int button = buyClickMode.get() == BuyClickMode.ShiftRightClick ? 1 : 0;
        ContainerInput input = buyClickMode.get() == BuyClickMode.ShiftRightClick ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        mc.gameMode.handleContainerInput(menu.containerId, buySlot, button, input, mc.player);
        waited = 0;
        verifyTicks = 0;
        delayCounter = delay(clickDelay);
        state = State.WAIT_CONFIRM;
    }

    private void waitConfirm() {
        if (purchaseSucceeded()) {
            finishPurchase();
            return;
        }

        if (!autoConfirm.get()) {
            if (++waited >= confirmTimeout.get()) failedPurchase("purchase was not manually confirmed");
            return;
        }

        if (GlazedSell.isDialogOpen() && GlazedSell.clickDialogYes()) {
            waited = 0;
            delayCounter = delay(clickDelay);
            state = State.VERIFY;
            return;
        }

        if (mc.player.containerMenu instanceof ChestMenu menu && isNewPurchaseMenu(menu) && GlazedSell.clickConfirm(menu)) {
            waited = 0;
            delayCounter = delay(clickDelay);
            state = State.VERIFY;
            return;
        }

        if (++waited >= confirmTimeout.get()) failedPurchase("confirmation did not appear");
    }

    private void verifyPurchase() {
        if (purchaseSucceeded()) {
            finishPurchase();
        } else if (++verifyTicks >= verifyTimeout.get()) {
            failedPurchase("server did not acknowledge the purchase");
        }
    }

    private boolean purchaseSucceeded() {
        return purchaseAcknowledged || countMatching(attempted) > countBefore;
    }

    private void finishPurchase() {
        bought++;
        if (notifications.get()) info("Bought %s for %s (%d this session).", attempted.getHoverName().getString(), com.nnpg.glazed.utils.MoneyFmt.format(attemptedPrice), bought);
        if (purchaseSound.get()) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 2.0f, 1.0f);
        resetTransaction();
        closeMenu();
        MarketUtils.releaseMenu(this);
        delayCounter = delay(purchaseCooldown);
        state = State.COOLDOWN;
    }

    private void failedPurchase(String reason) {
        if (notifications.get()) warning("AH purchase failed (%s); refreshing after a safe backoff.", reason);
        resetTransaction();
        cooldown(failureBackoff);
    }

    private void cooldown(Setting<RandomBetweenInt> range) {
        closeMenu();
        MarketUtils.releaseMenu(this);
        delayCounter = delay(range);
        state = State.COOLDOWN;
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;
        String message = event.getMessage().getString();
        if (message == null || message.isBlank() || message.contains("[Meteor]")) return;

        String lower = message.toLowerCase(Locale.ROOT);
        if (isPurchaseState() && lower.contains("you bought") && (attempted.isEmpty() || lower.contains(attempted.getHoverName().getString().toLowerCase(Locale.ROOT)))) {
            purchaseAcknowledged = true;
            return;
        }
        if (isPurchaseState() && matches(goneRegex.get(), message, "already bought", "no longer", "not found")) {
            failedPurchase("another player bought it first");
            return;
        }
        if (matches(slowDownRegex.get(), message, "slow down", "too fast", "please wait")) {
            resetTransaction();
            closeMenu();
            MarketUtils.releaseMenu(this);
            delayCounter = rateLimitCooldownSeconds.get() * 20 + random(0, 100);
            state = State.COOLDOWN;
            if (notifications.get()) warning("Server requested slower actions; pausing for about %d seconds.", rateLimitCooldownSeconds.get());
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        resetTransaction();
        MarketUtils.releaseMenu(this);
        delayCounter = 40;
        state = State.COOLDOWN;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        resetTransaction();
        MarketUtils.releaseMenu(this);
        delayCounter = 60;
        state = State.COOLDOWN;
    }

    private boolean isPurchaseState() {
        return state == State.WAIT_CONFIRM || state == State.VERIFY;
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

    private int countMatching(ItemStack reference) {
        if (reference.isEmpty()) return 0;
        int count = 0;
        for (int slot = 0; slot < 36 && slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, reference)) count += stack.getCount();
        }
        return count;
    }

    private boolean inventoryFull() {
        for (int slot = 0; slot < 36 && slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) return false;
            if (targetItem.get() != Items.AIR && stack.is(targetItem.get()) && stack.getCount() < stack.getMaxStackSize()) return false;
        }
        return true;
    }

    private void resetTransaction() {
        attempted = ItemStack.EMPTY;
        attemptedPrice = 0;
        countBefore = 0;
        purchaseAcknowledged = false;
        listingMenuId = Integer.MIN_VALUE;
        listingSignature = 0;
        waited = 0;
        verifyTicks = 0;
    }

    private void closeMenu() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
    }

    private boolean isNewPurchaseMenu(ChestMenu menu) {
        return menu.containerId != listingMenuId || signature(menu) != listingSignature;
    }

    private long signature(ChestMenu menu) {
        long hash = 1;
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode() * 31L + stack.getCount() * 17L + stack.getHoverName().getString().hashCode());
        }
        return hash;
    }

    private Setting<RandomBetweenInt> timing(String name, String description, int min, int max, int absoluteMin, int absoluteMax) {
        return sgTiming.add(new RandomBetweenIntSetting.Builder()
            .name(name)
            .description(description)
            .defaultRange(min, max)
            .range(absoluteMin, absoluteMax)
            .sliderRange(absoluteMin, Math.min(absoluteMax, 600))
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
        return state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private enum State {
        IDLE,
        SEND_SEARCH,
        WAIT_RESULTS,
        SCAN,
        WAIT_CONFIRM,
        VERIFY,
        COOLDOWN
    }

    public enum BuyClickMode {
        ShiftRightClick,
        LeftClick
    }
}
