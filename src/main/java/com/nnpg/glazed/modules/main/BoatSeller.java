package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.VersionUtil;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.utils.GlazedShop;
import com.nnpg.glazed.utils.RandomBetweenInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Pre;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.ItemSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public class BoatSeller extends Module {
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgOrders = this.settings.createGroup("Orders menu");
   private final SettingGroup sgPrice = this.settings.createGroup("Price lookup");
   private final SettingGroup sgRelisting = this.settings.createGroup("Relisting");
   private final SettingGroup sgTiming = this.settings.createGroup("Timing");
   private final Setting<Item> boat = this.sgGeneral
      .add(
         ((Builder)((Builder)((Builder)new Builder().name("boat")).description("Boat type to take from orders and sell.")).defaultValue(Items.SPRUCE_BOAT))
            .filter(item -> item instanceof BoatItem)
            .build()
      );
   private final Setting<Integer> undercut = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("undercut"))
                  .description("List this far below the cheapest matching auction."))
               .defaultValue(50))
            .min(1)
            .max(100000)
            .sliderMax(1000)
            .build()
      );
   private final Setting<Integer> minPrice = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("min-price"))
                  .description("Never list below this price. The module waits instead."))
               .defaultValue(1))
            .min(1)
            .max(1000000)
            .sliderMax(10000)
            .build()
      );
   private final Setting<Integer> limitCooldownSeconds = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("listing-limit-cooldown"))
                  .description("Seconds to wait after the auction house says there are too many listings."))
               .defaultValue(60))
            .min(20)
            .max(600)
            .sliderMax(300)
            .build()
      );
   private final Setting<String> limitRegex = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                     .name("limit-message"))
                  .description("Case-insensitive regex for the server's auction listing limit message."))
               .defaultValue(
                  "(sold too many|too many (items|listed|listings)|listing limit|sell limit|no (more )?(free )?(ah |auction )?slots|max(imum)? listings|reached .{0,20}limit|can only (have|list|sell))"
               ))
            .build()
      );
   private final Setting<Boolean> notifications = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                     .name("notifications"))
                  .description("Show chat feedback."))
               .defaultValue(true))
            .build()
      );
   private final Setting<String> ordersCommand = this.sgOrders
      .add(
         ((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                     .name("orders-command"))
                  .description("Command that opens the orders menu."))
               .defaultValue("/orders"))
            .build()
      );
   private final Setting<Integer> ordersSlotFromEnd = this.sgOrders
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("orders-slot-from-end"))
                  .description("The first chest to click, counted backward from the end of the orders menu."))
               .defaultValue(3))
            .min(1)
            .max(54)
            .sliderMax(9)
            .build()
      );
   private final Setting<Integer> orderSlot = this.sgOrders
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("order-slot"))
                  .description("Exact slot of the order to use after opening orders. -1 automatically finds the selected boat."))
               .defaultValue(-1))
            .min(-1)
            .max(53)
            .sliderMin(-1)
            .sliderMax(53)
            .build()
      );
   private final Setting<Integer> storageSlot = this.sgOrders
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("storage-slot"))
                  .description("Storage chest slot after selecting the boat. Falls back to the chest nearest the middle."))
               .defaultValue(13))
            .min(0)
            .max(53)
            .sliderMax(53)
            .build()
      );
   private final Setting<BoatSeller.TakeMode> takeMode = this.sgOrders
      .add(
         ((meteordevelopment.meteorclient.settings.EnumSetting.Builder)((meteordevelopment.meteorclient.settings.EnumSetting.Builder)((meteordevelopment.meteorclient.settings.EnumSetting.Builder)new meteordevelopment.meteorclient.settings.EnumSetting.Builder()
                     .name("take-mode"))
                  .description("Auto tries shift-clicking first, then a plain click if the server ignores it."))
               .defaultValue(BoatSeller.TakeMode.Auto))
            .build()
      );
   private final Setting<Integer> menuTimeout = this.sgOrders
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("menu-timeout"))
                  .description("Ticks to wait for an orders or auction menu before backing off."))
               .defaultValue(100))
            .min(20)
            .max(500)
            .sliderMax(200)
            .build()
      );
   private final Setting<String> priceCommand = this.sgPrice
      .add(
         ((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                     .name("price-command-override"))
                  .description("Optional AH lookup command without a leading slash. Empty automatically uses the selected boat name."))
               .defaultValue(""))
            .build()
      );
   private final Setting<String> priceRegex = this.sgPrice
      .add(
         ((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                     .name("price-regex"))
                  .description("Regex for listing prices. Group 1 is the number and optional group 2 is K, M, or B."))
               .defaultValue("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KkMmBb])?"))
            .build()
      );
   private final Setting<Boolean> firstListingOnly = this.sgPrice
      .add(
         ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                     .name("read-first-listing"))
                  .description("Trust the first matching listing as cheapest. Off scans every matching listing."))
               .defaultValue(false))
            .build()
      );
   private final Setting<Item> listingsButton = this.sgRelisting
      .add(
         ((Builder)((Builder)((Builder)new Builder().name("your-listings-button")).description("Item in the auction menu that opens your own listings."))
               .defaultValue(Items.CHEST))
            .build()
      );
   private final Setting<Integer> collectMax = this.sgRelisting
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("collect-max"))
                  .description("Maximum differently-priced boat listings to take back in one pass."))
               .defaultValue(64))
            .min(1)
            .max(500)
            .sliderMax(128)
            .build()
      );
   private final Setting<String> goneListingRegex = this.sgRelisting
      .add(
         ((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                  .name("gone-listing-message"))
               .description("Server message shown when a listing sells before it can be reclaimed."))
            .defaultValue(
               "(already (been )?(bought|sold|purchased|taken|claimed)|(was|has been) (already )?(bought|sold|purchased|claimed)|no longer (available|exists|listed|for sale)|(listing|item|auction) (is |was |has been |has )?(gone|expired|removed|unavailable)|not found|does ?n.?t exist)"
            )
            .build()
      );
   private final Setting<Integer> staleRefreshMax = this.sgRelisting
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("stale-refresh-max"))
                  .description("Maximum stale-list refreshes in one collection pass before continuing and trying again next cycle."))
               .defaultValue(10))
            .min(1)
            .max(100)
            .sliderMax(30)
            .build()
      );
   private final Setting<RandomBetweenInt> menuDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("menu-delay-range"))
               .description("Random ticks between orders-menu clicks."))
            .defaultRange(6, 12)
            .range(1, 200)
            .sliderRange(1, 60)
            .build()
      );
   private final Setting<RandomBetweenInt> takeDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("take-delay-range"))
               .description("Random ticks between taking boats from the order."))
            .defaultRange(4, 9)
            .range(1, 200)
            .sliderRange(1, 60)
            .build()
      );
   private final Setting<RandomBetweenInt> collectDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("collect-delay-range"))
               .description("Random ticks between boats reclaimed from your AH listings."))
            .defaultRange(8, 15)
            .range(1, 200)
            .sliderRange(1, 80)
            .build()
      );
   private final Setting<RandomBetweenInt> staleRefreshDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("stale-refresh-delay-range"))
               .description("Random ticks before reopening Your Listings after a boat sells during pickup."))
            .defaultRange(20, 40)
            .range(5, 1200)
            .sliderRange(5, 200)
            .build()
      );
   private final Setting<RandomBetweenInt> screenDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("screen-delay-range"))
               .description("Random ticks after a screen opens or closes."))
            .defaultRange(5, 10)
            .range(1, 200)
            .sliderRange(1, 60)
            .build()
      );
   private final Setting<RandomBetweenInt> slotDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("slot-delay-range"))
               .description("Random ticks between selecting a hotbar boat and sending /ah sell."))
            .defaultRange(1, 3)
            .range(1, 100)
            .sliderRange(1, 30)
            .build()
      );
   private final Setting<RandomBetweenInt> confirmDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("confirm-delay-range"))
               .description("Random ticks before clicking the auction confirmation."))
            .defaultRange(8, 15)
            .range(0, 200)
            .sliderRange(0, 80)
            .build()
      );
   private final Setting<Integer> confirmTimeout = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("confirm-timeout"))
                  .description("Ticks to wait for the auction confirmation screen."))
               .defaultValue(80))
            .min(10)
            .max(300)
            .sliderMax(150)
            .build()
      );
   private final Setting<RandomBetweenInt> verifyDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("verify-delay-range"))
               .description("Random ticks before checking that a listed boat left the inventory."))
            .defaultRange(12, 20)
            .range(1, 200)
            .sliderRange(1, 80)
            .build()
      );
   private final Setting<Integer> verifyTimeout = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("verify-timeout"))
                  .description("Ticks to wait for inventory or server-chat proof that a boat was listed."))
               .defaultValue(100))
            .min(20)
            .max(400)
            .sliderMax(200)
            .build()
      );
   private final Setting<Integer> maxListingRetries = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("max-listing-retries"))
                  .description("Retries at the same price before a full close, cooldown, and fresh price lookup."))
               .defaultValue(3))
            .min(1)
            .max(20)
            .sliderMax(10)
            .build()
      );
   private final Setting<RandomBetweenInt> listingRetryDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("listing-retry-delay-range"))
               .description("Random base delay before retrying a listing the server did not acknowledge."))
            .defaultRange(60, 120)
            .range(20, 1200)
            .sliderRange(20, 300)
            .build()
      );
   private final Setting<RandomBetweenInt> postCollectDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("post-collect-delay-range"))
               .description("Random settlement delay after reclaiming listings before sending a new AH sale."))
            .defaultRange(40, 80)
            .range(5, 1200)
            .sliderRange(5, 200)
            .build()
      );
   private final Setting<String> retryableMessageRegex = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.StringSetting.Builder)((meteordevelopment.meteorclient.settings.StringSetting.Builder)new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                  .name("retryable-message"))
               .description("Server messages that should retry the current listing instead of abandoning the batch."))
            .defaultValue("(please wait|slow down|too fast|cooldown|try again|cannot do that|can.?t do that|auction.*busy)")
            .build()
      );
   private final Setting<RandomBetweenInt> gapDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("listing-gap-range"))
               .description("Random ticks between finished auction listings."))
            .defaultRange(3, 8)
            .range(0, 200)
            .sliderRange(0, 60)
            .build()
      );
   private final Setting<RandomBetweenInt> cycleDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("cycle-delay-range"))
               .description("Random ticks between selling a batch and checking orders again."))
            .defaultRange(30, 60)
            .range(1, 2000)
            .sliderRange(1, 300)
            .build()
      );
   private final Setting<RandomBetweenInt> idleBackoff = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("idle-backoff-range"))
               .description("Random ticks before retrying an empty order or failed action."))
            .defaultRange(300, 600)
            .range(20, 6000)
            .sliderRange(20, 1200)
            .build()
      );
   private final Setting<Integer> hesitationChance = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("hesitation-chance"))
                  .description("Chance for an item click to add an extra human-like pause."))
               .defaultValue(8))
            .min(0)
            .max(50)
            .sliderMax(30)
            .build()
      );
   private final Setting<RandomBetweenInt> hesitationDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("hesitation-delay-range"))
               .description("Extra ticks added when a hesitation occurs."))
            .defaultRange(8, 30)
            .range(1, 400)
            .sliderRange(1, 100)
            .build()
      );
   private final Random random = new Random();
   private BoatSeller.State state = BoatSeller.State.IDLE;
   private int delayCounter;
   private int waited;
   private int lastMenuId;
   private long lastSignature;
   private int takeSource;
   private int boatsBeforeTake;
   private boolean plainClick;
   private int stalled;
   private int grabbed;
   private int collected;
   private int collectSource;
   private int boatsBeforeCollect;
   private ItemStack collectSnapshot = ItemStack.EMPTY;
   private int staleRefreshes;
   private int currentSlot;
   private int listed;
   private int sessionListed;
   private int stalledPulls;
   private int displacedMainSlot;
   private int displacedHotbarSlot;
   private ItemStack displacedHotbarItem = ItemStack.EMPTY;
   private long listPrice;
   private ItemStack soldRef = ItemStack.EMPTY;
   private int countBeforeSale;
   private int verifyTicks;
   private int consecutiveListingFailures;
   private boolean saleAcknowledged;
   private long lastCollectedPrice = Long.MIN_VALUE;
   private boolean collectionIncomplete = true;

   public BoatSeller() {
      super(GlazedAddon.CATEGORY, "boat-seller", "Takes boats from /orders, undercuts the cheapest matching AH listing, and lists them all.");
   }

   public void onActivate() {
      this.resetCycle();
      this.sessionListed = 0;
      this.consecutiveListingFailures = 0;
      this.lastCollectedPrice = Long.MIN_VALUE;
      this.collectionIncomplete = true;
      this.delayCounter = 0;
      this.state = BoatSeller.State.IDLE;
   }

   public void onDeactivate() {
      this.closeAnyMenu();
      this.restoreDisplacedHotbarItem();
      this.state = BoatSeller.State.IDLE;
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      if (!this.isActive()) return;

      this.closeAnyMenu();
      this.restoreDisplacedHotbarItem();
      this.resetCycle();
      this.consecutiveListingFailures = 0;
      this.delayCounter = 40;
      this.collectionIncomplete = true;
      this.lastCollectedPrice = Long.MIN_VALUE;
      this.state = BoatSeller.State.IDLE;
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      if (!this.isActive()) return;

      this.resetCycle();
      this.consecutiveListingFailures = 0;
      this.delayCounter = 40;
      this.collectionIncomplete = true;
      this.lastCollectedPrice = Long.MIN_VALUE;
      this.state = BoatSeller.State.IDLE;
   }

   private void resetCycle() {
      this.waited = 0;
      this.lastMenuId = Integer.MIN_VALUE;
      this.lastSignature = 0L;
      this.takeSource = -1;
      this.boatsBeforeTake = 0;
      this.plainClick = false;
      this.stalled = 0;
      this.grabbed = 0;
      this.collected = 0;
      this.collectSource = -1;
      this.boatsBeforeCollect = 0;
      this.collectSnapshot = ItemStack.EMPTY;
      this.staleRefreshes = 0;
      this.currentSlot = 0;
      this.listed = 0;
      this.stalledPulls = 0;
      this.displacedMainSlot = -1;
      this.displacedHotbarSlot = -1;
      this.displacedHotbarItem = ItemStack.EMPTY;
      this.listPrice = 0L;
      this.soldRef = ItemStack.EMPTY;
      this.countBeforeSale = 0;
      this.verifyTicks = 0;
      this.saleAcknowledged = false;
   }

   @EventHandler
   private void onTick(Pre event) {
      if (this.mc.player != null && this.mc.gameMode != null && this.mc.getConnection() != null) {
         if (this.delayCounter > 0) {
            this.delayCounter--;
         } else {
            switch (this.state) {
               case IDLE:
                  this.tickIdle();
                  break;
               case ORDERS_SEND:
                  this.tickOrdersSend();
                  break;
               case ORDERS_WAIT:
                  this.tickOrdersWait();
                  break;
               case ORDERS_CLICK:
                  this.tickOrdersClick();
                  break;
               case BOAT_MENU_WAIT:
                  this.tickMenuWait(BoatSeller.State.BOAT_MENU_CLICK, "boat selection menu");
                  break;
               case BOAT_MENU_CLICK:
                  this.tickBoatMenuClick();
                  break;
               case STORAGE_WAIT:
                  this.tickMenuWait(BoatSeller.State.STORAGE_CLICK, "boat order menu");
                  break;
               case STORAGE_CLICK:
                  this.tickStorageClick();
                  break;
               case ITEMS_WAIT:
                  this.tickMenuWait(BoatSeller.State.TAKE, "boat storage");
                  break;
               case TAKE:
                  this.tickTake();
                  break;
               case TAKE_SETTLE:
                  this.tickTakeSettle();
                  break;
               case ORDERS_CLOSE:
                  this.tickOrdersClose();
                  break;
               case PRICE_SEND:
                  this.tickPriceSend();
                  break;
               case PRICE_WAIT:
                  this.tickPriceWait();
                  break;
               case PRICE_CLOSE:
                  this.tickPriceClose();
                  break;
               case COLLECT_SEND:
                  this.tickCollectSend();
                  break;
               case COLLECT_OPEN_MINE:
                  this.tickCollectOpenMine();
                  break;
               case COLLECT_MINE_WAIT:
                  this.tickCollectMineWait();
                  break;
               case COLLECT_CLICK:
                  this.tickCollectClick();
                  break;
               case COLLECT_SETTLE:
                  this.tickCollectSettle();
                  break;
               case COLLECT_REFRESH:
                  this.state = BoatSeller.State.COLLECT_SEND;
                  break;
               case COLLECT_CLOSE:
                  this.tickCollectClose();
                  break;
               case SELL_SELECT:
                  this.tickSellSelect();
                  break;
               case SELL_SEND:
                  this.tickSellSend();
                  break;
               case SELL_CONFIRM:
                  this.tickSellConfirm();
                  break;
               case SELL_VERIFY:
                  this.tickSellVerify();
                  break;
               case SELL_RETRY:
                  this.tickSellRetry();
                  break;
               case SELL_GAP:
                  this.currentSlot++;
                  this.state = BoatSeller.State.SELL_SELECT;
                  break;
               case PULL_OPEN:
                  this.tickPullOpen();
                  break;
               case PULL:
                  this.tickPull();
                  break;
               case PULL_CLOSE:
                  this.tickPullClose();
                  break;
               case COOLDOWN:
                  this.resetCycle();
                  this.state = BoatSeller.State.IDLE;
            }
         }
      }
   }

   private void tickIdle() {
      if (this.countBoats() <= 0 && this.freeSlots() <= 0) {
         if ((Boolean)this.notifications.get()) {
            this.warning("Inventory is full; waiting for room before opening orders.", new Object[0]);
         }

         this.backoff();
      } else {
         this.state = BoatSeller.State.PRICE_SEND;
      }
   }

   private void tickOrdersSend() {
      this.markMenu();
      ChatUtils.sendPlayerMsg((String)this.ordersCommand.get());
      this.waited = 0;
      this.delayCounter = this.delay(this.screenDelay, false);
      this.state = BoatSeller.State.ORDERS_WAIT;
   }

   private void tickOrdersWait() {
      if (GlazedShop.openContainer() != null) {
         this.delayCounter = this.delay(this.menuDelay, true);
         this.state = BoatSeller.State.ORDERS_CLICK;
      } else {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            this.fail("Orders menu never opened.");
         }
      }
   }

   private void tickOrdersClick() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         this.fail("Orders menu closed early.");
      } else {
         int total = this.containerSlots(menu);
         int wanted = total - (Integer)this.ordersSlotFromEnd.get();
         int slot = this.isChest(this.itemAt(menu, wanted)) ? wanted : this.lastChestSlot(menu, total);
         if (slot < 0) {
            this.fail("No storage chest was found in the orders menu.");
         } else {
            this.clickMenu(menu, slot, BoatSeller.State.BOAT_MENU_WAIT);
         }
      }
   }

   private void tickBoatMenuClick() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         this.fail("Boat selection menu closed early.");
      } else {
         int total = this.containerSlots(menu);
         int configured = (Integer)this.orderSlot.get();
         int slot = configured >= 0 && configured < total && !this.itemAt(menu, configured).isEmpty()
            ? configured
            : this.findItem(menu, 0, total, (Item)this.boat.get());
         if (slot < 0) {
            this.fail(
               configured >= 0
                  ? "Configured order-slot %d is empty and no %s order was found.".formatted(configured, this.boatName())
                  : "No %s order was found.".formatted(this.boatName())
            );
         } else {
            this.clickMenu(menu, slot, BoatSeller.State.STORAGE_WAIT);
         }
      }
   }

   private void tickStorageClick() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         this.fail("Boat order menu closed early.");
      } else {
         int slot = this.chestNearMiddle(menu);
         if (slot < 0) {
            this.fail("No storage chest was found for that boat order.");
         } else {
            this.clickMenu(menu, slot, BoatSeller.State.ITEMS_WAIT);
         }
      }
   }

   private void tickMenuWait(BoatSeller.State next, String name) {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu != null && this.isNewMenu(menu)) {
         this.waited = 0;
         this.stalled = 0;
         this.delayCounter = this.delay(this.menuDelay, true);
         this.state = next;
      } else {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            this.fail("The " + name + " never opened.");
         }
      }
   }

   private void tickTake() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         if (this.grabbed > 0) {
            this.state = BoatSeller.State.ORDERS_CLOSE;
         } else {
            this.fail("Boat storage closed before any boats were taken.");
         }
      } else if (this.freeSlots() <= 0) {
         this.state = BoatSeller.State.ORDERS_CLOSE;
      } else {
         int source = this.findItem(menu, 0, this.containerSlots(menu), (Item)this.boat.get());
         if (source < 0) {
            if ((Boolean)this.notifications.get() && this.grabbed == 0) {
               this.info("No %s are waiting in that order.", new Object[]{this.boatName()});
            }

            this.state = BoatSeller.State.ORDERS_CLOSE;
         } else {
            this.boatsBeforeTake = this.countBoats();
            this.takeSource = source;
            boolean plain = this.takeMode.get() == BoatSeller.TakeMode.Click || this.takeMode.get() == BoatSeller.TakeMode.Auto && this.plainClick;
            this.mc.gameMode.handleContainerInput(menu.containerId, source, 0, plain ? ContainerInput.PICKUP : ContainerInput.QUICK_MOVE, this.mc.player);
            this.delayCounter = this.delay(this.takeDelay, true);
            this.state = BoatSeller.State.TAKE_SETTLE;
         }
      }
   }

   private void tickTakeSettle() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu != null && !menu.getCarried().isEmpty() && this.takeSource >= 0) {
         this.mc.gameMode.handleContainerInput(menu.containerId, this.takeSource, 0, ContainerInput.PICKUP, this.mc.player);
         this.delayCounter = this.delay(this.takeDelay, true);
      } else {
         int now = this.countBoats();
         if (now > this.boatsBeforeTake) {
            this.grabbed = this.grabbed + (now - this.boatsBeforeTake);
            this.stalled = 0;
            this.state = BoatSeller.State.TAKE;
         } else {
            this.stalled++;
            if (this.takeMode.get() == BoatSeller.TakeMode.Auto && !this.plainClick && this.stalled >= 2) {
               this.plainClick = true;
               this.stalled = 0;
               if ((Boolean)this.notifications.get()) {
                  this.info("Shift clicking did nothing; trying a plain click.", new Object[0]);
               }

               this.state = BoatSeller.State.TAKE;
            } else if (this.stalled >= 3) {
               if ((Boolean)this.notifications.get()) {
                  this.warning("Boats are not coming out of the order.", new Object[0]);
               }

               this.state = BoatSeller.State.ORDERS_CLOSE;
            } else {
               this.state = BoatSeller.State.TAKE;
            }
         }
      }
   }

   private void tickOrdersClose() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu != null && !menu.getCarried().isEmpty() && this.takeSource >= 0) {
         this.mc.gameMode.handleContainerInput(menu.containerId, this.takeSource, 0, ContainerInput.PICKUP, this.mc.player);
         this.delayCounter = this.delay(this.takeDelay, true);
      } else {
         this.closeAnyMenu();
         if (this.countBoats() <= 0) {
            this.backoff();
         } else {
            if ((Boolean)this.notifications.get()) {
               this.info("Took %d %s; checking the auction price.", new Object[]{this.grabbed, this.boatName()});
            }

            this.delayCounter = this.delay(this.screenDelay, false);
            this.state = BoatSeller.State.PRICE_SEND;
         }
      }
   }

   private void tickPriceSend() {
      this.closeAnyMenu();
      this.mc.getConnection().sendCommand(this.resolvedPriceCommand());
      this.waited = 0;
      this.delayCounter = this.delay(this.screenDelay, false);
      this.state = BoatSeller.State.PRICE_WAIT;
   }

   private void tickPriceWait() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu != null) {
         long cheapest = this.cheapestListing(menu);
         if (cheapest <= 0L) {
            if ((Boolean)this.notifications.get()) {
               this.warning("No priced %s listing was found; keeping the boats and retrying later.", new Object[]{this.boatName()});
            }

            this.state = BoatSeller.State.PRICE_CLOSE;
         } else {
            long candidate = cheapest - ((Integer)this.undercut.get()).intValue();
            if (candidate < ((Integer)this.minPrice.get()).intValue()) {
               if ((Boolean)this.notifications.get()) {
                  this.warning(
                     "Cheapest is %d; undercutting by %d would go below min-price %d.", new Object[]{cheapest, this.undercut.get(), this.minPrice.get()}
                  );
               }

               this.state = BoatSeller.State.PRICE_CLOSE;
            } else {
               this.listPrice = candidate;
               if ((Boolean)this.notifications.get()) {
                  this.info("Cheapest %s is %d; listing at %d.", new Object[]{this.boatName(), cheapest, this.listPrice});
               }

               this.state = BoatSeller.State.PRICE_CLOSE;
            }
         }
      } else {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            this.fail("Auction listings never opened.");
         }
      }
   }

   private void tickPriceClose() {
      this.closeAnyMenu();
      this.delayCounter = this.delay(this.screenDelay, false);
      if (this.listPrice <= 0L) {
         this.backoff();
      } else if (this.listPrice != this.lastCollectedPrice || this.collectionIncomplete) {
         this.collected = 0;
         this.state = BoatSeller.State.COLLECT_SEND;
      } else {
         this.continueAfterPriceAndCollection();
      }
   }

   private long cheapestListing(ChestMenu menu) {
      Pattern pattern;
      try {
         pattern = Pattern.compile((String)this.priceRegex.get());
      } catch (Exception var9) {
         if ((Boolean)this.notifications.get()) {
            this.error("price-regex does not compile: " + var9.getMessage(), new Object[0]);
         }

         return -1L;
      }

      long cheapest = -1L;

      for (int slot = 0; slot < this.containerSlots(menu); slot++) {
         ItemStack stack = this.itemAt(menu, slot);
         if (!stack.isEmpty() && stack.is((Item)this.boat.get())) {
            long price = this.parseListingPrice(stack, pattern);
            if (price > 0L) {
               if ((Boolean)this.firstListingOnly.get()) {
                  return price;
               }

               if (cheapest < 0L || price < cheapest) {
                  cheapest = price;
               }
            }
         }
      }

      return cheapest;
   }

   private long parseListingPrice(ItemStack stack, Pattern pattern) {
      List<String> lines = new ArrayList<>();
      lines.add(stack.getHoverName().getString());
      ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
      if (lore != null) {
         for (Component line : lore.lines()) {
            lines.add(line.getString());
         }
      }

      for (String line : lines) {
         String lower = line.toLowerCase(Locale.ROOT);
         if (lower.contains("price") || line.contains("$")) {
            long price = this.matchPrice(line, pattern);
            if (price > 0L) {
               return price;
            }
         }
      }

      for (String linex : lines) {
         long price = this.matchPrice(linex, pattern);
         if (price > 0L) {
            return price;
         }
      }

      return -1L;
   }

   private long matchPrice(String text, Pattern pattern) {
      Matcher matcher = pattern.matcher(text);
      if (matcher.find() && matcher.group(1) != null) {
         double value;
         try {
            value = Double.parseDouble(matcher.group(1).replace(",", ""));
         } catch (NumberFormatException var8) {
            return -1L;
         }

         if (matcher.groupCount() >= 2 && matcher.group(2) != null) {
            String e = matcher.group(2).toUpperCase(Locale.ROOT);

            value *= switch (e) {
               case "K" -> 1000.0;
               case "M" -> 1000000.0;
               case "B" -> 1.0E9;
               default -> 1.0;
            };
         }

         return (long)value;
      } else {
         return -1L;
      }
   }

   private void tickCollectSend() {
      this.closeAnyMenu();
      this.mc.getConnection().sendCommand(this.resolvedPriceCommand());
      this.waited = 0;
      this.delayCounter = this.delay(this.screenDelay, false);
      this.state = BoatSeller.State.COLLECT_OPEN_MINE;
   }

   private void tickCollectOpenMine() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            this.fail("Auction menu never opened for the relisting check.");
         }
      } else {
         int button = this.findItem(menu, 0, this.containerSlots(menu), (Item)this.listingsButton.get());
         if (button < 0) {
            if (++this.waited >= (Integer)this.menuTimeout.get()) {
               this.fail(
                  "No %s button for Your Listings was found.".formatted(((Item)this.listingsButton.get()).getDefaultInstance().getHoverName().getString())
               );
            }
         } else {
            this.markMenu();
            this.mc.gameMode.handleContainerInput(menu.containerId, button, 0, ContainerInput.PICKUP, this.mc.player);
            this.waited = 0;
            this.delayCounter = this.delay(this.menuDelay, true);
            this.state = BoatSeller.State.COLLECT_MINE_WAIT;
         }
      }
   }

   private void tickCollectMineWait() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu != null && this.isNewMenu(menu)) {
         this.waited = 0;
         this.state = BoatSeller.State.COLLECT_CLICK;
      } else {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            this.fail("Your Listings menu never opened.");
         }
      }
   }

   private void tickCollectClick() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         this.collectionIncomplete = true;
         this.state = BoatSeller.State.COLLECT_CLOSE;
      } else if (this.collected < (Integer)this.collectMax.get() && this.freeSlots() > 0) {
         int target = this.findDifferentlyPricedBoat(menu);
         if (target < 0) {
            this.collectionIncomplete = false;
            this.lastCollectedPrice = this.listPrice;
            this.state = BoatSeller.State.COLLECT_CLOSE;
         } else {
            this.collectSource = target;
            this.boatsBeforeCollect = this.countBoats();
            this.collectSnapshot = this.itemAt(menu, target).copy();
            this.mc.gameMode.handleContainerInput(menu.containerId, target, 0, ContainerInput.PICKUP, this.mc.player);
            this.waited = 0;
            this.delayCounter = this.delay(this.collectDelay, true);
            this.state = BoatSeller.State.COLLECT_SETTLE;
         }
      } else {
         this.collectionIncomplete = true;
         this.state = BoatSeller.State.COLLECT_CLOSE;
      }
   }

   private void tickCollectSettle() {
      int now = this.countBoats();
      if (now > this.boatsBeforeCollect) {
         this.collected = this.collected + (now - this.boatsBeforeCollect);
         this.collectSnapshot = ItemStack.EMPTY;
         this.waited = 0;
         this.state = BoatSeller.State.COLLECT_CLICK;
      } else {
         ChestMenu menu = GlazedShop.openContainer();
         if (menu == null) {
            if ((Boolean)this.notifications.get()) {
               this.warning("Your Listings closed while reclaiming a boat.", new Object[0]);
            }

            this.collectionIncomplete = true;
            this.state = BoatSeller.State.COLLECT_CLOSE;
         } else if (this.collectSource >= 0 && ItemStack.matches(this.collectSnapshot, this.itemAt(menu, this.collectSource))) {
            if (++this.waited >= (Integer)this.menuTimeout.get()) {
               if ((Boolean)this.notifications.get()) {
                  this.warning("A boat listing would not return to the inventory; continuing with what was reclaimed.", new Object[0]);
               }

               this.collectionIncomplete = true;
               this.state = BoatSeller.State.COLLECT_CLOSE;
            }
         } else {
            this.collectSnapshot = ItemStack.EMPTY;
            this.waited = 0;
            this.state = BoatSeller.State.COLLECT_CLICK;
         }
      }
   }

   private void refreshAfterGoneListing() {
      this.closeAnyMenu();
      this.collectSource = -1;
      this.collectSnapshot = ItemStack.EMPTY;
      this.waited = 0;
      this.collectionIncomplete = true;
      this.staleRefreshes++;
      if (this.staleRefreshes > (Integer)this.staleRefreshMax.get()) {
         if ((Boolean)this.notifications.get()) {
            this.warning("Too many listings sold during pickup; continuing now and checking the remainder next cycle.", new Object[0]);
         }

         this.delayCounter = this.delay(this.screenDelay, false);
         this.state = BoatSeller.State.COLLECT_CLOSE;
      } else {
         if ((Boolean)this.notifications.get()) {
            this.info("A boat sold during pickup; refreshing Your Listings (%d/%d).", new Object[]{this.staleRefreshes, this.staleRefreshMax.get()});
         }

         this.delayCounter = this.delay(this.staleRefreshDelay, false);
         this.state = BoatSeller.State.COLLECT_REFRESH;
      }
   }

   private void tickCollectClose() {
      this.closeAnyMenu();
      this.delayCounter = this.collected > 0 ? this.delay(this.postCollectDelay, false) : this.delay(this.screenDelay, false);
      if (this.collected > 0 && (Boolean)this.notifications.get()) {
         this.info("Reclaimed %d differently-priced %s listing(s).", new Object[]{this.collected, this.boatName()});
      }

      this.continueAfterPriceAndCollection();
   }

   private void continueAfterPriceAndCollection() {
      if (this.countBoats() > 0) {
         this.currentSlot = 0;
         this.listed = 0;
         this.state = BoatSeller.State.SELL_SELECT;
      } else if (this.freeSlots() <= 0) {
         this.backoff();
      } else {
         this.state = BoatSeller.State.ORDERS_SEND;
      }
   }

   private int findDifferentlyPricedBoat(ChestMenu menu) {
      Pattern pattern;
      try {
         pattern = Pattern.compile((String)this.priceRegex.get());
      } catch (Exception var8) {
         if ((Boolean)this.notifications.get()) {
            this.error("price-regex does not compile: " + var8.getMessage(), new Object[0]);
         }

         return -1;
      }

      int unpricedFallback = -1;

      for (int slot = 0; slot < this.containerSlots(menu); slot++) {
         ItemStack stack = this.itemAt(menu, slot);
         if (!stack.isEmpty() && stack.is((Item)this.boat.get())) {
            long price = this.parseListingPrice(stack, pattern);
            if (price > 0L && price != this.listPrice) {
               return slot;
            }

            if (price <= 0L && unpricedFallback < 0) {
               unpricedFallback = slot;
            }
         }
      }

      return unpricedFallback;
   }

   private void tickSellSelect() {
      if (this.currentSlot > 8) {
         if (this.hasBoatInMainInventory()) {
            this.state = BoatSeller.State.PULL_OPEN;
         } else {
            if ((Boolean)this.notifications.get()) {
               this.info("Listed %d %s, %d this session.", new Object[]{this.listed, this.boatName(), this.sessionListed});
            }

            this.normalCooldown();
         }
      } else {
         ItemStack stack = this.mc.player.getInventory().getItem(this.currentSlot);
         if (!stack.isEmpty() && stack.is((Item)this.boat.get())) {
            VersionUtil.setSelectedSlot(this.mc.player, this.currentSlot);
            this.delayCounter = this.delay(this.slotDelay, false);
            this.state = BoatSeller.State.SELL_SEND;
         } else {
            this.currentSlot++;
         }
      }
   }

   private void tickSellSend() {
      ItemStack stack = this.mc.player.getInventory().getItem(this.currentSlot);
      if (!stack.isEmpty() && stack.is((Item)this.boat.get())) {
         this.soldRef = stack.copy();
         this.countBeforeSale = this.countMatching(this.soldRef);
         this.saleAcknowledged = false;
         this.verifyTicks = 0;
         this.mc.getConnection().sendCommand("ah sell " + this.listPrice);
         this.waited = 0;
         this.delayCounter = this.delay(this.confirmDelay, false);
         this.state = BoatSeller.State.SELL_CONFIRM;
      } else {
         this.currentSlot++;
         this.state = BoatSeller.State.SELL_SELECT;
      }
   }

   private void tickSellConfirm() {
      if (GlazedSell.isDialogOpen()) {
         if (GlazedSell.clickDialogYes()) {
            this.waited = 0;
            this.verifyTicks = 0;
            this.delayCounter = this.delay(this.verifyDelay, false);
            this.state = BoatSeller.State.SELL_VERIFY;
         }
      } else if (this.mc.player.containerMenu instanceof ChestMenu chest && chest.getRowCount() == 3 && GlazedSell.clickConfirm(chest)) {
         this.waited = 0;
         this.verifyTicks = 0;
         this.delayCounter = this.delay(this.verifyDelay, false);
         this.state = BoatSeller.State.SELL_VERIFY;
      } else {
         if (++this.waited >= (Integer)this.confirmTimeout.get()) {
            this.scheduleListingRetry("no confirmation appeared for hotbar slot " + this.currentSlot);
         }
      }
   }

   private void tickSellVerify() {
      int after = this.countMatching(this.soldRef);
      if (this.saleAcknowledged || after < this.countBeforeSale) {
         this.finishSuccessfulListing();
      } else if (++this.verifyTicks >= (Integer)this.verifyTimeout.get()) {
         this.scheduleListingRetry("the server did not acknowledge the listing");
      }
   }

   private void tickSellRetry() {
      if (this.saleAcknowledged || this.countMatching(this.soldRef) < this.countBeforeSale) {
         this.finishSuccessfulListing();
      } else {
         this.state = BoatSeller.State.SELL_SELECT;
      }
   }

   private void finishSuccessfulListing() {
      this.listed++;
      this.sessionListed++;
      this.consecutiveListingFailures = 0;
      this.saleAcknowledged = false;
      this.soldRef = ItemStack.EMPTY;
      this.delayCounter = this.delay(this.gapDelay, true);
      this.state = BoatSeller.State.SELL_GAP;
   }

   private void scheduleListingRetry(String reason) {
      if (this.state == BoatSeller.State.SELL_RETRY) return;

      this.closeAnyMenu();
      this.saleAcknowledged = false;
      this.consecutiveListingFailures++;
      int maximum = (Integer)this.maxListingRetries.get();
      if (this.consecutiveListingFailures >= maximum) {
         if ((Boolean)this.notifications.get()) {
            this.warning("Listing failed %d time(s): %s. Closing AH and doing a fresh price check.", new Object[]{this.consecutiveListingFailures, reason});
         }

         this.consecutiveListingFailures = 0;
         this.soldRef = ItemStack.EMPTY;
         this.backoff();
      } else {
         int retryDelay = Math.max(20, this.delay(this.listingRetryDelay, false) * this.consecutiveListingFailures);
         if ((Boolean)this.notifications.get()) {
            this.warning(
               "Listing was not accepted (%s); retry %d/%d in about %.1f seconds.",
               new Object[]{reason, this.consecutiveListingFailures, maximum, retryDelay / 20.0}
            );
         }

         this.delayCounter = retryDelay;
         this.state = BoatSeller.State.SELL_RETRY;
      }
   }

   private void tickPullOpen() {
      if (this.mc.player.containerMenu != this.mc.player.inventoryMenu) {
         this.closeAnyMenu();
         this.delayCounter = this.delay(this.screenDelay, false);
      } else if (this.mc.screen instanceof InventoryScreen) {
         this.state = BoatSeller.State.PULL;
      } else {
         this.mc.setScreen(new InventoryScreen(this.mc.player));
         this.delayCounter = this.delay(this.screenDelay, false);
         this.state = BoatSeller.State.PULL;
      }
   }

   private void tickPull() {
      if (this.mc.player.containerMenu == this.mc.player.inventoryMenu && this.mc.screen instanceof InventoryScreen) {
         int source = this.firstMainInventoryBoat();
         int target = this.firstEmptyHotbarSlot();
         if (source < 0) {
            this.state = BoatSeller.State.PULL_CLOSE;
         } else {
            if (target < 0) {
               target = this.firstNonBoatHotbarSlot();
               if (target < 0) {
                  this.state = BoatSeller.State.PULL_CLOSE;
                  return;
               }

               this.displacedMainSlot = source;
               this.displacedHotbarSlot = target;
               this.displacedHotbarItem = this.mc.player.getInventory().getItem(target).copy();
            }

            ItemStack before = this.mc.player.getInventory().getItem(source).copy();
            this.mc.gameMode.handleContainerInput(this.mc.player.inventoryMenu.containerId, source, target, ContainerInput.SWAP, this.mc.player);
            if (ItemStack.matches(before, this.mc.player.getInventory().getItem(source))) {
               if (++this.stalledPulls >= 3) {
                  if ((Boolean)this.notifications.get()) {
                     this.warning("Could not move boats into the hotbar.", new Object[0]);
                  }

                  this.state = BoatSeller.State.PULL_CLOSE;
                  return;
               }
            } else {
               this.stalledPulls = 0;
            }

            this.delayCounter = this.delay(this.takeDelay, true);
         }
      } else {
         this.state = BoatSeller.State.PULL_OPEN;
      }
   }

   private void tickPullClose() {
      if (this.mc.screen instanceof InventoryScreen screen) {
         screen.onClose();
      }

      this.currentSlot = 0;
      this.stalledPulls = 0;
      this.delayCounter = this.delay(this.screenDelay, false);
      this.state = BoatSeller.State.SELL_SELECT;
   }

   private String resolvedPriceCommand() {
      String override = ((String)this.priceCommand.get()).trim();
      return !override.isEmpty() ? this.stripSlash(override) : "ah " + this.boatName().toLowerCase(Locale.ROOT);
   }

   private String stripSlash(String command) {
      return command.startsWith("/") ? command.substring(1) : command;
   }

   private String boatName() {
      return ((Item)this.boat.get()).getDefaultInstance().getHoverName().getString();
   }

   private int countBoats() {
      int total = 0;
      int size = Math.min(36, this.mc.player.getInventory().getContainerSize());

      for (int slot = 0; slot < size; slot++) {
         ItemStack stack = this.mc.player.getInventory().getItem(slot);
         if (!stack.isEmpty() && stack.is((Item)this.boat.get())) {
            total += stack.getCount();
         }
      }

      return total;
   }

   private int countMatching(ItemStack reference) {
      if (reference.isEmpty()) {
         return 0;
      } else {
         int total = 0;
         int size = Math.min(36, this.mc.player.getInventory().getContainerSize());

         for (int slot = 0; slot < size; slot++) {
            ItemStack stack = this.mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, reference)) {
               total += stack.getCount();
            }
         }

         return total;
      }
   }

   private int freeSlots() {
      int free = 0;
      int size = Math.min(36, this.mc.player.getInventory().getContainerSize());

      for (int slot = 0; slot < size; slot++) {
         if (this.mc.player.getInventory().getItem(slot).isEmpty()) {
            free++;
         }
      }

      return free;
   }

   private boolean hasBoatInMainInventory() {
      return this.firstMainInventoryBoat() >= 0;
   }

   private int firstMainInventoryBoat() {
      for (int slot = 9; slot < 36; slot++) {
         ItemStack stack = this.mc.player.getInventory().getItem(slot);
         if (!stack.isEmpty() && stack.is((Item)this.boat.get())) {
            return slot;
         }
      }

      return -1;
   }

   private int firstEmptyHotbarSlot() {
      for (int slot = 0; slot <= 8; slot++) {
         if (this.mc.player.getInventory().getItem(slot).isEmpty()) {
            return slot;
         }
      }

      return -1;
   }

   private int firstNonBoatHotbarSlot() {
      for (int slot = 0; slot <= 8; slot++) {
         ItemStack stack = this.mc.player.getInventory().getItem(slot);
         if (!stack.isEmpty() && !stack.is((Item)this.boat.get())) {
            return slot;
         }
      }

      return -1;
   }

   private void restoreDisplacedHotbarItem() {
      if (this.displacedMainSlot >= 0 && this.displacedHotbarSlot >= 0 && !this.displacedHotbarItem.isEmpty()) {
         if (this.mc.player != null && this.mc.gameMode != null) {
            ItemStack parked = this.mc.player.getInventory().getItem(this.displacedMainSlot);
            if (ItemStack.isSameItemSameComponents(parked, this.displacedHotbarItem) && parked.getCount() == this.displacedHotbarItem.getCount()) {
               this.mc
                  .gameMode
                  .handleContainerInput(
                     this.mc.player.inventoryMenu.containerId, this.displacedMainSlot, this.displacedHotbarSlot, ContainerInput.SWAP, this.mc.player
                  );
            } else if ((Boolean)this.notifications.get()) {
               this.warning("The temporarily parked hotbar item moved, so it was left in the inventory.", new Object[0]);
            }

            this.displacedMainSlot = -1;
            this.displacedHotbarSlot = -1;
            this.displacedHotbarItem = ItemStack.EMPTY;
         }
      }
   }

   private void clickMenu(ChestMenu menu, int slot, BoatSeller.State next) {
      this.markMenu();
      this.mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, this.mc.player);
      this.waited = 0;
      this.delayCounter = this.delay(this.menuDelay, true);
      this.state = next;
   }

   private void markMenu() {
      ChestMenu menu = GlazedShop.openContainer();
      if (menu == null) {
         this.lastMenuId = Integer.MIN_VALUE;
         this.lastSignature = 0L;
      } else {
         this.lastMenuId = menu.containerId;
         this.lastSignature = this.signature(menu);
      }
   }

   private boolean isNewMenu(ChestMenu menu) {
      return menu.containerId != this.lastMenuId || this.signature(menu) != this.lastSignature;
   }

   private long signature(ChestMenu menu) {
      long hash = 1L;

      for (int slot = 0; slot < this.containerSlots(menu); slot++) {
         ItemStack stack = this.itemAt(menu, slot);
         hash = hash * 31L + (stack.isEmpty() ? 0L : stack.getItem().hashCode() * 31L + stack.getCount());
      }

      return hash;
   }

   private int containerSlots(ChestMenu menu) {
      return Math.min(GlazedShop.containerSlotCount(menu), menu.slots.size());
   }

   private ItemStack itemAt(AbstractContainerMenu menu, int slot) {
      return slot >= 0 && slot < menu.slots.size() ? menu.getSlot(slot).getItem() : ItemStack.EMPTY;
   }

   private int findItem(ChestMenu menu, int from, int to, Item item) {
      for (int slot = from; slot < to && slot < menu.slots.size(); slot++) {
         if (this.itemAt(menu, slot).is(item)) {
            return slot;
         }
      }

      return -1;
   }

   private boolean isChest(ItemStack stack) {
      return stack.is(Items.CHEST) || stack.is(Items.TRAPPED_CHEST) || stack.is(Items.ENDER_CHEST) || stack.is(Items.BARREL);
   }

   private int lastChestSlot(ChestMenu menu, int total) {
      for (int slot = total - 1; slot >= 0; slot--) {
         if (this.isChest(this.itemAt(menu, slot))) {
            return slot;
         }
      }

      return -1;
   }

   private int chestNearMiddle(ChestMenu menu) {
      int total = this.containerSlots(menu);
      int configured = (Integer)this.storageSlot.get();
      if (configured < total && this.isChest(this.itemAt(menu, configured))) {
         return configured;
      } else {
         int middle = total / 2;
         int best = -1;
         int distance = Integer.MAX_VALUE;

         for (int slot = 0; slot < total; slot++) {
            if (this.isChest(this.itemAt(menu, slot))) {
               int candidate = Math.abs(slot - middle);
               if (candidate < distance) {
                  distance = candidate;
                  best = slot;
               }
            }
         }

         return best;
      }
   }

   private int delay(Setting<RandomBetweenInt> range, boolean allowHesitation) {
      int ticks = Math.max(0, ((RandomBetweenInt)range.get()).getRandom());
      if (allowHesitation && this.random.nextInt(100) < (Integer)this.hesitationChance.get()) {
         ticks += Math.max(1, ((RandomBetweenInt)this.hesitationDelay.get()).getRandom());
      }

      return ticks;
   }

   private void normalCooldown() {
      this.closeAnyMenu();
      this.restoreDisplacedHotbarItem();
      this.delayCounter = Math.max(1, this.delay(this.cycleDelay, false));
      this.state = BoatSeller.State.COOLDOWN;
   }

   private void backoff() {
      this.closeAnyMenu();
      this.restoreDisplacedHotbarItem();
      this.delayCounter = Math.max(20, this.delay(this.idleBackoff, false));
      this.state = BoatSeller.State.COOLDOWN;
   }

   private void fail(String message) {
      if ((Boolean)this.notifications.get()) {
         this.warning(message, new Object[0]);
      }

      this.backoff();
   }

   private void closeAnyMenu() {
      if (this.mc.player != null && this.mc.player.containerMenu != this.mc.player.inventoryMenu) {
         this.mc.player.closeContainer();
      }

      if (this.mc.screen != null) {
         this.mc.setScreen(null);
      }
   }

   private void startLimitCooldown() {
      this.closeAnyMenu();
      this.restoreDisplacedHotbarItem();
      this.saleAcknowledged = false;
      this.soldRef = ItemStack.EMPTY;
      this.consecutiveListingFailures = 0;
      this.delayCounter = (Integer)this.limitCooldownSeconds.get() * 20 + this.random.nextInt(101);
      this.state = BoatSeller.State.COOLDOWN;
      if ((Boolean)this.notifications.get()) {
         this.info(
            "Auction listing limit reached; keeping the remaining boats and retrying in about %d seconds.", new Object[]{this.limitCooldownSeconds.get()}
         );
      }
   }

   @EventHandler
   private void onChatMessage(ReceiveMessageEvent event) {
      if (!this.isActive()) return;

      String message = event.getMessage().getString();
      if (message.contains("[Meteor]")) return;

      if (this.state == BoatSeller.State.COLLECT_SETTLE && this.matchesGoneListing(message)) {
         this.refreshAfterGoneListing();
         return;
      }

      if (this.isListingState() && this.isListingSuccess(message)) {
         this.saleAcknowledged = true;
         if (this.state == BoatSeller.State.SELL_RETRY) {
            this.delayCounter = 0;
            this.state = BoatSeller.State.SELL_VERIFY;
         }

         return;
      }

      if (this.state == BoatSeller.State.COOLDOWN) return;

      try {
         if (Pattern.compile((String)this.limitRegex.get(), 2).matcher(message).find()) {
            this.startLimitCooldown();
            return;
         }
      } catch (Exception var5) {
         if ((Boolean)this.notifications.get()) {
            this.error("limit-message regex does not compile: " + var5.getMessage(), new Object[0]);
         }
      }

      if (this.isListingState()) {
         try {
            if (Pattern.compile((String)this.retryableMessageRegex.get(), 2).matcher(message).find()) {
               this.scheduleListingRetry("server requested a cooldown");
            }
         } catch (Exception var4) {
            if ((Boolean)this.notifications.get()) {
               this.error("retryable-message regex does not compile: " + var4.getMessage(), new Object[0]);
            }
         }
      }
   }

   private boolean matchesGoneListing(String message) {
      try {
         return Pattern.compile((String)this.goneListingRegex.get(), Pattern.CASE_INSENSITIVE).matcher(message).find();
      } catch (Exception var3) {
         String lower = message.toLowerCase(Locale.ROOT);
         return lower.contains("already bought") || lower.contains("already sold") || lower.contains("no longer") || lower.contains("not found");
      }
   }

   private boolean isListingState() {
      return this.state == BoatSeller.State.SELL_SEND
         || this.state == BoatSeller.State.SELL_CONFIRM
         || this.state == BoatSeller.State.SELL_VERIFY
         || this.state == BoatSeller.State.SELL_RETRY;
   }

   private boolean isListingSuccess(String message) {
      String lower = message.toLowerCase(Locale.ROOT);
      return lower.contains("you listed") && lower.contains(this.boatName().toLowerCase(Locale.ROOT));
   }

   public String getInfoString() {
      return this.state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
   }

   private static enum State {
      IDLE,
      ORDERS_SEND,
      ORDERS_WAIT,
      ORDERS_CLICK,
      BOAT_MENU_WAIT,
      BOAT_MENU_CLICK,
      STORAGE_WAIT,
      STORAGE_CLICK,
      ITEMS_WAIT,
      TAKE,
      TAKE_SETTLE,
      ORDERS_CLOSE,
      PRICE_SEND,
      PRICE_WAIT,
      PRICE_CLOSE,
      COLLECT_SEND,
      COLLECT_OPEN_MINE,
      COLLECT_MINE_WAIT,
      COLLECT_CLICK,
      COLLECT_SETTLE,
      COLLECT_REFRESH,
      COLLECT_CLOSE,
      SELL_SELECT,
      SELL_SEND,
      SELL_CONFIRM,
      SELL_VERIFY,
      SELL_RETRY,
      SELL_GAP,
      PULL_OPEN,
      PULL,
      PULL_CLOSE,
      COOLDOWN;
   }

   public static enum TakeMode {
      Auto,
      ShiftClick,
      Click;
   }
}
