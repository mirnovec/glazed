package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedShop;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;

public class ShulkerDropper extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions in ticks.")
        .defaultValue(1)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Integer> clicksPerBuy = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-buy")
        .description("How many times to click the shulker each pass.")
        .defaultValue(8)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> reopenDelay = sgGeneral.add(new IntSetting.Builder()
        .name("reopen-delay")
        .description("Ticks to wait after sending /shop before looking for the menu.")
        .defaultValue(20)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> dropBought = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-bought")
        .description("Throw the shulkers on the floor after buying them.")
        .defaultValue(true)
        .build()
    );

    private int delayCounter = 0;

    public ShulkerDropper() {
        super(GlazedAddon.CATEGORY, "shulker-dropper", "Automatically buys shulkers from shop and drops them.");
    }

    @Override
    public void onActivate() {
        delayCounter = 0;
    }

    @Override
    public void onDeactivate() {
        delayCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        ChestMenu handler = GlazedShop.openContainer();

        // one chest now, nothing to navigate
        if (handler == null) {
            ChatUtils.sendPlayerMsg("/shop");
            delayCounter = reopenDelay.get();
            return;
        }

        int shulkerSlot = GlazedShop.findItemSlot(handler, Items.SHULKER_BOX);

        if (shulkerSlot < 0) {
            // menu open but empty, or its not the shop
            delayCounter = Math.max(1, delay.get());
            return;
        }

        GlazedShop.spamClick(handler, shulkerSlot, clicksPerBuy.get());
        delayCounter = Math.max(1, delay.get());

        if (dropBought.get()) dropShulkers(handler);
    }

    // vro this used to send a raw DROP_ITEM packet which drops whatevr hotbar slot u had selected
    // so it was yeeting ur totem instead of the shulkers. wtf mane who wrote tiz
    private void dropShulkers(ChestMenu handler) {
        int containerSlots = GlazedShop.containerSlotCount(handler);

        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
            if (!handler.getSlot(slot).getItem().is(Items.SHULKER_BOX)) continue;

            // button 1 = drop the whole stack
            mc.gameMode.handleContainerInput(handler.containerId, slot, 1, ContainerInput.THROW, mc.player);
        }
    }
}
