package com.nnpg.glazed;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

// mc renames this stuff every version so keep it all in here
public final class VersionUtil {

    private VersionUtil() {}

    public static int getSelectedSlot(LocalPlayer player) {
        return player.getInventory().selected;
    }

    public static void setSelectedSlot(LocalPlayer player, int slot) {
        player.getInventory().selected = slot;
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
        return player.getInventory().items;
    }

    // 1.21.4 only has an exact-match lookup, so walk tab ourselves
    public static PlayerInfo playerInfoIgnoreCase(ClientPacketListener handler, String name) {
        if (handler == null || name == null) return null;

        for (PlayerInfo entry : handler.getOnlinePlayers()) {
            String profile = entry.getProfile().getName();
            if (profile != null && profile.equalsIgnoreCase(name)) return entry;
        }

        return null;
    }
}
