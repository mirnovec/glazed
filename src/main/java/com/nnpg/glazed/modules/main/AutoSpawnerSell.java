package com.nnpg.glazed.modules.main;

import com.mojang.blaze3d.platform.InputConstants;
import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedSell;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import meteordevelopment.meteorclient.events.world.TickEvent;

public class AutoSpawnerSell extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> dropDelay = sgGeneral.add(new IntSetting.Builder()
        .name("drop-delay")
        .description("Delay in ticks between drops")
        .defaultValue(20)
        .min(0)
        .max(2400)
        .build()
    );

    private final Setting<Integer> pageAmount = sgGeneral.add(new IntSetting.Builder()
        .name("page-amount")
        .description("Number of pages to drop before selling")
        .defaultValue(2)
        .min(1)
        .max(10)
        .build()
    );

    private final Setting<Integer> pageSwitchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("page-switch-delay")
        .description("Delay between page switches (ticks)")
        .defaultValue(80)
        .min(0)
        .max(7200)
        .build()
    );

    private int delayCounter = 0;
    private int pageCounter = 0;
    private boolean isProcessing = false;
    private boolean isSelling = false;
    private boolean isPageSwitching = false;

    public AutoSpawnerSell() {
        super(GlazedAddon.CATEGORY, "auto-spawner-sell", "Automatically drops bones from spawner and sells them");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        AbstractContainerMenu handler = mc.player.containerMenu;

        if (pageCounter >= pageAmount.get()) {
            isSelling = true;
            pageCounter = 0;
            delayCounter = 40;
            return;
        }

        if (isSelling) {
            handleSelling(handler);
        } else {
            handleDropping(handler);
        }
    }

    // no ordering any more. open /sell, shove everything in, confirm, close
    private void handleSelling(AbstractContainerMenu handler) {
        if (!(handler instanceof ChestMenu container)) {
            GlazedSell.openSell();
            delayCounter = 20;
            return;
        }

        // the confirm can be a green pane or a Yes/No screen, and it asks twice
        if (GlazedSell.clickConfirm(container)) {
            delayCounter = 10;
            return;
        }

        if (GlazedSell.depositNext(container)) {
            delayCounter = 3;
            return;
        }

        // nothing left to put in and nothing to confirm, we're done
        GlazedSell.close();
        isSelling = false;
        delayCounter = 20;
    }

    private void handleDropping(AbstractContainerMenu handler) {
        if (!(handler instanceof ChestMenu)) {
            KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(1));
            delayCounter = 20;
            return;
        }

        NonNullList<ItemStack> stacks = handler.getItems();
        boolean allBones = true;

        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.getItem() != Items.BONE) {
                allBones = false;
                break;
            }
        }

        if (allBones) {
            mc.gameMode.handleContainerInput(handler.containerId, 52, 1, ContainerInput.THROW, mc.player);
            isProcessing = true;
            delayCounter = pageSwitchDelay.get();
            pageCounter++;
        } else if (isProcessing) {
            isProcessing = false;
            mc.gameMode.handleContainerInput(handler.containerId, 50, 0, ContainerInput.PICKUP, mc.player);
            delayCounter = 20;
        } else {
            isProcessing = false;
            if (pageCounter != 0) {
                pageCounter = 0;
                isSelling = true;
                delayCounter = 40;
                return;
            }
            mc.gameMode.handleContainerInput(handler.containerId, 45, 1, ContainerInput.THROW, mc.player);
            delayCounter = dropDelay.get();
        }
    }

    private Item getInventoryItem() {
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) return stack.getItem();
        }
        return Items.AIR;
    }

    private String getOrderCommand() {
        Item item = getInventoryItem();
        if (item == Items.BONE) return "Bones";
        return item.getName(new ItemStack(item)).getString();
    }
}
