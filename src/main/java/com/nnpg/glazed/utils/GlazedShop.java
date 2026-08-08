package com.nnpg.glazed.utils;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// shop is just one chest now, no categories and no confirm screen. find the item, click it
public final class GlazedShop {

    private GlazedShop() {}

    // open container or null
    public static ChestMenu openContainer() {
        if (mc.player == null) return null;

        return mc.player.containerMenu instanceof ChestMenu handler ? handler : null;
    }

    // anything past this is your own inv, never treat it as shop stock
    public static int containerSlotCount(ChestMenu handler) {
        return handler.getRowCount() * 9;
    }

    // first slot with this item, -1 if not there
    public static int findItemSlot(ChestMenu handler, Item item) {
        int limit = Math.min(containerSlotCount(handler), handler.slots.size());

        for (int index = 0; index < limit; index++) {
            Slot slot = handler.getSlot(index);
            if (!slot.getItem().isEmpty() && slot.getItem().is(item)) return index;
        }

        return -1;
    }

    // same but you pass your own check
    public static int findSlot(ChestMenu handler, java.util.function.Predicate<ItemStack> test) {
        int limit = Math.min(containerSlotCount(handler), handler.slots.size());

        for (int index = 0; index < limit; index++) {
            ItemStack stack = handler.getSlot(index).getItem();
            if (!stack.isEmpty() && test.test(stack)) return index;
        }

        return -1;
    }

    // just spam click it, nothing to confirm anymore
    public static void spamClick(AbstractContainerMenu handler, int slotIndex, int times) {
        if (mc.gameMode == null || mc.player == null) return;
        if (slotIndex < 0) return;

        for (int click = 0; click < times; click++) {
            mc.gameMode.handleContainerInput(handler.containerId, slotIndex, 0, ContainerInput.PICKUP, mc.player);
        }
    }

    // inv completely full?
    public static boolean isInventoryFull() {
        if (mc.player == null) return false;

        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) return false;
        }

        return true;
    }
}
