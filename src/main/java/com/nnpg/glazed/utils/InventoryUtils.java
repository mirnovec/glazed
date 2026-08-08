package com.nnpg.glazed.utils;

import net.minecraft.world.entity.player.Inventory;

// was reflection on "selectedSlot" but that name gets remapped at runtime so it just returned 0
// forever. shieldbreaker + breachswap were swapping to slot 0 this whole time
public final class InventoryUtils {

    private InventoryUtils() {}

    public static int getSelectedSlot(Inventory inv) {
        return inv.selected;
    }

    public static void setSelectedSlot(Inventory inv, int slot) {
        inv.selected = slot;
    }
}
