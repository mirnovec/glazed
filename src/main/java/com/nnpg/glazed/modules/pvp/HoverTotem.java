package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.mixins.HandledScreenMixin;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

public class HoverTotem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks to wait between operations.")
        .defaultValue(0)
        .min(0)
        .max(20)
        .sliderMin(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> hotbarTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-totem")
        .description("Also places a totem in your preferred hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Your preferred hotbar slot for totem (1-9).")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderMin(1)
        .sliderMax(9)
        .build()
    );

    private final Setting<Boolean> autoSwitchToTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch-to-totem")
        .description("Automatically switches to totem slot when inventory is opened.")
        .defaultValue(false)
        .build()
    );

    private int remainingDelay;

    public HoverTotem() {
        super(GlazedAddon.pvp, "hover-totem", "Equips a totem in offhand and optionally hotbar when hovering over one in inventory.");
    }

    @Override
    public void onActivate() {
        resetDelay();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        Screen currentScreen = mc.screen;
        if (!(currentScreen instanceof InventoryScreen inventoryScreen)) {
            resetDelay();
            return;
        }

        Slot focusedSlot = getFocusedSlotSafe(inventoryScreen);

        if (focusedSlot == null || focusedSlot.getContainerSlot() > 35) return;

        if (autoSwitchToTotem.get()) {
            mc.player.getInventory().selected = hotbarSlot.get() - 1;
        }

        if (!focusedSlot.getItem().is(Items.TOTEM_OF_UNDYING)) return;

        if (remainingDelay > 0) {
            remainingDelay--;
            return;
        }

        int slotIndex = focusedSlot.getContainerSlot();
        int syncId = inventoryScreen.getMenu().containerId;

        if (!mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            equipOffhandTotem(syncId, slotIndex);
            return;
        }

        if (hotbarTotem.get()) {
            int hotbarIndex = hotbarSlot.get() - 1;
            if (!mc.player.getInventory().getItem(hotbarIndex).is(Items.TOTEM_OF_UNDYING)) {
                equipHotbarTotem(syncId, slotIndex, hotbarIndex);
            }
        }
    }

    private void equipOffhandTotem(int syncId, int slotIndex) {
        mc.gameMode.handleInventoryMouseClick(syncId, slotIndex, 40, ClickType.SWAP, mc.player);
        resetDelay();
    }

    private void equipHotbarTotem(int syncId, int slotIndex, int hotbarIndex) {
        mc.gameMode.handleInventoryMouseClick(syncId, slotIndex, hotbarIndex, ClickType.SWAP, mc.player);
        resetDelay();
    }

    private void resetDelay() {
        remainingDelay = tickDelay.get();
    }

    // mixin always hits so the old reflection fallback never ran, and it checked the wrong class anyway
    private Slot getFocusedSlotSafe(InventoryScreen screen) {
        if (screen instanceof HandledScreenMixin mixin) {
            return mixin.glazed$getFocusedSlot();
        }

        return null;
    }
}
