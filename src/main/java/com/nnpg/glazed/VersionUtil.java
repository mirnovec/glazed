package com.nnpg.glazed;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

// mc renames this stuff every version so keep it all in here
public final class VersionUtil {

    private VersionUtil() {}

    public static int getSelectedSlot(LocalPlayer player) {
        return player.getInventory().getSelectedSlot();
    }

    public static void setSelectedSlot(LocalPlayer player, int slot) {
        player.getInventory().setSelectedSlot(slot);
    }

    public static double getPrevX(Entity entity) {
        return entity.xOld;
    }

    public static double getPrevY(Entity entity) {
        return entity.yOld;
    }

    public static double getPrevZ(Entity entity) {
        return entity.zOld;
    }

    public static NonNullList<ItemStack> getMainInventory(LocalPlayer player) {
        return player.getInventory().getNonEquipmentItems();
    }
}
