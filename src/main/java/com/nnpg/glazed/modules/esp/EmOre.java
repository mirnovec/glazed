package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.OverworldOre;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * One tracer, always pointing at the nearest deepslate emerald. A test harness for
 * {@link OverworldOreFinder}: it owns no simulation of its own, it just asks the finder for its
 * closest emerald prediction under y=0 and draws a line to it.
 *
 * Deepslate emerald is the rarest thing the finder predicts — the height triangle peaks at y=232
 * and deepslate only exists below y=0, so these sit in the extreme bottom tail *and* need a
 * mountain biome. Expect long stretches with no target; that is the ore being rare, not the
 * module being broken, which is why it says what it is seeing.
 */
public class EmOre extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> deepslateY = sgGeneral.add(new IntSetting.Builder()
        .name("deepslate-y")
        .description("Emerald at or below this height counts as deepslate. Deepslate fully replaces stone below y=0 and fades in up to y=8.")
        .defaultValue(0)
        .min(-64)
        .max(320)
        .sliderRange(-64, 16)
        .build()
    );

    private final Setting<Boolean> autoSetup = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-setup")
        .description("Turn on Overworld Ore Finder with emerald and predict-unloaded when this module is enabled. Without those there is nothing for it to point at.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> searchRange = sgGeneral.add(new IntSetting.Builder()
        .name("search-range")
        .description("Raise the finder's unloaded-range to at least this many chunks, so there is something to find past your render distance. Deepslate emerald is rare enough that a small radius often has none: 32 covers ~4200 chunks, 64 covers ~16600.")
        .defaultValue(16)
        .min(1)
        .max(64)
        .sliderRange(1, 48)
        .visible(autoSetup::get)
        .build()
    );

    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("min-chunks-per-tick")
        .description("Raise the finder's chunks-per-tick to at least this. A wide search-range fills far too slowly at the default of 2.")
        .defaultValue(6)
        .min(1)
        .max(16)
        .sliderRange(1, 16)
        .visible(autoSetup::get)
        .build()
    );

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Say in chat where the target is and when it changes, throttled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Tracer and box colour.")
        .defaultValue(new SettingColor(23, 224, 122))
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box")
        .description("Also draw a box on the target block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> sideAlpha = sgRender.add(new IntSetting.Builder()
        .name("side-alpha")
        .description("Fill opacity of the box.")
        .defaultValue(60)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .visible(box::get)
        .build()
    );

    /** Same vein, marginally nearer block. Not worth another chat line. */
    private static final double RE_ANNOUNCE_DISTANCE_SQ = 64;

    private BlockPos target;
    private OverworldOreFinder.PredictionState targetState;
    private long targetSourceChunk;
    private long targetEpoch;
    private boolean targetConfirmed;
    private BlockPos announced;
    private int tickCounter;
    private boolean sawFinder;

    public EmOre() {
        super(GlazedAddon.esp, "em-ore", "Always draws a tracer to the nearest deepslate emerald, loaded chunk or not. Test harness for Overworld Ore Finder.");
    }

    @Override
    public void onActivate() {
        target = null;
        targetState = null;
        targetSourceChunk = Long.MIN_VALUE;
        targetEpoch = Long.MIN_VALUE;
        targetConfirmed = false;
        announced = null;
        tickCounter = 0;
        sawFinder = false;

        if (PlayerUtils.getDimension() != Dimension.Overworld) {
            error("Overworld only.");
            toggle();
            return;
        }

        OverworldOreFinder finder = finder();
        if (finder == null) {
            error("Overworld Ore Finder is missing, so there is nothing to read.");
            toggle();
            return;
        }

        if (!autoSetup.get()) return;

        if (!finder.isOreEnabled(OverworldOre.Type.EMERALD)) {
            finder.setOreEnabled(OverworldOre.Type.EMERALD, true);
            info("Turned emerald on in Overworld Ore Finder.");
        }

        if (!finder.isPredictingUnloaded()) {
            finder.setPredictUnloaded(true);
            info("Turned predict-unloaded on, otherwise unloaded chunks stay empty.");
        }

        if (finder.unloadedRange() < searchRange.get()) {
            finder.setUnloadedRange(searchRange.get());
            info("Raised unloaded-range to %d chunks.", searchRange.get());
        }

        if (finder.chunksPerTick() < chunksPerTick.get()) {
            finder.setChunksPerTick(chunksPerTick.get());
            info("Raised chunks-per-tick to %d.", chunksPerTick.get());
        }

        if (!finder.isActive()) {
            finder.toggle();
            info("Enabled Overworld Ore Finder.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (PlayerUtils.getDimension() != Dimension.Overworld) {
            clearTarget();
            return;
        }

        OverworldOreFinder finder = finder();

        if (finder == null || !finder.isActive()) {
            clearTarget();
            sawFinder = false;

            // a module drawing nothing and saying nothing looks identical to a broken one
            if (++tickCounter >= 100) {
                tickCounter = 0;
                if (announce.get()) warning("Overworld Ore Finder is off, nothing to point at.");
            }

            return;
        }

        if (!sawFinder) {
            sawFinder = true;
            tickCounter = 0;
        }

        // A finder lifecycle epoch (seed/world/region/RTP/module reset) means every coordinate it
        // produced belongs to a world that is gone, so the current one goes with it.
        if (target != null && targetEpoch != finder.predictionEpoch()) clearTarget();

        // A block a player has broken is gone for good, so pointing at it would be pointing at
        // nothing. Drop it and let the query below hand over the next one this same tick.
        if (target != null && finder.isMined(target)) {
            if (announce.get()) info("Target %d %d %d was mined, picking the next one.",
                target.getX(), target.getY(), target.getZ());
            clearTarget();
        }

        // Always the closest one: re-ask every tick and move the tracer the moment something
        // nearer shows up, whether that is a chunk finishing or you walking towards it.
        OverworldOreFinder.Prediction prediction = finder.nearestPredictionInfo(
            OverworldOre.Type.EMERALD, mc.level.getMinY(), deepslateY.get());

        // No answer never releases what we are already pointing at. Cache eviction, a chunk
        // recomputing, rubber-banding and the AIR/deepslate anti-xray disguises all show up here
        // as a momentary null, and blanking the tracer on those is the bug this module was
        // rebuilt to kill. The target only changes for a real, nearer candidate.
        if (prediction != null && !prediction.position().equals(target)) {
            target = prediction.position();
            targetState = prediction.state();
            targetSourceChunk = prediction.sourceChunk();
            targetEpoch = finder.predictionEpoch();
            targetConfirmed = false;
        }

        // Positive evidence is still useful: unlike AIR/deepslate, an actual emerald block is not
        // ambiguous. Record it once so the live log proves that this exact locked coordinate was
        // revealed by the server. Negative anti-xray states never alter the lease.
        if (target != null && !targetConfirmed && mc.level.hasChunkAt(target)) {
            var state = mc.level.getBlockState(target);
            if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) {
                targetConfirmed = true;
                if (announce.get()) info("Confirmed emerald at locked target %d %d %d.",
                    target.getX(), target.getY(), target.getZ());
            }
        }

        if (!announce.get()) return;

        if (++tickCounter < 40) return;
        tickCounter = 0;

        if (target == null) {
            if (announced != null) {
                announced = null;
                info("No validated emerald remains below y=%d within %d chunks.", deepslateY.get(), finder.unloadedRange());
            } else {
                info("No validated deepslate emerald below y=%d yet (%d terrain chunks pending, %d cached).",
                    deepslateY.get(), finder.terrainPending(), finder.terrainCached());
            }

            return;
        }

        // moving makes the nearest block flip around inside one vein; only speak up on a real change
        if (announced != null && target.distSqr(announced) < RE_ANNOUNCE_DISTANCE_SQ) return;

        announced = target;

        info("Nearest deepslate emerald: %d %d %d, %d blocks away%s.",
            target.getX(), target.getY(), target.getZ(),
            Math.round(Math.sqrt(mc.player.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5))),
            targetConfirmed ? " (server revealed)"
                : targetState == OverworldOreFinder.PredictionState.GENERATED
                    ? " (seed locked)" : " (anti-xray compatible)");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        BlockPos pos = target;
        if (pos == null || mc.player == null) return;

        Color line = color.get();

        event.renderer.line(
            RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            line);

        if (!box.get()) return;

        Color side = new Color(line.r, line.g, line.b, sideAlpha.get());

        event.renderer.box(
            pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
            side, line, ShapeMode.Both, 0);
    }

    @Override
    public String getInfoString() {
        BlockPos pos = target;
        if (pos == null) return "none";
        return String.format("%d %d %d", pos.getX(), pos.getY(), pos.getZ());
    }

    private OverworldOreFinder finder() {
        return Modules.get().get(OverworldOreFinder.class);
    }

    private void clearTarget() {
        target = null;
        targetState = null;
        targetSourceChunk = Long.MIN_VALUE;
        targetEpoch = Long.MIN_VALUE;
        targetConfirmed = false;
        announced = null;
    }
}
