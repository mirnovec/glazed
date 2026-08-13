package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedSell;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;

public class AutoSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SellMode> mode = sgGeneral.add(new EnumSetting.Builder<SellMode>()
        .name("mode")
        .description("Whether to whitelist or blacklist the selected items.")
        .defaultValue(SellMode.Whitelist)
        .build()
    );

    private final Setting<List<Item>> itemList = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items to sell.")
        .defaultValue(List.of(Items.SEA_PICKLE))
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between actions.")
        .defaultValue(1)
        .min(0)
        .max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat feedback.")
        .defaultValue(true)
        .build()
    );

    private int delayCounter;
    private boolean needsReopen;

    public AutoSell() {
        super(GlazedAddon.CATEGORY, "auto-sell", "Automatically sells items.");
    }

    @Override
    public void onActivate() {
        delayCounter = 20;
        needsReopen = false;
    }

    @Override
    public void onDeactivate() {
        needsReopen = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        if (needsReopen) {
            GlazedSell.openSell();
            needsReopen = false;
            delayCounter = 20;
            return;
        }

        ChestMenu container = GlazedSell.container();

        if (container == null) {
            GlazedSell.openSell();
            delayCounter = 20;
            return;
        }

        int usable = GlazedSell.usableSlots(container);

        // usable area full, close and reopen for the next batch
        if (GlazedSell.firstEmptyUsableSlot(container) < 0) {
            GlazedSell.close();
            needsReopen = hasMatchingItems(container);
            if (!needsReopen) {
                if (notifications.get()) info("All items sold.");
                toggle();
            }
            delayCounter = delay.get();
            return;
        }

        // find the next matching item in the player inventory and shift-click it in
        int containerSlots = GlazedSell.containerSlots(container);
        for (int slot = containerSlots; slot < container.slots.size(); slot++) {
            ItemStack stack = container.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            if (!shouldSellItem(stack.getItem())) continue;

            mc.gameMode.handleInventoryMouseClick(container.containerId, slot, 0, ClickType.QUICK_MOVE, mc.player);
            delayCounter = delay.get();
            return;
        }

        // nothing left to deposit, close
        GlazedSell.close();
        if (notifications.get()) info("All items sold.");
        toggle();
    }

    private boolean hasMatchingItems(ChestMenu container) {
        int containerSlots = GlazedSell.containerSlots(container);
        for (int slot = containerSlots; slot < container.slots.size(); slot++) {
            ItemStack stack = container.getSlot(slot).getItem();
            if (!stack.isEmpty() && shouldSellItem(stack.getItem())) return true;
        }
        return false;
    }

    private boolean shouldSellItem(Item item) {
        List<Item> selectedItems = itemList.get();
        if (mode.get() == SellMode.Whitelist) {
            return selectedItems.contains(item);
        } else {
            return !selectedItems.contains(item);
        }
    }

    public enum SellMode {
        Whitelist,
        Blacklist
    }
}

