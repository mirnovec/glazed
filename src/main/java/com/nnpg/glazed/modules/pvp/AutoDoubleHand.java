package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.Items;

public class AutoDoubleHand extends Module {
    private boolean wasHoldingTotem = true;

    public AutoDoubleHand() {
        super(GlazedAddon.pvp, "auto-double-hand", "After pop, switches to totem");
    }

    @Override
    public void onActivate() {
        wasHoldingTotem = mc.player != null && mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.gameMode == null) return;

        boolean holdingNow = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);

        if (wasHoldingTotem && !holdingNow) {
            int slot = findHotbarTotem();
            if (slot != -1) {
                mc.player.getInventory().setSelectedSlot(slot);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
        }

        wasHoldingTotem = holdingNow;
    }

    private int findHotbarTotem() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }
}
