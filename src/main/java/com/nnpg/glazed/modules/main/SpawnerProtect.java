package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.utils.GlazedWebhook;
import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedWebhook;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import java.time.Instant;
import java.util.*;

public class SpawnerProtect extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    private final Setting<Boolean> webhook = sgWebhook.add(new BoolSetting.Builder()
            .name("webhook")
            .description("Enable webhook notifications")
            .defaultValue(false)
            .build());

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
            .name("webhook-url")
            .description("Discord webhook URL for notifications")
            .defaultValue("")
            .visible(webhook::get)
            .build());

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
            .name("self-ping")
            .description("Ping yourself in the webhook message")
            .defaultValue(false)
            .visible(webhook::get)
            .build());

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
            .name("discord-id")
            .description("Your Discord user ID for pinging")
            .defaultValue("")
            .visible(() -> webhook.get() && selfPing.get())
            .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications")
            .description("Show chat feedback.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-range")
            .description("Range to check for remaining spawners")
            .defaultValue(16)
            .min(1)
            .max(50)
            .sliderMax(50)
            .build());

    private final Setting<Integer> emergencyDistance = sgGeneral.add(new IntSetting.Builder()
            .name("emergency-distance")
            .description("Distance in blocks where player triggers immediate disconnect (0 to disable).")
            .defaultValue(7)
            .min(0)
            .max(20)
            .sliderMax(20)
            .build());

    private final Setting<Integer> minDetectionRange = sgGeneral.add(new IntSetting.Builder()
            .name("min-detection-range")
            .description("Minimum distance to detect a player (ignore players closer than this).")
            .defaultValue(0)
            .min(0)
            .max(50)
            .sliderMax(50)
            .build());

    private final Setting<Integer> maxDetectionRange = sgGeneral.add(new IntSetting.Builder()
            .name("max-detection-range")
            .description("Maximum distance to detect a player.")
            .defaultValue(50)
            .min(1)
            .max(100)
            .sliderMax(100)
            .build());

    private final Setting<Integer> spawnerCheckDelay = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-check-delay-ms")
            .description("Delay in milliseconds before confirming all spawners are gone")
            .defaultValue(3000)
            .min(1000)
            .max(10000)
            .sliderMax(10000)
            .build());

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-timeout-ms")
            .description("Time in milliseconds before skipping a spawner that can't be mined")
            .defaultValue(4000)
            .min(4000)
            .max(30000)
            .sliderMax(30000)
            .build());

    private final Setting<Boolean> depositToEChest = sgGeneral.add(new BoolSetting.Builder()
            .name("deposit-to-echest")
            .description("Deposit spawners into ender chest after mining.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> enableWhitelist = sgWhitelist.add(new BoolSetting.Builder()
            .name("enable-whitelist")
            .description("Enable player whitelist (whitelisted players won't trigger protection)")
            .defaultValue(false)
            .build());

    private final Setting<List<String>> whitelistPlayers = sgWhitelist.add(new StringListSetting.Builder()
            .name("whitelisted-players")
            .description("List of player names to ignore")
            .defaultValue(new ArrayList<>())
            .visible(enableWhitelist::get)
            .build());

    private final Setting<List<Item>> depositBlacklist = sgGeneral.add(new ItemListSetting.Builder()
            .name("deposit-blacklist")
            .description("Items that will never be deposited into the ender chest.")
            .defaultValue(Arrays.asList(
                    Items.ENDER_PEARL,
                    Items.END_CRYSTAL,
                    Items.OBSIDIAN,
                    Items.RESPAWN_ANCHOR,
                    Items.GLOWSTONE,
                    Items.TOTEM_OF_UNDYING))
            .visible(depositToEChest::get)
            .build());

    private enum State {
        IDLE,
        GOING_TO_SPAWNERS,
        GOING_TO_CHEST,
        OPENING_CHEST,
        DEPOSITING_ITEMS,
        DISCONNECTING,
        WORLD_CHANGED_ONCE,
        WORLD_CHANGED_TWICE
    }

    private State currentState = State.IDLE;
    private String detectedPlayer = "";
    private long detectionTime = 0;
    private boolean spawnersMinedSuccessfully = false;
    private boolean itemsDepositedSuccessfully = false;
    private int tickCounter = 0;
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;

    private boolean sneaking = false;
    private BlockPos currentTarget = null;
    private long noSpawnerStartTime = -1;
    private long currentTargetStartTime = -1;

    private BlockPos targetChest = null;
    private int chestOpenAttempts = 0;
    private boolean emergencyDisconnect = false;
    private String emergencyReason = "";

    private Level trackedWorld = null;
    private int worldChangeCount = 0;
    private final int PLAYER_COUNT_THRESHOLD = 3;
    private float targetYaw, targetPitch;
    private boolean rotating = false;
    private final float ROTATION_SPEED = 8.0f;
    private long respawnWaitStart = -1;
    private final Set<BlockPos> invalidSpawners = new HashSet<>();
    public SpawnerProtect() {
        super(GlazedAddon.CATEGORY, "spawner-protect",
                "Breaks spawners and puts them in your inv when a player is detected");
    }

    @Override
    public void onActivate() {
        resetState();
        configureLegitMining();

        if (mc.level != null) {
            trackedWorld = mc.level;
            worldChangeCount = 0;
            if (notifications.get())
                info("SpawnerProtect activated - Monitoring world: " + mc.level.dimension().location());
            if (notifications.get())
                info("Monitoring for players...");
        }

        if (notifications.get())
            ChatUtils.warning(
                    "Make sure to have an empty inventory with only a silk touch pickaxe and an ender chest nearby!");
    }

    private void resetState() {
        currentState = State.IDLE;
        detectedPlayer = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        tickCounter = 0;
        transferDelayCounter = 0;
        lastProcessedSlot = -1;
        sneaking = false;
        currentTarget = null;
        noSpawnerStartTime = -1;
        currentTargetStartTime = -1;
        targetChest = null;
        chestOpenAttempts = 0;
        emergencyDisconnect = false;
        emergencyReason = "";
        invalidSpawners.clear();
        rotating = false;
    }

    private void configureLegitMining() {
        if (notifications.get())
            info("Manual mining mode activated");
    }

    private void disableAutoReconnectIfEnabled() {
        Module autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            if (notifications.get())
                info("AutoReconnect disabled due to player detection");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null)
            return;
        if (rotating) {
            float yaw = mc.player.getYRot();
            float pitch = mc.player.getXRot();

            float yawDiff = wrapDegrees(targetYaw - yaw);
            float pitchDiff = targetPitch - pitch;

            float newYaw = yaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
            float newPitch = pitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);

            mc.player.setYRot(newYaw);
            mc.player.setXRot(newPitch);

            if (Math.abs(yawDiff) < 1f && Math.abs(pitchDiff) < 1f) {
                rotating = false;
            }
        }

        if (currentState == State.GOING_TO_SPAWNERS) {
            mc.options.keyShift.setDown(true);
        } else {
            mc.options.keyShift.setDown(mc.options.keyShift.isDown());
        }

        tickCounter++;

        if (mc.level != trackedWorld) {
            handleWorldChange();
            return;
        }

        if (currentState == State.WORLD_CHANGED_ONCE) {
            return;
        }

        if (currentState == State.WORLD_CHANGED_TWICE) {
            currentState = State.IDLE;
            if (notifications.get())
                info("Returned to spawner world - resuming player monitoring");
        }

        if (checkEmergencyDisconnect()) {
            return;
        }

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        switch (currentState) {
            case IDLE:
                checkForPlayers();
                break;
            case GOING_TO_SPAWNERS:
                handleGoingToSpawners();
                break;
            case GOING_TO_CHEST:
                handleGoingToChest();
                break;
            case OPENING_CHEST:
                handleOpeningChest();
                break;
            case DEPOSITING_ITEMS:
                handleDepositingItems();
                break;
            case DISCONNECTING:
                handleDisconnecting();
                break;
            case WORLD_CHANGED_ONCE:
            case WORLD_CHANGED_TWICE:
                break;
        }
    }

    private void handleWorldChange() {
        worldChangeCount++;
        trackedWorld = mc.level;

        if (worldChangeCount == 1) {
            currentState = State.WORLD_CHANGED_ONCE;
            if (notifications.get())
                info("World changed (TP to spawn) - pausing player detection until return");
        } else if (worldChangeCount == 2) {
            currentState = State.WORLD_CHANGED_TWICE;
            worldChangeCount = 0;
            if (notifications.get())
                info("World changed (back to spawners) - will resume monitoring");
        }
    }

    private float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f)
            value -= 360.0f;
        if (value < -180.0f)
            value += 360.0f;
        return value;
    }

    private boolean checkEmergencyDisconnect() {
        if (emergencyDistance.get() <= 0)
            return false;

        long otherPlayers = mc.level.players().stream().filter(p -> p != mc.player).count();
        if (otherPlayers >= PLAYER_COUNT_THRESHOLD)
            return false;

        for (Player player : mc.level.players()) {
            if (player == mc.player || player == null || !(player instanceof AbstractClientPlayer))
                continue;

            String playerName = player.getName().getString();

            if (isPlayerWhitelisted(playerName)) {
                continue;
            }

            double distance = mc.player.distanceTo(player);
            if (distance <= emergencyDistance.get()) {
                if (notifications.get())
                    info("EMERGENCY: Player " + playerName + " came too close (" + String.format("%.1f", distance)
                            + " blocks)!");

                emergencyDisconnect = true;
                emergencyReason = "User " + playerName + " came too close";

                toggle();
                if (mc.level != null) {
                    mc.level.disconnect();
                }

                detectedPlayer = playerName;
                detectionTime = System.currentTimeMillis();

                disableAutoReconnectIfEnabled();

                currentState = State.DISCONNECTING;
                return true;
            }
        }
        return false;
    }

    private void checkForPlayers() {
        // 67
        long otherPlayers = mc.level.players().stream().filter(p -> p != mc.player).count();
        if (otherPlayers >= PLAYER_COUNT_THRESHOLD)
            return;

        for (Player player : mc.level.players()) {
            if (player == mc.player || player == null || !(player instanceof AbstractClientPlayer))
                continue;

            double distance = mc.player.distanceTo(player);
            if (distance < minDetectionRange.get() || distance > maxDetectionRange.get())
                continue;

            String playerName = player.getName().getString();

            if (isPlayerWhitelisted(playerName)) {
                continue;
            }

            detectedPlayer = playerName;
            detectionTime = System.currentTimeMillis();

            if (notifications.get())
                info("SpawnerProtect: Player detected at " + String.format("%.1f", distance) + " blocks - "
                        + detectedPlayer);

            disableAutoReconnectIfEnabled();

            currentState = State.GOING_TO_SPAWNERS;
            if (notifications.get())
                info("Player detected! Starting protection sequence...");

            break;
        }
    }

    private boolean isPlayerWhitelisted(String playerName) {
        //check global admin list first
        AdminList adminList = Modules.get().get(AdminList.class);
        if (adminList != null && adminList.isActive() && adminList.isAdmin(playerName)) {
            return true;
        }

        if (!enableWhitelist.get() || whitelistPlayers.get().isEmpty()) {
            return false;
        }

        return whitelistPlayers.get().stream()
                .anyMatch(whitelistedName -> whitelistedName.equalsIgnoreCase(playerName));
    }

    private FindItemResult findSilkTouchPickaxe() {
        return InvUtils.find(stack -> {
            if (!stack.is(ItemTags.PICKAXES))
                return false;

            var enchantments = stack.getEnchantments();
            for (var entry : enchantments.entrySet()) {
                if (entry.getKey().is(Enchantments.SILK_TOUCH))
                    return true;
            }
            return false;
        });
    }

    private void handleGoingToSpawners() {
        mc.options.keyShift.setDown(true);

        if (currentTarget != null && mc.level.getBlockState(currentTarget).getBlock() != Blocks.SPAWNER) {
            currentTarget = null;
            currentTargetStartTime = -1;
            stopBreaking();
            noSpawnerStartTime = -1;
            return;
        }

        if (currentTarget == null) {
            currentTarget = findNearestSpawner();

            if (currentTarget == null) {
                if (noSpawnerStartTime == -1) {
                    noSpawnerStartTime = System.currentTimeMillis();
                    if (notifications.get())
                        info("No spawners found, waiting " + spawnerCheckDelay.get() + "ms to confirm...");
                    return;
                }
                
                long elapsed = System.currentTimeMillis() - noSpawnerStartTime;
                if (elapsed < spawnerCheckDelay.get()) {
                    return;
                }
                
                invalidSpawners.clear();
                stopBreaking();
                currentState = State.GOING_TO_CHEST;
                noSpawnerStartTime = -1;
                if (notifications.get())
                    info("No more spawners in range after delay, moving to ender chest...");
                return;
            }
            
            currentTargetStartTime = System.currentTimeMillis();
            if (notifications.get())
                info("Found spawner at " + currentTarget + ", distance: " + 
                    String.format("%.1f", Math.sqrt(currentTarget.distToLowCornerSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ()))));
        }
        
        noSpawnerStartTime = -1;

        if (currentTargetStartTime != -1) {
            long timeTrying = System.currentTimeMillis() - currentTargetStartTime;
            if (timeTrying > spawnerTimeout.get()) {
                if (notifications.get())
                    info("Timeout mining spawner at " + currentTarget + " after " + spawnerTimeout.get() + "ms, skipping...");
                invalidSpawners.add(currentTarget);
                currentTarget = null;
                currentTargetStartTime = -1;
                stopBreaking();
                return;
            }
        }

        Direction side = getExposedFaceSide(currentTarget);
        
        lookAtBlock(currentTarget, side);

        if (mc.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(currentTarget)) {
            
            FindItemResult pickaxe = findSilkTouchPickaxe();
            if (!pickaxe.found()) {
                stopBreaking();
                currentState = State.GOING_TO_CHEST;
                if (notifications.get())
                    info("No silk touch pickaxe found, moving to ender chest...");
                return;
            }

            InvUtils.swap(pickaxe.slot(), true);

            mc.options.keyAttack.setDown(true);
            mc.gameMode.continueDestroyBlock(currentTarget, hit.getDirection());
        }
    }

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        double maxDistanceSq = spawnerRange.get() * spawnerRange.get();

        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.offset(-spawnerRange.get(), -spawnerRange.get(), -spawnerRange.get()),
                playerPos.offset(spawnerRange.get(), spawnerRange.get(), spawnerRange.get()))) {

            if (mc.level.getBlockState(pos).getBlock() != Blocks.SPAWNER) continue;
            if (invalidSpawners.contains(pos)) continue;

            double distanceSq = pos.distToLowCornerSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (distanceSq > maxDistanceSq) continue;

            if (distanceSq < nearestDistance) {
                nearestDistance = distanceSq;
                nearest = pos.immutable();
            }
        }

        return nearest;
    }
    private boolean hasLineOfSight(BlockPos pos, Direction side) {
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 targetPos = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));

        BlockHitResult result = mc.level.clip(new net.minecraft.world.level.ClipContext(
                eyePos,
                targetPos,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                mc.player
        ));

        if (result == null) return true; //big brain

        if (result.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && result.getBlockPos().equals(pos)) {
            return true;
        }
        
        if (result.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos hitPos = result.getBlockPos();
            if (mc.level.getBlockState(hitPos).isAir()) {
                return true;
            }
            if (!mc.level.getBlockState(hitPos).isCollisionShapeFullBlock(mc.level, hitPos)) {
                return true;
            }
            if (hitPos.equals(mc.player.blockPosition()) || hitPos.equals(mc.player.blockPosition().below())) {
                return true;
            }
        }

        return false;
    }
    private void lookAtBlock(BlockPos pos) {
        lookAtBlock(pos, Direction.UP);
    }

    private void lookAtBlock(BlockPos pos, Direction side) {
        Vec3 targetPos = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
        Vec3 playerPos = mc.player.getEyePosition();
        Vec3 dir = targetPos.subtract(playerPos).normalize();

        targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        targetPitch = (float) Math.toDegrees(-Math.asin(dir.y));
        rotating = true;
    }

    private Direction getExposedFaceSide(BlockPos pos) {
        BlockPos playerBlockPos = mc.player.blockPosition();
        if (pos.equals(playerBlockPos.below())) {
        }
        
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.relative(side);
            if (mc.level.getBlockState(neighbor).isAir()
                    || !mc.level.getBlockState(neighbor).isCollisionShapeFullBlock(mc.level, neighbor)) {
                return side;
            }
        }

        return Direction.UP;
    }

    private void stopBreaking() {
        mc.options.keyAttack.setDown(false);
    }

    private void handleGoingToChest() {
        if (!depositToEChest.get()) {
            currentState = State.DISCONNECTING;
            if (notifications.get())
                info("Deposit to ender chest disabled, disconnecting...");
            return;
        }

        if (targetChest == null) {
            targetChest = findNearestEnderChest();
            if (targetChest == null) {
                if (notifications.get())
                    info("No ender chest found nearby!");
                currentState = State.DISCONNECTING;
                return;
            }
            if (notifications.get())
                info("Found ender chest at " + targetChest);
        }

        moveTowardsBlock(targetChest);

        if (mc.player.blockPosition().distSqr(targetChest) <= 9) {
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
            if (notifications.get())
                info("Reached ender chest. Attempting to open...");
        }

        if (tickCounter > 600) {
            if (notifications.get())
                ChatUtils.error("Timed out trying to reach ender chest!");
            currentState = State.DISCONNECTING;
        }
    }

    private BlockPos findNearestEnderChest() {
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos nearestChest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.offset(-16, -8, -16),
                playerPos.offset(16, 8, 16))) {

            if (mc.level.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                double distance = pos.distToLowCornerSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestChest = pos.immutable();
                }
            }
        }

        return nearestChest;
    }

    private void moveTowardsBlock(BlockPos target) {
        Vec3 playerPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3 targetPos = Vec3.atCenterOf(target);
        Vec3 direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        mc.player.setYRot((float) yaw);

        KeyMapping.set(mc.options.keyUp.getDefaultKey(), true);
    }

    private void handleOpeningChest() {
        if (targetChest == null) {
            currentState = State.GOING_TO_CHEST;
            return;
        }

        if (sneaking) {
        }
        mc.options.keyShift.setDown(false);

        KeyMapping.set(mc.options.keyUp.getDefaultKey(), false);
        KeyMapping.set(mc.options.keyJump.getDefaultKey(), true);

        if (chestOpenAttempts < 20) {
            lookAtBlock(targetChest);
        }

        if (chestOpenAttempts % 5 == 0) {
            if (mc.gameMode != null && mc.player != null) {
                mc.gameMode.useItemOn(
                        mc.player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3.atCenterOf(targetChest),
                                Direction.UP,
                                targetChest,
                                false));
                if (notifications.get())
                    info("Right-clicking ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
            }
        }

        chestOpenAttempts++;

        if (mc.player.containerMenu instanceof ChestMenu) {
            KeyMapping.set(mc.options.keyJump.getDefaultKey(), false);
            currentState = State.DEPOSITING_ITEMS;
            lastProcessedSlot = -1;
            tickCounter = 0;
            if (notifications.get())
                info("Ender chest opened successfully! Made by GLZD ");
        }

        if (chestOpenAttempts > 200) {
            KeyMapping.set(mc.options.keyJump.getDefaultKey(), false);
            if (notifications.get())
                ChatUtils.error("Failed to open ender chest after multiple attempts!");
            currentState = State.DISCONNECTING;
        }
    }

    private void handleDepositingItems() {
        if (!depositToEChest.get()) {
            currentState = State.DISCONNECTING;
            if (notifications.get())
                info("Deposit to ender chest disabled, skipping deposit.");
            return;
        }

        mc.options.keyShift.setDown(false);

        if (mc.player.containerMenu instanceof ChestMenu) {
            ChestMenu handler = (ChestMenu) mc.player.containerMenu;

            if (!hasItemsToDeposit()) {
                itemsDepositedSuccessfully = true;
                if (notifications.get())
                    info("All items deposited successfully!");
                mc.player.closeContainer();
                transferDelayCounter = 10;
                currentState = State.DISCONNECTING;
                return;
            }

            transferItemsToChest(handler);

        } else {
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
        }

        if (tickCounter > 900) {
            if (notifications.get())
                ChatUtils.error("Timed out depositing items!");
            currentState = State.DISCONNECTING;
        }
    }

    private boolean isVitalItem(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == Items.AIR)
            return true;

        if (depositBlacklist.get().contains(stack.getItem()))
            return true;

        if (stack.getItem() == Items.ENDER_CHEST)
            return true;

        return false;
    }

    private boolean hasItemsToDeposit() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isVitalItem(stack)) {
                return true;
            }
        }
        return false;
    }

    private void transferItemsToChest(ChestMenu handler) {
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInventoryStart = chestSlots;

        if (isChestFull(handler, chestSlots)) {
            if (notifications.get())
                error("Ender chest is full! Disconnecting for safety.");
            currentState = State.DISCONNECTING;
            return;
        }

        for (int i = 0; i < 36; i++) {
            int slotId = playerInventoryStart + i;
            ItemStack stack = handler.getSlot(slotId).getItem();

            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) {
                depositSlot(handler, slotId, stack);
                return;
            }
        }

        for (int i = 0; i < 36; i++) {
            int slotId = playerInventoryStart + i;
            ItemStack stack = handler.getSlot(slotId).getItem();

            if (!stack.isEmpty() && !isVitalItem(stack)) {
                depositSlot(handler, slotId, stack);
                return;
            }
        }

        if (lastProcessedSlot >= playerInventoryStart) {
            lastProcessedSlot = playerInventoryStart - 1;
            transferDelayCounter = 3;
        }
    }

    private void depositSlot(ChestMenu handler, int slotId, ItemStack stack) {
        if (notifications.get())
            info("Transferring item from slot " + slotId + ": " + stack.getItem().toString());

        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(
                    handler.containerId,
                    slotId,
                    0,
                    ClickType.QUICK_MOVE,
                    mc.player);
        }

        lastProcessedSlot = slotId;
        transferDelayCounter = 2;
    }

    private boolean isChestFull(ChestMenu handler, int chestSlots) {
        for (int i = 0; i < chestSlots; i++) {
            if (handler.getSlot(i).getItem().isEmpty())
                return false;
        }
        return true;
    }

    private void handleDisconnecting() {
        KeyMapping.set(mc.options.keyUp.getDefaultKey(), false);
        KeyMapping.set(mc.options.keyJump.getDefaultKey(), false);

        sendWebhookNotification();

        if (emergencyDisconnect) {
            if (notifications.get())
                info("SpawnerProtect: " + emergencyReason + ". Successfully disconnected.");
        } else {
            if (notifications.get())
                info("SpawnerProtect: " + detectedPlayer + " detected. Successfully disconnected.");
        }

        if (mc.level != null) {
            mc.level.disconnect();
        }

        if (notifications.get())
            info("Disconnected due to player detection.");
        toggle();
    }

    private void sendWebhookNotification() {
        if (!webhook.get() || webhookUrl.get() == null || webhookUrl.get().trim().isEmpty()) {
            if (notifications.get())
                info("Webhook disabled or URL not configured.");
            return;
        }

        long discordTimestamp = detectionTime / 1000L;

        GlazedWebhook.to(webhookUrl.get())
            .username("Glazed Webhook")
            .ping(selfPing.get() ? discordId.get() : null)
            .title(emergencyDisconnect ? "SpawnerProtect Emergency Alert" : "SpawnerProtect Alert")
            .description(buildDescription(discordTimestamp))
            .color(emergencyDisconnect ? 16711680 : 16766720)
            .onError(message -> {
                if (notifications.get()) error("Failed to send webhook notification: " + message);
            })
            .send();
    }

    private String buildDescription(long discordTimestamp) {
        if (emergencyDisconnect) {
            return String.format(
                    "**Player Detected:** %s\n**Detection Time:** <t:%d:R>\n**Reason:** %s\n**Disconnected:** Yes",
                    detectedPlayer, discordTimestamp, emergencyReason);
        }

        return String.format(
                "**Player Detected:** %s\n**Detection Time:** <t:%d:R>\n**Spawners Mined:** %s\n**Items Deposited:** %s\n**Disconnected:** Yes",
                detectedPlayer, discordTimestamp,
                spawnersMinedSuccessfully ? "✅ Success" : "❌ Failed",
                itemsDepositedSuccessfully ? "✅ Success" : "❌ Failed");
    }

    @Override
    public void onDeactivate() {
        stopBreaking();
        KeyMapping.set(mc.options.keyUp.getDefaultKey(), false);
        KeyMapping.set(mc.options.keyJump.getDefaultKey(), false);
    }
}