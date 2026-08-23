package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/** Watches the highest matching order without clicking or fulfilling it. */
public class OrderNotifier extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing and safety");

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("Order item to watch. Set AIR to use only the search term.")
        .defaultValue(Items.DIAMOND)
        .build()
    );

    private final Setting<String> searchTerm = sgGeneral.add(new StringSetting.Builder()
        .name("search-term")
        .description("Text used by /orders and as an optional name/tooltip filter.")
        .defaultValue("diamond")
        .build()
    );

    private final Setting<String> commandOverride = sgGeneral.add(new StringSetting.Builder()
        .name("command-override")
        .description("Optional command without a leading slash. Blank uses /orders plus the query.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> moneyThreshold = sgGeneral.add(new StringSetting.Builder()
        .name("money-threshold")
        .description("Sound the alarm when the biggest matching order reaches this value. Supports K/M/B.")
        .defaultValue("1m")
        .build()
    );

    private final Setting<MarketUtils.PriceMode> priceMode = sgGeneral.add(new EnumSetting.Builder<MarketUtils.PriceMode>()
        .name("threshold-mode")
        .description("Compare the threshold with total payout or per-item price.")
        .defaultValue(MarketUtils.PriceMode.Total)
        .build()
    );

    private final Setting<Integer> soundVolume = sgGeneral.add(new IntSetting.Builder()
        .name("sound-volume")
        .description("Alarm volume.")
        .defaultValue(6)
        .min(1)
        .max(10)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> alertCooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("alert-cooldown")
        .description("Seconds before the same continuing opportunity may sound again.")
        .defaultValue(120)
        .min(10)
        .max(3600)
        .sliderMax(600)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show details when the alarm fires or recovery is needed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RandomBetweenInt> screenDelay = timing("screen-delay-range", "Delay before reading a newly opened order menu.", 12, 24, 5, 400);
    private final Setting<RandomBetweenInt> refreshDelay = timing("refresh-delay-range", "Random delay between order searches.", 240, 400, 80, 6000);
    private final Setting<RandomBetweenInt> errorBackoff = timing("error-backoff-range", "Delay after a missing or stale order menu.", 400, 700, 100, 6000);

    private final Setting<Integer> menuTimeout = sgTiming.add(new IntSetting.Builder()
        .name("menu-timeout")
        .description("Ticks to wait for order results before backing off.")
        .defaultValue(120)
        .min(20)
        .max(600)
        .sliderMax(240)
        .build()
    );

    private final Setting<Integer> rateLimitCooldownSeconds = sgTiming.add(new IntSetting.Builder()
        .name("rate-limit-cooldown")
        .description("Seconds to pause if the server asks for slower actions.")
        .defaultValue(90)
        .min(20)
        .max(900)
        .sliderMax(300)
        .build()
    );

    private final Random random = new Random();
    private State state = State.IDLE;
    private int delayCounter;
    private int waited;
    private int alertCooldownTicks;
    private long lastBiggest;

    public OrderNotifier() {
        super(GlazedAddon.CATEGORY, "order-notifier", "Sounds a loud alarm when the biggest matching order passes your threshold.");
    }

    @Override
    public void onActivate() {
        if (!validSettings()) {
            toggle();
            return;
        }
        closeMenu();
        waited = 0;
        alertCooldownTicks = 0;
        lastBiggest = 0;
        delayCounter = random(20, 40);
        state = State.SEND;
    }

    @Override
    public void onDeactivate() {
        closeMenu();
        MarketUtils.releaseMenu(this);
        state = State.IDLE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;
        if (alertCooldownTicks > 0) alertCooldownTicks--;
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        switch (state) {
            case IDLE -> {}
            case SEND -> sendSearch();
            case WAIT -> waitForMenu();
            case SCAN -> scan();
            case COOLDOWN -> state = State.SEND;
        }
    }

    private boolean validSettings() {
        if (targetItem.get() == Items.AIR && searchTerm.get().isBlank()) {
            error("Choose a target item or enter a search term.");
            return false;
        }
        if (MarketUtils.parseConfiguredMoney(moneyThreshold.get()) <= 0) {
            error("Invalid money threshold: %s.", moneyThreshold.get());
            return false;
        }
        return true;
    }

    private void sendSearch() {
        if (!MarketUtils.acquireMenu(this)) {
            delayCounter = random(20, 40);
            return;
        }
        closeMenu();
        String override = MarketUtils.stripSlash(commandOverride.get());
        String query = MarketUtils.query(searchTerm.get(), targetItem.get());
        mc.getConnection().sendCommand(override.isBlank() ? (query.isBlank() ? "orders" : "orders " + query) : override);
        waited = 0;
        delayCounter = delay(screenDelay);
        state = State.WAIT;
    }

    private void waitForMenu() {
        if (mc.player.containerMenu instanceof ChestMenu) {
            delayCounter = delay(screenDelay);
            state = State.SCAN;
        } else if (++waited >= menuTimeout.get()) {
            if (notifications.get()) warning("Order results did not open; waiting before the next check.");
            backoff(errorBackoff);
        }
    }

    private void scan() {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            backoff(errorBackoff);
            return;
        }

        MarketUtils.Listing biggest = null;
        for (int slot = 0; slot < MarketUtils.containerSlots(menu); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!MarketUtils.matches(stack, targetItem.get(), searchTerm.get())) continue;
            MarketUtils.Price price = MarketUtils.readPrice(stack);
            if (price == null) continue;
            if (biggest == null || price.forMode(priceMode.get()) > biggest.price().forMode(priceMode.get())) {
                biggest = new MarketUtils.Listing(slot, stack.copy(), price);
            }
        }

        long threshold = MarketUtils.parseConfiguredMoney(moneyThreshold.get());
        lastBiggest = biggest == null ? 0 : biggest.price().forMode(priceMode.get());
        if (biggest != null && lastBiggest >= threshold && alertCooldownTicks <= 0) {
            soundAlarm();
            alertCooldownTicks = alertCooldownSeconds.get() * 20;
            if (notifications.get()) {
                info("BIG ORDER: %s at %s (%s).", biggest.stack().getHoverName().getString(), MoneyFmt.format(lastBiggest), priceMode.get());
            }
        }

        closeMenu();
        MarketUtils.releaseMenu(this);
        delayCounter = delay(refreshDelay);
        state = State.COOLDOWN;
    }

    private void soundAlarm() {
        float volume = soundVolume.get();
        mc.player.playSound(SoundEvents.ENDER_DRAGON_GROWL, volume, 1.0f);
        mc.player.playSound(SoundEvents.BEACON_ACTIVATE, volume, 1.25f);
        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, volume, 0.7f);
    }

    private void backoff(Setting<RandomBetweenInt> range) {
        closeMenu();
        MarketUtils.releaseMenu(this);
        delayCounter = delay(range);
        state = State.COOLDOWN;
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;
        String message = event.getMessage().getString();
        if (message == null || message.contains("[Meteor]")) return;
        try {
            if (!Pattern.compile("(slow down|too fast|please wait|rate.?limit|cooldown|try again later)", Pattern.CASE_INSENSITIVE).matcher(message).find()) return;
        } catch (Exception ignored) {
            return;
        }

        closeMenu();
        MarketUtils.releaseMenu(this);
        delayCounter = rateLimitCooldownSeconds.get() * 20 + random(0, 100);
        state = State.COOLDOWN;
        if (notifications.get()) warning("Server requested slower checks; pausing for about %d seconds.", rateLimitCooldownSeconds.get());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        MarketUtils.releaseMenu(this);
        state = State.COOLDOWN;
        delayCounter = 60;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        MarketUtils.releaseMenu(this);
        state = State.COOLDOWN;
        delayCounter = 80;
    }

    private void closeMenu() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
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
        String value = lastBiggest > 0 ? MoneyFmt.format(lastBiggest) : "none";
        return state.name().toLowerCase(Locale.ROOT) + " • " + value;
    }

    private enum State {
        IDLE,
        SEND,
        WAIT,
        SCAN,
        COOLDOWN
    }
}
