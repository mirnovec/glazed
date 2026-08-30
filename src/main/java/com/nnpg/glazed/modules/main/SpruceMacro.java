package com.nnpg.glazed.modules.main;

import com.mojang.blaze3d.platform.InputConstants;
import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.settings.RandomBetweenIntSetting;
import com.nnpg.glazed.utils.GlazedLog;
import com.nnpg.glazed.utils.RandomBetweenInt;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Runs a 2x2 mega spruce farm on a dirt platform.
 *
 * Look at the platform and toggle it on: the block under your crosshair becomes the anchor, the 2x2
 * around it is locked in, and from there it loops - four saplings, bone meal until the trunk shows
 * up, then the axe into a log so the server's tree feller drops the rest.
 *
 * Everything goes through the game's own input path. The camera is the real camera. Breaking holds
 * the attack key and lets vanilla mine whatever the crosshair is on, which is also how it eats a
 * canopy that grew over your head - no special case, the leaves are simply what is in front. Placing
 * and bone mealing are use-key clicks. Hotbar changes set the selected slot the way the number keys
 * do, and restocking opens the real inventory screen and swaps a stack down with a hotbar press.
 *
 * That matters beyond looking human: driving continueDestroyBlock directly while the attack key was
 * up meant vanilla called stopDestroyBlock every tick, so every START we sent was followed by an
 * ABORT in the same tick and the server never counted the break.
 */
public class SpruceMacro extends Module {
    /** A mega spruce tops out around thirty blocks, so this covers the whole trunk. */
    private static final int TRUNK_SCAN = 32;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCamera = settings.createGroup("Camera");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    // ---------------------------------------------------------------- general

    private final Setting<Integer> axeSlot = sgGeneral.add(new IntSetting.Builder()
        .name("axe-slot")
        .description("Hotbar slot holding the tree axe. 0 picks the fastest tool for the block on its own.")
        .defaultValue(0)
        .min(0)
        .max(9)
        .sliderRange(0, 9)
        .build()
    );

