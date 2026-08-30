package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
import com.nnpg.glazed.utils.GlazedSell;
import com.nnpg.glazed.utils.RandomBetweenInt;
import java.util.Random;
import meteordevelopment.meteorclient.events.world.TickEvent.Pre;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BoolSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;

public class ChestSeller extends Module {
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgTiming = this.settings.createGroup("Timing");
   private final Setting<Boolean> notifications = this.sgGeneral
      .add(((Builder)((Builder)((Builder)new Builder().name("notifications")).description("Show chat feedback.")).defaultValue(true)).build());
   private final Setting<RandomBetweenInt> takeDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("take-delay-range"))
               .description("Random tick range between stacks taken from the source chest."))
            .defaultRange(1, 4)
            .range(1, 100)
            .sliderRange(1, 40)
            .build()
      );
   private final Setting<RandomBetweenInt> depositDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("deposit-delay-range"))
               .description("Random tick range between stacks placed into the sell window."))
            .defaultRange(2, 5)
            .range(1, 100)
            .sliderRange(1, 40)
            .build()
      );
   private final Setting<RandomBetweenInt> screenDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("screen-delay-range"))
               .description("Random tick range after opening or closing a window."))
            .defaultRange(5, 10)
            .range(1, 200)
            .sliderRange(1, 60)
            .build()
      );
   private final Setting<RandomBetweenInt> confirmDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("confirm-delay-range"))
               .description("Random tick range before and after pressing the green sell button."))
            .defaultRange(8, 15)
            .range(1, 200)
            .sliderRange(1, 80)
            .build()
      );
   private final Setting<Integer> menuTimeout = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("menu-timeout"))
                  .description("Ticks to wait for a chest or sell window before backing off."))
               .defaultValue(80))
            .min(20)
            .max(400)
            .sliderMax(200)
            .build()
      );
   private final Setting<RandomBetweenInt> cycleDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("cycle-delay-range"))
               .description("Random tick range between a completed sale and reopening the source chest."))
            .defaultRange(16, 35)
            .range(1, 1000)
            .sliderRange(1, 200)
            .build()
      );
   private final Setting<RandomBetweenInt> idleBackoff = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("idle-backoff-range"))
               .description("Random tick range before retrying when the chest is empty or an action fails."))
            .defaultRange(180, 320)
            .range(20, 6000)
            .sliderRange(20, 800)
            .build()
      );
   private final Setting<Integer> hesitationChance = this.sgTiming
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                     .name("hesitation-chance"))
                  .description("Chance after an item click to add a longer random pause."))
               .defaultValue(8))
            .min(0)
            .max(50)
            .sliderMax(30)
            .build()
      );
   private final Setting<RandomBetweenInt> hesitationDelay = this.sgTiming
      .add(
         ((RandomBetweenIntSetting.Builder)((RandomBetweenIntSetting.Builder)new RandomBetweenIntSetting.Builder().name("hesitation-delay-range"))
               .description("Extra tick range used when a hesitation occurs."))
            .defaultRange(8, 30)
            .range(1, 400)
            .sliderRange(1, 100)
            .build()
      );
   private final Random random = new Random();
   private ChestSeller.State state = ChestSeller.State.IDLE;
   private BlockPos sourcePos;
   private int delayCounter;
   private int waited;
   private int sourceCursor;
   private int depositCursor;
   private int failedMoves;
   private int taken;
   private int deposited;
   private int sold;
   private int inventoryBeforeSale;

   public ChestSeller() {
      super(GlazedAddon.CATEGORY, "chest-seller", "Takes everything from the chest you are looking at and repeatedly sells it through /sell.");
   }

   public void onActivate() {
      this.resetCycle();
      this.sold = 0;
      this.delayCounter = 0;
      this.state = ChestSeller.State.IDLE;
   }

   public void onDeactivate() {
      this.closeMenu();
      this.state = ChestSeller.State.IDLE;
   }

   @EventHandler
   private void onTick(Pre event) {
      if (this.mc.player != null && this.mc.level != null && this.mc.gameMode != null && this.mc.getConnection() != null) {
         if (this.delayCounter > 0) {
            this.delayCounter--;
         } else {
            switch (this.state) {
               case IDLE:
                  this.tickIdle();
                  break;
               case SOURCE_OPEN:
                  this.tickSourceOpen();
                  break;
               case SOURCE_WAIT:
                  this.tickSourceWait();
                  break;
               case TAKE:
                  this.tickTake();
                  break;
               case SOURCE_CLOSE:
                  this.tickSourceClose();
                  break;
               case SELL_OPEN:
                  this.tickSellOpen();
                  break;
               case SELL_WAIT:
                  this.tickSellWait();
                  break;
               case DEPOSIT:
                  this.tickDeposit();
                  break;
               case CONFIRM:
                  this.tickConfirm();
                  break;
               case SELL_CLOSE:
                  this.tickSellClose();
                  break;
               case COOLDOWN:
                  this.resetCycle();
                  this.state = ChestSeller.State.IDLE;
            }
         }
      }
   }

   private void tickIdle() {
      BlockHitResult hit = this.lookedAtChest();
      if (hit == null) {
         this.delayCounter = this.randomTicks(6, 14);
      } else {
         this.sourcePos = hit.getBlockPos();
         this.state = ChestSeller.State.SOURCE_OPEN;
      }
   }

   private void tickSourceOpen() {
      BlockHitResult hit = this.lookedAtChest();
      if (hit != null && hit.getBlockPos().equals(this.sourcePos)) {
         this.mc.gameMode.useItemOn(this.mc.player, InteractionHand.MAIN_HAND, hit);
         this.mc.player.swing(InteractionHand.MAIN_HAND);
         this.waited = 0;
         this.delayCounter = this.randomDelay(this.screenDelay);
         this.state = ChestSeller.State.SOURCE_WAIT;
      } else {
         if ((Boolean)this.notifications.get()) {
            this.warning("Look at the source chest to continue.", new Object[0]);
         }

         this.backoff();
      }
   }

   private void tickSourceWait() {
      if (this.openChest() != null) {
         this.sourceCursor = 0;
         this.failedMoves = 0;
         this.state = ChestSeller.State.TAKE;
      } else {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            if ((Boolean)this.notifications.get()) {
               this.warning("The source chest did not open.", new Object[0]);
            }

            this.backoff();
         }
      }
   }

   private void tickTake() {
      ChestMenu menu = this.openChest();
      if (menu == null) {
         if ((Boolean)this.notifications.get()) {
            this.warning("The source chest closed early.", new Object[0]);
         }

         this.backoff();
      } else {
         int end = Math.min(GlazedSell.containerSlots(menu), menu.slots.size());
         int nonEmpty = this.countNonEmpty(menu, 0, end);
         if (nonEmpty != 0 && this.failedMoves < nonEmpty) {
            int slot = this.findNextNonEmpty(menu, 0, end, this.sourceCursor);
            if (slot < 0) {
               this.state = ChestSeller.State.SOURCE_CLOSE;
            } else {
               ItemStack before = menu.getSlot(slot).getItem().copy();
               this.mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, this.mc.player);
               if (ItemStack.matches(before, menu.getSlot(slot).getItem())) {
                  this.failedMoves++;
                  this.sourceCursor = this.nextSlot(slot, 0, end);
               } else {
                  this.failedMoves = 0;
                  this.sourceCursor = slot;
                  this.taken++;
               }

               this.delayCounter = this.humanClickDelay(this.takeDelay);
            }
         } else {
            this.state = ChestSeller.State.SOURCE_CLOSE;
         }
      }
   }

   private void tickSourceClose() {
      this.closeMenu();
      if (this.taken == 0) {
         if ((Boolean)this.notifications.get()) {
            this.info("The source chest is empty or the inventory is full.", new Object[0]);
         }

         this.backoff();
      } else {
         this.inventoryBeforeSale = this.countPlayerStacks();
         if ((Boolean)this.notifications.get()) {
            this.info("Took %d stack(s); opening /sell.", new Object[]{this.taken});
         }

         this.delayCounter = this.randomDelay(this.screenDelay);
         this.state = ChestSeller.State.SELL_OPEN;
      }
   }

   private void tickSellOpen() {
      GlazedSell.openSell();
      this.waited = 0;
      this.depositCursor = 0;
      this.failedMoves = 0;
      this.delayCounter = this.randomDelay(this.screenDelay);
      this.state = ChestSeller.State.SELL_WAIT;
   }

   private void tickSellWait() {
      if (GlazedSell.container() != null) {
         this.state = ChestSeller.State.DEPOSIT;
      } else {
         if (++this.waited >= (Integer)this.menuTimeout.get()) {
            if ((Boolean)this.notifications.get()) {
               this.warning("The /sell window did not open.", new Object[0]);
            }

            this.backoff();
         }
      }
   }

   private void tickDeposit() {
      ChestMenu menu = GlazedSell.container();
      if (menu == null) {
         if ((Boolean)this.notifications.get()) {
            this.warning("The /sell window closed early.", new Object[0]);
         }

         this.backoff();
      } else if (GlazedSell.firstEmptyUsableSlot(menu) < 0) {
         this.beginConfirm();
      } else {
         int from = GlazedSell.containerSlots(menu);
         int to = menu.slots.size();
         int nonEmpty = this.countNonEmpty(menu, from, to);
         if (nonEmpty != 0 && this.failedMoves < nonEmpty) {
            int start = Math.max(from, Math.min(this.depositCursor, to - 1));
            int slot = this.findNextNonEmpty(menu, from, to, start);
            if (slot < 0) {
               this.beginConfirm();
            } else {
               ItemStack before = menu.getSlot(slot).getItem().copy();
               this.mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, this.mc.player);
               if (ItemStack.matches(before, menu.getSlot(slot).getItem())) {
                  this.failedMoves++;
                  this.depositCursor = this.nextSlot(slot, from, to);
               } else {
                  this.failedMoves = 0;
                  this.depositCursor = slot;
                  this.deposited++;
               }

               this.delayCounter = this.humanClickDelay(this.depositDelay);
            }
         } else {
            if (this.deposited == 0) {
               if ((Boolean)this.notifications.get()) {
                  this.warning("No inventory items could be put into /sell.", new Object[0]);
               }

               this.backoff();
            } else {
               this.beginConfirm();
            }
         }
      }
   }

   private void beginConfirm() {
      this.waited = 0;
      this.delayCounter = this.randomDelay(this.confirmDelay);
      this.state = ChestSeller.State.CONFIRM;
   }

   private void tickConfirm() {
      ChestMenu menu = GlazedSell.container();
      if (menu == null) {
         if ((Boolean)this.notifications.get()) {
            this.warning("The /sell window vanished before confirmation.", new Object[0]);
         }

         this.backoff();
      } else {
         int button = this.findGreenButton(menu);
         if (button >= 0) {
            this.mc.gameMode.handleContainerInput(menu.containerId, button, 0, ContainerInput.PICKUP, this.mc.player);
            this.delayCounter = this.randomDelay(this.confirmDelay);
            this.state = ChestSeller.State.SELL_CLOSE;
         } else {
            if (++this.waited >= (Integer)this.menuTimeout.get()) {
               if ((Boolean)this.notifications.get()) {
                  this.warning("No green sell button was found; nothing was confirmed.", new Object[0]);
               }

               this.backoff();
            }
         }
      }
   }

   private void tickSellClose() {
      this.closeMenu();
      this.sold = this.sold + this.deposited;
      int remaining = this.countPlayerStacks();
      if (remaining > 0 && remaining < this.inventoryBeforeSale) {
         this.inventoryBeforeSale = remaining;
         this.deposited = 0;
         this.delayCounter = this.randomDelay(this.screenDelay);
         this.state = ChestSeller.State.SELL_OPEN;
      } else if (remaining >= this.inventoryBeforeSale && remaining > 0) {
         if ((Boolean)this.notifications.get()) {
            this.warning("Some inventory items did not sell; retrying the chest after a backoff.", new Object[0]);
         }

         this.backoff();
      } else {
         if ((Boolean)this.notifications.get()) {
            this.info("Sale complete: %d stack move(s), %d this session.", new Object[]{this.deposited, this.sold});
         }

         this.delayCounter = this.randomDelay(this.cycleDelay);
         this.state = ChestSeller.State.COOLDOWN;
      }
   }

   private BlockHitResult lookedAtChest() {
      if (this.mc.hitResult instanceof BlockHitResult hit) {
         if (hit.getType() != Type.BLOCK) {
            return null;
         } else {
            return this.mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof ChestBlock ? hit : null;
         }
      } else {
         return null;
      }
   }

   private ChestMenu openChest() {
      if (this.mc.player.containerMenu == this.mc.player.inventoryMenu) {
         return null;
      } else {
         return this.mc.player.containerMenu instanceof ChestMenu menu ? menu : null;
      }
   }

   private int findGreenButton(ChestMenu menu) {
      int end = Math.min(GlazedSell.containerSlots(menu), menu.slots.size());
      int start = Math.min(GlazedSell.usableSlots(menu), end);

      for (int slot = end - 1; slot >= start; slot--) {
         if (GlazedSell.isConfirmButton(menu.getSlot(slot).getItem())) {
            return slot;
         }
      }

      return -1;
   }

   private int findNextNonEmpty(ChestMenu menu, int from, int to, int start) {
      if (from >= to) {
         return -1;
      } else {
         int boundedStart = Math.max(from, Math.min(start, to - 1));

         for (int slot = boundedStart; slot < to; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) {
               return slot;
            }
         }

         for (int slotx = from; slotx < boundedStart; slotx++) {
            if (!menu.getSlot(slotx).getItem().isEmpty()) {
               return slotx;
            }
         }

         return -1;
      }
   }

   private int countNonEmpty(ChestMenu menu, int from, int to) {
      int count = 0;

      for (int slot = from; slot < to; slot++) {
         if (!menu.getSlot(slot).getItem().isEmpty()) {
            count++;
         }
      }

      return count;
   }

   private int nextSlot(int slot, int from, int to) {
      return slot + 1 < to ? slot + 1 : from;
   }

   private int countPlayerStacks() {
      int count = 0;
      int size = Math.min(36, this.mc.player.getInventory().getContainerSize());

      for (int slot = 0; slot < size; slot++) {
         if (!this.mc.player.getInventory().getItem(slot).isEmpty()) {
            count++;
         }
      }

      return count;
   }

   private void resetCycle() {
      this.sourcePos = null;
      this.waited = 0;
      this.sourceCursor = 0;
      this.depositCursor = 0;
      this.failedMoves = 0;
      this.taken = 0;
      this.deposited = 0;
      this.inventoryBeforeSale = 0;
   }

   private void backoff() {
      this.closeMenu();
      this.delayCounter = this.randomDelay(this.idleBackoff);
      this.state = ChestSeller.State.COOLDOWN;
   }

   private void closeMenu() {
      if (this.mc.player != null && this.mc.player.containerMenu != this.mc.player.inventoryMenu) {
         this.mc.player.closeContainer();
      }

      if (this.mc.screen != null) {
         this.mc.setScreen(null);
      }
   }

   private int humanClickDelay(Setting<RandomBetweenInt> range) {
      int ticks = this.randomDelay(range);
      if (this.random.nextInt(100) < (Integer)this.hesitationChance.get()) {
         ticks += this.randomDelay(this.hesitationDelay);
      }

      return ticks;
   }

   private int randomDelay(Setting<RandomBetweenInt> range) {
      return Math.max(1, ((RandomBetweenInt)range.get()).getRandom());
   }

   private int randomTicks(int min, int max) {
      return min + this.random.nextInt(max - min + 1);
   }

   private static enum State {
      IDLE,
      SOURCE_OPEN,
      SOURCE_WAIT,
      TAKE,
      SOURCE_CLOSE,
      SELL_OPEN,
      SELL_WAIT,
      DEPOSIT,
      CONFIRM,
      SELL_CLOSE,
      COOLDOWN;
   }
}