    private final Setting<Boolean> restock = sgGeneral.add(new BoolSetting.Builder()
        .name("restock-hotbar")
        .description("When the hotbar runs dry, open the inventory and swap a stack down, the way you would by hand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearStumps = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-stumps")
        .description("If the axe does not take the whole tree, break what is left of the trunk instead of stalling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dumpLogs = sgGeneral.add(new BoolSetting.Builder()
        .name("dump-logs")
        .description("Throw logs out when the inventory fills up. Vanilla fills the hotbar first, so logs landing there are what starves the sapling and bone meal slots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dumpLitter = sgGeneral.add(new BoolSetting.Builder()
        .name("dump-litter")
        .description("Throw out the rest of the tree too - leaves, sticks, apples. Saplings, bone meal and tools are never thrown.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> freeSlotsMin = sgGeneral.add(new IntSetting.Builder()
        .name("free-slots-min")
        .description("Empty inventory slots to keep spare. Below this it stops between trees and clears the logs out.")
        .defaultValue(3)
        .min(1)
        .max(30)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> background = sgGeneral.add(new BoolSetting.Builder()
        .name("background-mode")
        .description("Keep running while you are in another window. Turns off vanilla's pause-on-lost-focus for as long as the module is on, and does inventory work without opening a screen, because opening one releases the mouse and the game will not re-grab it while unfocused.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Chat feedback: what it is doing, and what it is waiting on when it is stuck.")
        .defaultValue(true)
        .build()
    );

    // ---------------------------------------------------------------- camera

    private final Setting<Boolean> humanCamera = sgCamera.add(new BoolSetting.Builder()
        .name("human-camera")
        .description("Move the camera the way a hand does. Off snaps straight to the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RandomBetweenInt> turnTicks = sgCamera.add(new RandomBetweenIntSetting.Builder()
        .name("turn-ticks")
        .description("Ticks a full 90 degree turn takes. Short turns scale down from this, and every turn rolls fresh.")
        .defaultRange(5, 10)
        .range(1, 60)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Integer> overshoot = sgCamera.add(new IntSetting.Builder()
        .name("overshoot")
        .description("Percent a long turn flies past the target before it pulls back.")
        .defaultValue(9)
        .min(0)
        .max(40)
        .sliderRange(0, 25)
        .build()
    );

    private final Setting<Double> handShake = sgCamera.add(new DoubleSetting.Builder()
        .name("hand-shake")
        .description("Degrees of tremor riding on top of wherever the camera is pointed.")
        .defaultValue(0.35)
        .min(0.0)
        .max(3.0)
        .sliderRange(0.0, 1.5)
        .build()
    );

    private final Setting<Double> idleSway = sgCamera.add(new DoubleSetting.Builder()
        .name("idle-sway")
        .description("Degrees of slow drift while it is waiting rather than aiming.")
        .defaultValue(0.8)
        .min(0.0)
        .max(6.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    private final Setting<Double> chopSteady = sgCamera.add(new DoubleSetting.Builder()
        .name("chop-steady")
        .description("How much of the tremor and sway is left while the axe is swinging. Low keeps the crosshair on one block.")
        .defaultValue(0.15)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Boolean> glances = sgCamera.add(new BoolSetting.Builder()
        .name("idle-glances")
        .description("Occasionally look off somewhere else during a long wait, then come back.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> aimSpread = sgCamera.add(new DoubleSetting.Builder()
        .name("aim-spread")
        .description("How far off the middle of a block it aims, in block widths.")
        .defaultValue(0.26)
        .min(0.0)
        .max(0.45)
        .sliderRange(0.0, 0.45)
        .build()
    );

    // ---------------------------------------------------------------- timing

    private final Setting<Integer> settleTicks = sgTiming.add(new IntSetting.Builder()
        .name("settle-ticks")
        .description("Ticks to let the crosshair rest after a turn before clicking. The block pick and the rotation the server has both need a tick to catch up.")
        .defaultValue(2)
        .min(0)
        .max(20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<RandomBetweenInt> placeDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("place-delay")
        .description("Ticks between sapling placements.")
        .defaultRange(5, 11)
        .range(1, 200)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<RandomBetweenInt> bonemealDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("bonemeal-delay")
        .description("Ticks between bone meal clicks. Vanilla holds a four tick cooldown of its own on top of this.")
        .defaultRange(3, 7)
        .range(1, 200)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<RandomBetweenInt> cycleGap = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("cycle-gap")
        .description("Ticks to stand there after the tree drops, before the next four saplings go down.")
        .defaultRange(14, 34)
        .range(1, 400)
        .sliderRange(1, 120)
        .build()
    );

    private final Setting<RandomBetweenInt> screenDelay = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("inventory-delay")
        .description("Ticks between opening the inventory, moving the stack, and closing it again.")
        .defaultRange(4, 9)
        .range(1, 100)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Integer> growTimeout = sgTiming.add(new IntSetting.Builder()
        .name("grow-timeout")
        .description("Ticks of bone mealing with no trunk before it stops and says so.")
        .defaultValue(900)
        .min(100)
        .max(12000)
        .sliderRange(200, 3000)
        .build()
    );

    private final Setting<Integer> breakTimeout = sgTiming.add(new IntSetting.Builder()
        .name("break-timeout")
        .description("Ticks on one target log before it lets go and lines the shot up again.")
        .defaultValue(120)
        .min(20)
        .max(2000)
        .sliderRange(20, 400)
        .build()
    );

    private final Setting<Integer> stuckTicks = sgTiming.add(new IntSetting.Builder()
        .name("stuck-ticks")
        .description("Ticks the crosshair may sit on one block that refuses to break before it is written off and worked around. This is what stops a ghost block from eating the session.")
        .defaultValue(50)
        .min(10)
        .max(600)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> breakRetries = sgTiming.add(new IntSetting.Builder()
        .name("break-retries")
        .description("Goes at the same log before it gives up on the cycle.")
        .defaultValue(6)
        .min(1)
        .max(30)
        .sliderRange(1, 15)
        .build()
    );

    private final Setting<RandomBetweenInt> breakConfirm = sgTiming.add(new RandomBetweenIntSetting.Builder()
        .name("break-confirm")
        .description("Ticks to watch the spot after a log vanishes, in case the server puts it back.")
        .defaultRange(12, 22)
        .range(1, 200)
        .sliderRange(4, 60)
        .build()
    );

    private final Setting<Integer> stuckBackoff = sgTiming.add(new IntSetting.Builder()
        .name("stuck-backoff")
        .description("Ticks to sit still after something went wrong, so a bad cycle cannot turn into click spam.")
        .defaultValue(200)
        .min(20)
        .max(6000)
        .sliderRange(40, 1200)
        .build()
    );

    private final Setting<Integer> timingJitter = sgTiming.add(new IntSetting.Builder()
        .name("timing-jitter")
        .description("Randomly varies every delay by up to this percent.")
        .defaultValue(25)
        .min(0)
        .max(90)
        .sliderRange(0, 60)
        .build()
    );

    // ---------------------------------------------------------------- debug

    private final Setting<Boolean> debugLog = sgDebug.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write everything to .minecraft/logs/glazed-spruce-macro.log - every swing, every block action packet, and every block the server hands back.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugTicks = sgDebug.add(new BoolSetting.Builder()
        .name("debug-ticks")
        .description("Add a line per tick while the axe is swinging. Noisy, but it shows the crosshair and the break stage moving.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debugChat = sgDebug.add(new BoolSetting.Builder()
        .name("debug-chat")
        .description("Mirror the result line of every swing into chat as well.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> freezeWhileMining = sgDebug.add(new BoolSetting.Builder()
        .name("freeze-while-mining")
        .description("Pin the camera exactly where it was when the swing started, for as long as the block is breaking.")
        .defaultValue(true)
        .build()
    );

    private enum State { PLACE, GROW, BREAK, SETTLE, RESTOCK, DUMP, BLOCKED }

    private enum Restock { OPEN, MOVE, CLOSE }

    private enum Dump { OPEN, THROW, CLOSE }

    private final Random random = new Random();
    private final Map<String, Integer> lastSaid = new HashMap<>();
    private final GlazedLog log = new GlazedLog("spruce-macro");

    // the farm
    private BlockPos[] soil = null;
    private BlockPos[] sap = null;
    private State state = State.PLACE;
    private int delayCounter = 0;
    private int tickCount = 0;
    private int trees = 0;

    // phase bookkeeping
    private BlockPos actionTarget = null;
    private boolean targetIsFace = false;
    private int aimFails = 0;
    private int placeTries = 0;
    private int growTicks = 0;
    private int settled = 0;
    private int nextPlaceAt = 0;

    // breaking
    private BlockPos underCrosshair = null;
    private int underTicks = 0;
    private int breakTicks = 0;
    private int retries = 0;
    private boolean attackDown = false;
    private boolean screenless = false;
    private Boolean pauseOnFocusLossWas = null;
    private boolean awaitingConfirm = false;
    private int confirmTicks = 0, confirmFor = 0;
    private BlockPos brokenPos = null;
    private boolean stumpWarned = false;
    private final Set<BlockPos> dead = new HashSet<>();

    // restocking
    private Dump dumpStep = Dump.OPEN;
    private State dumpReturn = State.PLACE;
    private int dumped = 0;

    private Restock restockStep = Restock.OPEN;
    private Item restockItem = null;
    private String restockLabel = "";
    private State restockReturn = State.PLACE;

    // camera
    private double camYaw, camPitch;
    private double srcYaw, srcPitch, dYaw, dPitch, ovYaw, ovPitch;
    private int turnTick, turnLen;
    private boolean turning = false;
    private double shakeYaw, shakePitch;
    private double swayPhase;
    private int glanceHold = 0;
    private Vec3 glanceReturn = null;
    private double lockYaw, lockPitch, devYaw, devPitch;

    // diagnostics
    private BlockPos watchMin = null, watchMax = null;
    private BlockPos swingPos = null;
    private int swingAge = 0;
    private boolean swingInFlight = false;
    private volatile int lastAckSeq = -1;
    private int revertsSeen = 0;

    public SpruceMacro() {
        super(GlazedAddon.CATEGORY, "spruce-macro", "Look at a 2x2 dirt platform and toggle on: plants four spruce saplings, bone meals them into a mega spruce, and chops a log so the tree axe takes the rest.");
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onActivate() {
        resetRun();

        if (mc.player == null || mc.level == null) {
            error("No world.");
            toggle();
            return;
        }

        BlockPos looked = lookedAtBlock();
        if (looked == null) {
            error("Look at the dirt platform before you turn this on.");
            toggle();
            return;
        }

        if (!isSoil(looked)) {
            error("%s is not something a sapling grows on. Look at the dirt.", nameOf(looked));
            toggle();
            return;
        }

        BlockPos[] square = findSquare(looked);
        if (square == null) {
            error("Could not find a clear 2x2 of dirt around that block - is one corner covered or missing?");
            toggle();
            return;
        }

        soil = square;
        sap = new BlockPos[4];
        for (int i = 0; i < 4; i++) sap[i] = soil[i].above();

        watchMin = new BlockPos(Math.min(soil[0].getX(), soil[3].getX()) - 2, soil[0].getY() - 2, Math.min(soil[0].getZ(), soil[3].getZ()) - 2);
        watchMax = new BlockPos(Math.max(soil[0].getX(), soil[3].getX()) + 2, soil[0].getY() + TRUNK_SCAN, Math.max(soil[0].getZ(), soil[3].getZ()) + 2);

        camYaw = mc.player.getYRot();
        camPitch = mc.player.getXRot();

        if (debugLog.get()) {
            log.open(String.format("spruce-macro on, farm %d %d %d, reach %.3f", soil[0].getX(), soil[0].getY(), soil[0].getZ(), reach()));
            info("Logging to %s", log.path());
        }

        if (background.get()) {
            // the pause screen is the only thing that actually stops this when you tab away: it takes
            // the mouse grab with it, and grabMouse refuses to run while the window is inactive
            pauseOnFocusLossWas = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;
        }

        info("Farm locked at %d %d %d. Watching a 2x2.", soil[0].getX(), soil[0].getY(), soil[0].getZ());
        state = State.PLACE;
    }

    @Override
    public void onDeactivate() {
        releaseKeys();
        closeScreen();
        if (pauseOnFocusLossWas != null) {
            mc.options.pauseOnLostFocus = pauseOnFocusLossWas;
            pauseOnFocusLossWas = null;
        }
        log.line("spruce-macro off after %d trees, %d reverts, %d dead blocks", trees, revertsSeen, dead.size());
        log.close();
        watchMin = null;
        watchMax = null;
        resetRun();
    }

    @Override
    public String getInfoString() {
        if (soil == null) return "no farm";
        return switch (state) {
            case PLACE -> "planting";
            case GROW -> "bone mealing";
            case BREAK -> "chopping";
            case SETTLE -> "waiting";
            case RESTOCK -> "restocking";
            case DUMP -> "dumping logs";
            case BLOCKED -> "stuck";
        };
    }

    private void resetRun() {
        state = State.PLACE;
        delayCounter = 0;
        actionTarget = null;
        targetIsFace = false;
        aimFails = 0;
        placeTries = 0;
        growTicks = 0;
        settled = 0;
        nextPlaceAt = 0;
        underCrosshair = null;
        underTicks = 0;
        breakTicks = 0;
        retries = 0;
        awaitingConfirm = false;
        confirmTicks = 0;
        brokenPos = null;
        stumpWarned = false;
        swingPos = null;
        swingInFlight = false;
        turning = false;
        glanceHold = 0;
        glanceReturn = null;
        dumpStep = Dump.OPEN;
        dumped = 0;
        restockStep = Restock.OPEN;
        dead.clear();
        lastSaid.clear();
    }

    // ---------------------------------------------------------------- input, the way a player makes it

    private InputConstants.Key keyOf(KeyMapping mapping) {
        return InputConstants.getKey(mapping.saveString());
    }

    /**
     * Hold or release the attack button. The press is a real click, so vanilla runs startAttack for it,
     * and the hold is re-asserted every tick because opening any screen calls KeyMapping.releaseAll and
     * would otherwise drop the button underneath us.
     */
    private void holdAttack(boolean down) {
        InputConstants.Key key = keyOf(mc.options.keyAttack);

        if (down) {
            KeyMapping.set(key, true);
            if (!attackDown) {
                KeyMapping.click(key);
                if (log.isOpen()) log.line("  HOLD  attack");
            }
        } else {
            KeyMapping.set(key, false);
            if (attackDown && log.isOpen()) log.line("  REL   attack");
        }
        attackDown = down;
    }

    /** One right click. Vanilla's startUseItem picks it up on the next handleKeybinds and uses mc.hitResult. */
    private void clickUse() {
        InputConstants.Key key = keyOf(mc.options.keyUse);
        KeyMapping.set(key, true);
        KeyMapping.click(key);
        KeyMapping.set(key, false);
    }

    private void releaseKeys() {
        if (attackDown) {
            KeyMapping.set(keyOf(mc.options.keyAttack), false);
            attackDown = false;
        }
        KeyMapping.set(keyOf(mc.options.keyUse), false);
    }

    /** What pressing a number key does. The carried-item packet rides out with the next action. */
    private void selectSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    private void closeScreen() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
    }

    /**
     * Vanilla runs handleKeybinds only with no screen up. Focus is not part of it, so use-clicks keep
     * working in another window.
     */
    private boolean keysWork() {
        return mc.screen == null;
    }

    /** continueAttack additionally wants the mouse grab, which only a screen ever takes away. */
    private boolean attackWorks() {
        return keysWork() && mc.mouseHandler.isMouseGrabbed();
    }

    /** Inventory work skips the visual screen when we are not the focused window - see background-mode. */
    private boolean wantScreen() {
        return !background.get() || mc.isWindowActive();
    }

    // ---------------------------------------------------------------- the packet tap

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof ServerboundPlayerActionPacket p)) return;
        if (!log.isOpen()) return;

        switch (p.getAction()) {
            case START_DESTROY_BLOCK -> log.line("  -> START  %s face=%s seq=%d", fmt(p.getPos()), p.getDirection(), p.getSequence());
            case STOP_DESTROY_BLOCK -> log.line("  -> STOP   %s face=%s seq=%d", fmt(p.getPos()), p.getDirection(), p.getSequence());
            case ABORT_DESTROY_BLOCK -> log.line("  -> ABORT  %s", fmt(p.getPos()));
            default -> {
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!log.isOpen()) return;

        if (event.packet instanceof ClientboundBlockChangedAckPacket ack) {
            lastAckSeq = ack.sequence();
            log.line("  <- ACK    seq=%d", ack.sequence());
        } else if (event.packet instanceof ClientboundBlockUpdatePacket up) {
            if (inWatch(up.getPos())) log.line("  <- SET    %s = %s", fmt(up.getPos()), up.getBlockState().getBlock().getName().getString());
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((pos, st) -> {
                if (inWatch(pos)) log.line("  <- SET*   %s = %s", fmt(pos), st.getBlock().getName().getString());
            });
        }
    }

    /** Read from the network thread, so it only touches the two immutable corners. */
    private boolean inWatch(BlockPos pos) {
        BlockPos lo = watchMin, hi = watchMax;
        if (lo == null || hi == null) return false;
        return pos.getX() >= lo.getX() && pos.getX() <= hi.getX()
            && pos.getZ() >= lo.getZ() && pos.getZ() <= hi.getZ()
            && pos.getY() >= lo.getY() && pos.getY() <= hi.getY();
    }

    private String fmt(BlockPos pos) {
        return String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ());
    }

    private String nameOf(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock().getName().getString();
    }

    // ---------------------------------------------------------------- tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
        if (soil == null || sap == null) return;

        tickCount++;
        tickCamera();

        if (state != State.BREAK && attackDown) holdAttack(false);

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        if (state != State.RESTOCK && state != State.DUMP && !keysWork()) {
            holdAttack(false);
            say("screenup", "A screen is open - nothing runs until it is closed.");
            delayCounter = jitter(20);
            return;
        }

        if (state == State.BREAK && !attackWorks()) {
            holdAttack(false);
            say("ungrabbed", "The mouse grab is gone, so the game will not swing. Click back into this window once and it carries on.");
            delayCounter = jitter(40);
            return;
        }

        switch (state) {
            case PLACE -> tickPlace();
            case GROW -> tickGrow();
            case BREAK -> tickBreak();
            case SETTLE -> tickSettle();
            case RESTOCK -> tickRestock();
            case DUMP -> tickDump();
            case BLOCKED -> {
                say("unblock", "Trying again.");
                aimFails = 0;
                placeTries = 0;
                growTicks = 0;
                retries = 0;
                awaitingConfirm = false;
                state = State.PLACE;
            }
        }
    }

    // ---------------------------------------------------------------- planting

    private void tickPlace() {
        if (!platformIntact()) {
            blocked("The 2x2 is not dirt any more - the platform got dug up or covered.");
            return;
        }

        List<BlockPos> pending = new ArrayList<>();
        for (BlockPos p : sap) {
            BlockState s = mc.level.getBlockState(p);
            if (s.is(Blocks.SPRUCE_SAPLING)) continue;
            if (isLog(s)) {
                startBreaking();
                return;
            }
            if (!s.isAir()) {
                // something grew or fell onto the platform. it is in the way, so take it out
                say("clutter", "%s on the platform at %s, clearing it.", nameOf(p), fmt(p));
                actionTarget = null;
                startBreaking();
                return;
            }
            pending.add(p);
        }

        if (pending.isEmpty()) {
            say("planted", "Four saplings down, bone mealing.");
            growTicks = 0;
            clearAim();
            state = State.GROW;
            return;
        }

        Integer slot = handSlot(Items.SPRUCE_SAPLING, "spruce saplings", State.PLACE);
        if (slot == null) return;
        selectSlot(slot);

        // far corners first, so a sapling already down is never between the eye and the next one
        Vec3 eye = mc.player.getEyePosition();
        pending.sort((a, b) -> Double.compare(centre(b).distanceToSqr(eye), centre(a).distanceToSqr(eye)));
        BlockPos ground = pending.getFirst().below();

        if (!ground.equals(actionTarget) || !targetIsFace) {
            aimAtFace(ground, Direction.UP);
            return;
        }
        if (!aimReady()) return;

        // the turn to the next corner runs *during* the click cooldown rather than after it, which is
        // what a hand does - the cooldown only gates the click, never the aim
        if (tickCount < nextPlaceAt) return;

        BlockHitResult hit = crosshair();
        if (hit == null || !hit.getBlockPos().equals(ground) || hit.getDirection() != Direction.UP) {
            nudge("place");
            return;
        }

        clickUse();
        placeTries++;
        nextPlaceAt = tickCount + jitter(placeDelay.get().getRandom());
        if (log.isOpen()) log.line("PLACE  use on %s", fmt(ground));

        if (placeTries > 10) {
            blocked("Ten tries and the sapling at %s will not stick.", fmt(pending.getFirst()));
            return;
        }

        clearAim();
    }

    // ---------------------------------------------------------------- growing

    private void tickGrow() {
        growTicks++;

        for (BlockPos p : sap) {
            if (isLog(mc.level.getBlockState(p))) {
                say("grew", "Tree is up. Taking a log out of the trunk.");
                startBreaking();
                return;
            }
        }

        for (BlockPos p : sap) {
            if (!mc.level.getBlockState(p).is(Blocks.SPRUCE_SAPLING)) {
                say("regrow", "A sapling is gone from %s, replanting.", fmt(p));
                placeTries = 0;
                clearAim();
                state = State.PLACE;
                return;
            }
        }

        if (growTicks > growTimeout.get()) {
            blocked("Bone mealed for %d ticks and nothing grew. A 2x2 spruce needs open space above the platform.", growTicks);
            return;
        }

        Integer slot = handSlot(Items.BONE_MEAL, "bone meal", State.GROW);
        if (slot == null) return;
        selectSlot(slot);

        // one sapling carries the whole 2x2, so pick it once and stay on it
        if (actionTarget == null || targetIsFace || !mc.level.getBlockState(actionTarget).is(Blocks.SPRUCE_SAPLING)) {
            BlockPos chosen = pickSapling();
            if (chosen == null) {
                blocked("None of the four saplings are in reach from where you are standing.");
                return;
            }
            aimAtBlock(chosen, 0.6);
            return;
        }
        if (!aimReady()) return;

        BlockHitResult hit = crosshair();
        if (hit == null || !hit.getBlockPos().equals(actionTarget)) {
            nudge("bonemeal");
            return;
        }

        clickUse();
        delayCounter = jitter(bonemealDelay.get().getRandom());
    }

    // ---------------------------------------------------------------- chopping

    private void startBreaking() {
        clearAim();
        breakTicks = 0;
        retries = 0;
        underCrosshair = null;
        underTicks = 0;
        awaitingConfirm = false;
        stumpWarned = false;
        state = State.BREAK;
    }

    private void tickBreak() {
        if (awaitingConfirm) {
            tickBreakConfirm();
            return;
        }

        // pick something to take down
        if (actionTarget == null) {
            BlockPos next = nextTarget();
            if (next == null) {
                finishCycle();
                return;
            }
            breakTicks = 0;
            underCrosshair = null;
            underTicks = 0;
            aimAtBlock(next, 0.3);
            return;
        }

        BlockState st = mc.level.getBlockState(actionTarget);
        if (st.isAir()) {
            holdAttack(false);
            endSwing("client-broke");
            brokenPos = actionTarget;
            awaitingConfirm = true;
            confirmTicks = 0;
            confirmFor = breakConfirm.get().getRandom();
            preAimNextPlant();
            return;
        }

        Integer slot = axeHotbarSlot(st);
        if (slot == null) {
            blocked("No axe in the hotbar - set axe-slot or put one there.");
            return;
        }
        selectSlot(slot);

        if (!aimReady()) {
            holdAttack(false);
            return;
        }

        // hold the button and let the game mine whatever the crosshair is on. leaves in the way are
        // not a special case, they are simply the next thing in front of the axe
        holdAttack(true);

        BlockPos under = hitBlock();
        if (under == null) {
            underCrosshair = null;
            underTicks = 0;
        } else if (under.equals(underCrosshair)) {
            underTicks++;
        } else {
            if (underCrosshair != null) endSwing("moved-on");
            underCrosshair = under;
            underTicks = 0;
            beginSwing(under);
        }

        if (debugTicks.get() && log.isOpen() && under != null) {
            log.line("  tick %2d on %s %s stage=%d under=%d yaw=%.2f pitch=%.2f",
                breakTicks, fmt(under), nameOf(under), mc.gameMode.getDestroyStage(), underTicks, mc.player.getYRot(), mc.player.getXRot());
        }
        trackRotation();
        breakTicks++;

        // a block that will not die - a ghost, or something the server refuses - must not eat the run
        if (under != null && underTicks > stuckTicks.get()) {
            holdAttack(false);
            endSwing("WONT-BREAK");
            dead.add(under);
            say("dead", "%s at %s will not break, working around it.", nameOf(under), fmt(under));
            if (log.isOpen()) log.line("DEAD   %s %s after %d ticks under the crosshair", fmt(under), nameOf(under), underTicks);
            retarget();
            return;
        }

        if (breakTicks > breakTimeout.get()) {
            holdAttack(false);
            endSwing("timeout");
            breakTicks = 0;
            if (!retry("The log is not coming down", actionTarget)) return;
            clearAim();
        }
    }

    /** A log vanishing on the client is not a break. Watch the spot, and swing again if it comes back. */
    private void tickBreakConfirm() {
        confirmTicks++;

        if (!mc.level.getBlockState(brokenPos).isAir()) {
            revertsSeen++;
            if (log.isOpen()) log.line("REVERT %s came back as %s", fmt(brokenPos), nameOf(brokenPos));
            awaitingConfirm = false;
            breakTicks = 0;
            if (!retry("The server put the block back", brokenPos)) return;
            clearAim();
            return;
        }

        if (confirmTicks < confirmFor) return;

        awaitingConfirm = false;
        retries = 0;

        BlockPos leftover = nextTarget();
        if (leftover != null) {
            clearAim();
            if (clearStumps.get()) {
                if (!stumpWarned) {
                    say("stump", "The axe left part of the tree standing, taking it down myself.");
                    stumpWarned = true;
                }
                breakTicks = 0;
                aimAtBlock(leftover, 0.3);
                return;
            }
            say("leftlogs", "Part of the tree is still up and clear-stumps is off.");
        }

        finishCycle();
    }

    /** Give up on the current target and find another angle into the tree. */
    private void retarget() {
        clearAim();
        breakTicks = 0;
        underCrosshair = null;
        underTicks = 0;

        BlockPos next = nextTarget();
        if (next == null) {
            blocked("Nothing left in reach that will break - %d blocks written off this cycle.", dead.size());
            return;
        }
        aimAtBlock(next, 0.3);
    }

    private boolean retry(String what, BlockPos pos) {
        retries++;
        if (retries > breakRetries.get()) {
            blocked("%s at %s, %d tries in.", what, fmt(pos), retries - 1);
            return false;
        }
        say("retry" + retries, "%s at %s (try %d of %d), swinging again.", what, fmt(pos), retries, breakRetries.get());
        delayCounter = jitter(8);
        return true;
    }

    private void finishCycle() {
        holdAttack(false);
        if (log.isOpen()) log.line("CYCLE  tree %d down, %d dead blocks this cycle", trees + 1, dead.size());
        trees++;
        say("felled", "Tree %d down.", trees);
        retries = 0;
        stumpWarned = false;
        dead.clear();
        delayCounter = jitter(cycleGap.get().getRandom());
        state = State.SETTLE;
    }

    // ---------------------------------------------------------------- between cycles

    private void tickSettle() {
        holdAttack(false);

        BlockPos leftover = nextTarget();
        if (leftover != null && clearStumps.get()) {
            startBreaking();
            return;
        }

        for (BlockPos p : sap) {
            BlockState s = mc.level.getBlockState(p);
            if (!s.isAir() && !s.is(Blocks.SPRUCE_SAPLING)) {
                say("litter", "Waiting on %s at %s to clear before replanting.", nameOf(p), fmt(p));
                delayCounter = jitter(60);
                return;
            }
        }

        if (!platformIntact()) {
            blocked("The 2x2 is not dirt any more.");
            return;
        }

        if (needsDump()) {
            startDump(State.SETTLE);
            return;
        }

        if (countItem(Items.SPRUCE_SAPLING) < 4) {
            info("Fewer than four spruce saplings left. Stopping.");
            toggle();
            return;
        }

        placeTries = 0;
        state = State.PLACE;
    }

    /**
     * The confirm window and the gap after a fell are dead time the head does not have to spend still.
     * Line up the first corner while the server is still making its mind up about the log, so planting
     * starts on the tick the wait ends instead of a turn later.
     */
    private void preAimNextPlant() {
        if (!platformIntact()) return;

        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDist = -1.0;

        for (BlockPos p : sap) {
            if (!mc.level.getBlockState(p).isAir()) return;   // tree still standing, nothing to line up
            double d = centre(p).distanceToSqr(eye);
            if (d > bestDist) {
                bestDist = d;
                best = p;
            }
        }
        if (best == null) return;

        // far corner first, matching the order planting will ask for
        aimAtFace(best.below(), Direction.UP);
    }

    // ---------------------------------------------------------------- restocking, through the real screen

    private void tickRestock() {
        switch (restockStep) {
            case OPEN -> {
                if (screenOpen()) {
                    restockStep = Restock.MOVE;
                    delayCounter = jitter(screenDelay.get().getRandom());
                    return;
                }
                holdAttack(false);
                openInventory();
                if (log.isOpen()) log.line("RESTOCK open inventory for %s (screenless=%s)", restockLabel, screenless);
                delayCounter = jitter(screenDelay.get().getRandom());
            }
            case MOVE -> {
                if (!screenOpen()) {
                    restockStep = Restock.OPEN;
                    return;
                }

                FindItemResult found = InvUtils.find(restockItem);
                if (!found.found()) {
                    closeScreen();
                    info("No %s left anywhere in the inventory. Stopping.", restockLabel);
                    toggle();
                    return;
                }
                if (found.isHotbar()) {
                    restockStep = Restock.CLOSE;
                    return;
                }

                int free = freeHotbarSlot();
                if (free < 0) {
                    // almost always logs that landed in the hotbar on pickup. clear them and come back
                    if (dumpLogs.get() && firstJunkSlot() >= 0) {
                        say("hotbarfull", "Hotbar is full, clearing logs out of it first.");
                        dumpStep = Dump.THROW;
                        dumpReturn = State.RESTOCK;
                        dumped = 0;
                        restockStep = Restock.MOVE;
                        state = State.DUMP;
                        return;
                    }
                    closeScreen();
                    blocked("Hotbar is full, no free slot to move %s into.", restockLabel);
                    return;
                }

                // hovering the stack and pressing a hotbar number - one SWAP, exactly what a hand sends
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slotIdOf(found.slot()), free, ContainerInput.SWAP, mc.player);
                say("restock", "Moved %s down to hotbar slot %d.", restockLabel, free + 1);
                if (log.isOpen()) log.line("RESTOCK swap inv slot %d -> hotbar %d", found.slot(), free);
                restockStep = Restock.CLOSE;
                delayCounter = jitter(screenDelay.get().getRandom());
            }
            case CLOSE -> {
                closeInventory();
                if (log.isOpen()) log.line("RESTOCK closed");
                clearAim();
                state = restockReturn;
                delayCounter = jitter(screenDelay.get().getRandom());
            }
        }
    }

    // ---------------------------------------------------------------- dumping, through the real screen

    /** True when it is worth stopping to clear out. */
    private boolean needsDump() {
        if (!dumpLogs.get()) return false;

        int free = 0;
        boolean junkInHotbar = false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                free++;
                continue;
            }
            // a log sitting in the hotbar is the real problem: vanilla fills the hotbar first, so it
            // takes the slot the saplings or the bone meal need
            if (i < 9 && isJunk(stack)) junkInHotbar = true;
        }

        if (junkInHotbar) return true;
        if (free >= freeSlotsMin.get()) return false;
        return firstJunkSlot() >= 0;
    }

    private boolean isJunk(ItemStack stack) {
        if (stack.isEmpty()) return false;

        Item item = stack.getItem();
        if (item == Items.BONE_MEAL) return false;
        if (!(item instanceof BlockItem block)) {
            return dumpLitter.get() && (item == Items.STICK || item == Items.APPLE);
        }

        BlockState st = block.getBlock().defaultBlockState();
        if (st.is(BlockTags.SAPLINGS)) return false;
        if (st.is(BlockTags.LOGS)) return true;
        return dumpLitter.get() && st.is(BlockTags.LEAVES);
    }

    private int firstJunkSlot() {
        for (int i = 0; i < 36; i++) {
            if (isJunk(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private void startDump(State back) {
        dumpReturn = back;
        dumpStep = Dump.OPEN;
        dumped = 0;
        state = State.DUMP;
    }

    private void tickDump() {
        switch (dumpStep) {
            case OPEN -> {
                if (screenOpen()) {
                    dumpStep = Dump.THROW;
                    delayCounter = jitter(screenDelay.get().getRandom());
                    return;
                }
                holdAttack(false);
                openInventory();
                if (log.isOpen()) log.line("DUMP   open inventory (screenless=%s)", screenless);
                delayCounter = jitter(screenDelay.get().getRandom());
            }
            case THROW -> {
                if (!screenOpen()) {
                    dumpStep = Dump.OPEN;
                    return;
                }

                int slot = firstJunkSlot();
                if (slot < 0) {
                    dumpStep = Dump.CLOSE;
                    return;
                }

                // hovering a stack and hitting ctrl+Q - one THROW with button 1 is the whole stack
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slotIdOf(slot), 1, ContainerInput.THROW, mc.player);
                dumped++;
                if (log.isOpen()) log.line("DUMP   threw slot %d", slot);

                if (dumped > 40) {
                    dumpStep = Dump.CLOSE;
                    return;
                }
                delayCounter = jitter(3);
            }
            case CLOSE -> {
                // a dump that started inside a restock hands the open screen straight back to it
                if (dumpReturn != State.RESTOCK) closeInventory();
                if (dumped > 0) say("dumped", "Threw out %d stacks to make room.", dumped);
                if (log.isOpen()) log.line("DUMP   closed after %d stacks", dumped);
                if (dumpReturn != State.RESTOCK) clearAim();
                state = dumpReturn;
                delayCounter = jitter(screenDelay.get().getRandom());
            }
        }
    }

    /**
     * Open the player's inventory for a stack move.
     *
     * The server is never told about the player's own inventory being opened - there is no packet for
     * it - so the container clicks are byte for byte the same either way. The visual screen is there
     * for when you are watching; when you are in another window it is skipped, because setScreen
     * releases the mouse grab and grabMouse refuses to give it back while the window is inactive,
     * which would kill the swing on the next tree.
     */
    private void openInventory() {
        screenless = !wantScreen();
        if (!screenless) mc.setScreen(new InventoryScreen(mc.player));
    }

    private boolean screenOpen() {
        return screenless ? mc.screen == null : mc.screen instanceof InventoryScreen;
    }

    private void closeInventory() {
        if (!screenless) closeScreen();
        screenless = false;
    }

    /** Inventory index (0-8 hotbar, 9-35 main) to a slot id in the player's own menu. */
    private int slotIdOf(int index) {
        return index < 9 ? index + 36 : index;
    }

    // ---------------------------------------------------------------- farm helpers

    private BlockPos[] findSquare(BlockPos anchor) {
        BlockPos[] best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                BlockPos origin = anchor.offset(dx, 0, dz);
                BlockPos[] square = { origin, origin.east(), origin.south(), origin.east().south() };

                boolean ok = true;
                for (BlockPos p : square) {
                    BlockState above = mc.level.getBlockState(p.above());
                    if (!isSoil(p) || !(above.isAir() || above.is(Blocks.SPRUCE_SAPLING) || isLog(above))) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;

                double d = new Vec3(origin.getX() + 1.0, origin.getY() + 1.0, origin.getZ() + 1.0).distanceToSqr(eye);
                if (d < bestDist) {
                    bestDist = d;
                    best = square;
                }
            }
        }
        return best;
    }

    private boolean platformIntact() {
        for (BlockPos p : soil) if (!isSoil(p)) return false;
        return true;
    }

    private boolean isSoil(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        if (s.is(BlockTags.DIRT)) return true;
        return s.is(Blocks.PODZOL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MOSS_BLOCK);
    }

    private boolean isLog(BlockState s) {
        return s.is(BlockTags.LOGS);
    }

    /**
     * The next thing to swing at: the lowest trunk log we can reach that has not been written off.
     * Lowest first because a tree feller fires off the base of the trunk, and the blocks higher up are
     * the ones that sit at the edge of reach.
     */
    private BlockPos nextTarget() {
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;

        for (BlockPos base : sap) {
            for (int dy = 0; dy < TRUNK_SCAN; dy++) {
                BlockPos p = base.above(dy);
                if (dead.contains(p)) continue;

                BlockState s = mc.level.getBlockState(p);
                boolean wanted = isLog(s) || (dy == 0 && !s.isAir() && !s.is(Blocks.SPRUCE_SAPLING));
                if (!wanted) continue;

                double d = centre(p).distanceToSqr(eye);
                if (d > reach * reach) continue;
                if (!mc.player.isWithinBlockInteractionRange(p, 0.0)) continue;

                if (p.getY() < bestY || (p.getY() == bestY && d < bestDist)) {
                    bestY = p.getY();
                    bestDist = d;
                    best = p;
                }
            }
        }
        return best;
    }

    private BlockPos pickSapling() {
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos p : sap) {
            if (!mc.level.getBlockState(p).is(Blocks.SPRUCE_SAPLING)) continue;
            double d = centre(p).distanceToSqr(eye);
            if (d > reach * reach) continue;
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- inventory

    private int countItem(Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) n += stack.getCount();
        }
        return n;
    }

    /** Hotbar slot holding {@code item}. Null means it is not usable this tick and it has said why. */
    private Integer handSlot(Item item, String label, State back) {
        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found() && hotbar.isHotbar()) return hotbar.slot();

        if (countItem(item) < 1) {
            info("Out of %s. Stopping.", label);
            toggle();
            return null;
        }

        if (!restock.get()) {
            blocked("No %s in the hotbar and restock-hotbar is off.", label);
            return null;
        }

        restockItem = item;
        restockLabel = label;
        restockReturn = back;
        restockStep = Restock.OPEN;
        state = State.RESTOCK;
        return null;
    }

    private int freeHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private Integer axeHotbarSlot(BlockState target) {
        if (axeSlot.get() > 0) return axeSlot.get() - 1;

        FindItemResult fastest = InvUtils.findFastestTool(target);
        if (fastest.found() && fastest.isHotbar()) return fastest.slot();

        FindItemResult axe = InvUtils.findInHotbar(stack -> stack.getItem() instanceof AxeItem);
        if (axe.found() && axe.isHotbar()) return axe.slot();

        return null;
    }

    // ---------------------------------------------------------------- aiming

    private double reach() {
        return mc.player.blockInteractionRange();
    }

    private Vec3 centre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private BlockPos lookedAtBlock() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
        return null;
    }

    /** The game's own pick, which is what every click and every swing actually uses. */
    private BlockHitResult crosshair() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit;
        return null;
    }

    private BlockPos hitBlock() {
        BlockHitResult hit = crosshair();
        return hit == null ? null : hit.getBlockPos();
    }

    private void clearAim() {
        actionTarget = null;
        targetIsFace = false;
        settled = 0;
        aimFails = 0;
    }

    /** True once the turn is done and the crosshair has been resting for a beat. */
    private boolean aimReady() {
        if (turning || glanceHold > 0) {
            settled = 0;
            return false;
        }
        if (settled < settleTicks.get()) {
            settled++;
            return false;
        }
        return true;
    }

    private void nudge(String what) {
        aimFails++;
        if (aimFails > 60) {
            blocked("Cannot get the crosshair onto the block for the %s - something is in the way, or you moved.", what);
            return;
        }
        if (aimFails % 10 == 0 && actionTarget != null) {
            BlockPos again = actionTarget;
            if (targetIsFace) aimAtFace(again, Direction.UP);
            else aimAtBlock(again, 0.5);
        }
    }

    private void aimAtBlock(BlockPos pos, double spreadScale) {
        actionTarget = pos;
        targetIsFace = false;
        settled = 0;
        double spread = aimSpread.get() * spreadScale;
        beginTurn(new Vec3(
            pos.getX() + 0.5 + rand(spread),
            pos.getY() + 0.45 + rand(spread * 0.7),
            pos.getZ() + 0.5 + rand(spread)
        ));
    }

    private void aimAtFace(BlockPos pos, Direction face) {
        actionTarget = pos;
        targetIsFace = true;
        settled = 0;
        double spread = aimSpread.get();
        double x = pos.getX() + 0.5 + face.getStepX() * 0.5;
        double y = pos.getY() + 0.5 + face.getStepY() * 0.5;
        double z = pos.getZ() + 0.5 + face.getStepZ() * 0.5;

        switch (face.getAxis()) {
            case Y -> { x += rand(spread); z += rand(spread); }
            case X -> { y += rand(spread); z += rand(spread); }
            case Z -> { x += rand(spread); y += rand(spread); }
        }
        beginTurn(new Vec3(x, y, z));
    }

    private double rand(double spread) {
        return (random.nextDouble() * 2.0 - 1.0) * spread;
    }

    // ---------------------------------------------------------------- the camera

    private void beginTurn(Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        beginTurnTo(Math.toDegrees(Math.atan2(dz, dx)) - 90.0, -Math.toDegrees(Math.atan2(dy, flat)));
    }

    private void beginTurnTo(double targetYaw, double targetPitch) {
        srcYaw = camYaw;
        srcPitch = camPitch;
        dYaw = wrap(targetYaw - camYaw);
        dPitch = clampPitch(targetPitch) - camPitch;

        if (!humanCamera.get()) {
            turnLen = 1;
            ovYaw = 0;
            ovPitch = 0;
            turnTick = 0;
            turning = true;
            return;
        }

        double sweep = Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        double base = turnTicks.get().getRandom();
        turnLen = Math.max(2, (int) Math.round((0.30 + sweep / 90.0) * base * (0.8 + random.nextDouble() * 0.45)));

        double pct = overshoot.get() / 100.0;
        if (sweep > 7.0 && pct > 0.0) {
            double roll = pct * (0.4 + random.nextDouble() * 1.2);
            ovYaw = dYaw * roll;
            ovPitch = dPitch * roll * 0.6;
        } else {
            ovYaw = 0;
            ovPitch = 0;
        }

        turnTick = 0;
        turning = true;
    }

    private void tickCamera() {
        if (!humanCamera.get()) {
            if (turning) {
                camYaw = srcYaw + dYaw;
                camPitch = srcPitch + dPitch;
                turning = false;
            }
            apply(camYaw, camPitch);
            return;
        }

        // a swing in flight holds the head still, so the crosshair cannot wander onto the neighbour
        if (freezeWhileMining.get() && attackDown && !turning) {
            apply(lockYaw, lockPitch);
            return;
        }

        if (turning) {
            turnTick++;
            double t = Math.min(1.0, (double) turnTick / turnLen);
            double split = 0.72;

            if (t < split) {
                double u = ease(t / split);
                camYaw = srcYaw + (dYaw + ovYaw) * u;
                camPitch = srcPitch + (dPitch + ovPitch) * u;
            } else {
                double u = ease((t - split) / (1.0 - split));
                camYaw = srcYaw + dYaw + ovYaw * (1.0 - u);
                camPitch = srcPitch + dPitch + ovPitch * (1.0 - u);
            }

            if (t >= 1.0) {
                camYaw = srcYaw + dYaw;
                camPitch = srcPitch + dPitch;
                turning = false;
                lockYaw = camYaw;
                lockPitch = camPitch;
            }
        } else if (glanceHold > 0) {
            glanceHold--;
            if (glanceHold == 0 && glanceReturn != null) {
                Vec3 back = glanceReturn;
                glanceReturn = null;
                beginTurn(back);
            }
        } else {
            maybeGlance();
        }

        double steady = (state == State.BREAK && !turning) ? chopSteady.get() : 1.0;

        double amp = handShake.get() * steady;
        if (amp > 0.0) {
            shakeYaw = shakeYaw * 0.82 + random.nextGaussian() * amp * 0.20;
            shakePitch = shakePitch * 0.82 + random.nextGaussian() * amp * 0.16;
        } else {
            shakeYaw = 0;
            shakePitch = 0;
        }

        double swayY = 0, swayP = 0;
        if (!turning && idleSway.get() > 0.0) {
            swayPhase += 0.021 + random.nextDouble() * 0.012;
            double s = idleSway.get() * steady;
            swayY = (Math.sin(swayPhase) * 0.62 + Math.sin(swayPhase * 0.41 + 2.1) * 0.38) * s;
            swayP = (Math.sin(swayPhase * 0.67 + 1.3) * 0.55) * s * 0.6;
        }

        apply(camYaw + shakeYaw + swayY, camPitch + shakePitch + swayP);
    }

    private void maybeGlance() {
        if (!glances.get() || !humanCamera.get()) return;
        if (delayCounter < 16) return;
        if (state == State.BREAK || attackDown) return;
        if (random.nextInt(220) != 0) return;

        Vec3 back = actionTarget != null ? centre(actionTarget) : null;
        if (back == null) return;

        glanceReturn = back;
        glanceHold = 12 + random.nextInt(28);
        beginTurnTo(camYaw + (random.nextBoolean() ? 1 : -1) * (14 + random.nextDouble() * 38), camPitch + rand(16));
    }

    private void apply(double yaw, double pitch) {
        mc.player.setYRot((float) yaw);
        mc.player.setXRot((float) clampPitch(pitch));
    }

    private double ease(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    private double wrap(double degrees) {
        degrees %= 360.0;
        if (degrees >= 180.0) degrees -= 360.0;
        if (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    private double clampPitch(double pitch) {
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    private void trackRotation() {
        double dy = Math.abs(wrap(mc.player.getYRot() - lockYaw));
        double dp = Math.abs(mc.player.getXRot() - lockPitch);
        if (dy > devYaw) devYaw = dy;
        if (dp > devPitch) devPitch = dp;
    }

    // ---------------------------------------------------------------- swing records

    private void beginSwing(BlockPos pos) {
        swingPos = pos;
        swingAge = tickCount;
        swingInFlight = true;
        lockYaw = mc.player.getYRot();
        lockPitch = mc.player.getXRot();
        devYaw = 0;
        devPitch = 0;

        if (!log.isOpen()) return;
        log.line("SWING  begin %s %s dy=%+d dist=%.3f inServerRange=%s target=%s",
            fmt(pos), nameOf(pos), pos.getY() - (soil[0].getY() + 1),
            centre(pos).distanceTo(mc.player.getEyePosition()),
            mc.player.isWithinBlockInteractionRange(pos, 1.0),
            actionTarget == null ? "none" : fmt(actionTarget));
    }

    private void endSwing(String result) {
        if (!swingInFlight) return;
        swingInFlight = false;

        if (log.isOpen()) {
            log.line("SWING  end   %-11s %s ticks=%d yawDev=%.2f pitchDev=%.2f lastAck=%d",
                result, fmt(swingPos), tickCount - swingAge, devYaw, devPitch, lastAckSeq);
        }
        if (debugChat.get()) info("swing %s at %s after %dt", result, fmt(swingPos), tickCount - swingAge);
        swingPos = null;
    }

    // ---------------------------------------------------------------- misc

    private void blocked(String message, Object... args) {
        holdAttack(false);
        closeScreen();
        endSwing("blocked");
        if (log.isOpen()) log.line("BLOCKED %s", args.length == 0 ? message : String.format(message, args));
        warning(message, args);
        warning("Sitting out %d ticks before trying again.", stuckBackoff.get());
        delayCounter = jitter(stuckBackoff.get());
        clearAim();
        turning = false;
        state = State.BLOCKED;
    }

    private void say(String key, String message, Object... args) {
        if (log.isOpen()) log.line(args.length == 0 ? message : String.format(message, args));
        if (!notifications.get()) return;

        Integer last = lastSaid.get(key);
        if (last != null && tickCount - last < 100) return;
        lastSaid.put(key, tickCount);
        info(message, args);
    }

    private int jitter(int ticks) {
        int pct = timingJitter.get();
        if (pct <= 0) return Math.max(1, ticks);

        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * (pct / 100.0);
        return Math.max(1, (int) Math.round(ticks * factor));
    }
}
