package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PistonESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> pistonColor = sgGeneral.add(new ColorSetting.Builder()
        .name("piston-color")
        .description("Regular piston box color")
        .defaultValue(new SettingColor(255, 100, 100, 100))
        .build());

    private final Setting<SettingColor> stickyPistonColor = sgGeneral.add(new ColorSetting.Builder()
        .name("sticky-piston-color")
        .description("Sticky piston box color")
        .defaultValue(new SettingColor(100, 255, 100, 100))
        .build());

    private final Setting<ShapeMode> pistonShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Piston box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to pistons")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Piston tracer color")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> pistonChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce pistons in chat")
        .defaultValue(true)
        .build());

    private final SettingGroup sgFiltering = settings.createGroup("Piston Types");

    private final Setting<Boolean> includeRegularPistons = sgFiltering.add(new BoolSetting.Builder()
        .name("regular-pistons")
        .description("Include regular pistons")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeStickyPistons = sgFiltering.add(new BoolSetting.Builder()
        .name("sticky-pistons")
        .description("Include sticky pistons")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeExtendedPistons = sgFiltering.add(new BoolSetting.Builder()
        .name("extended-pistons")
        .description("Include extended piston heads")
        .defaultValue(false)
        .build());

    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> minY = sgRange.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan for pistons")
        .defaultValue(-64)
        .min(-64)
        .max(128)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan for pistons")
        .defaultValue(320)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final SettingGroup sgThreading = settings.createGroup("Threading");

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .description("Use multi-threading for chunk scanning (better performance)")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
        .name("thread-pool-size")
        .description("Number of threads to use for scanning")
        .defaultValue(2)
        .min(1)
        .max(8)
        .sliderRange(1, 8)
        .visible(useThreading::get)
        .build());

    private final Setting<Boolean> limitChatSpam = sgThreading.add(new BoolSetting.Builder()
        .name("limit-chat-spam")
        .description("Reduce chat spam when using threading")
        .defaultValue(true)
        .visible(useThreading::get)
        .build());

    private final Set<BlockPos> regularPistonPositions = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> stickyPistonPositions = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> pistonHeadPositions = ConcurrentHashMap.newKeySet();

    private ExecutorService threadPool;

    public PistonESP() {
        super(GlazedAddon.esp, "piston-esp", "ESP for pistons and sticky pistons with threading and tracer support.");
    }

    @Override
    public void onActivate() {
        if (mc.level == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        regularPistonPositions.clear();
        stickyPistonPositions.clear();
        pistonHeadPositions.clear();

        if (useThreading.get()) {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk worldChunk) {
                    threadPool.submit(() -> scanChunkForPistons(worldChunk));
                }
            }
        } else {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk worldChunk) scanChunkForPistons(worldChunk);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }

        regularPistonPositions.clear();
        stickyPistonPositions.clear();
        pistonHeadPositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunkForPistons(event.chunk()));
        } else {
            scanChunkForPistons(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        Runnable updateTask = () -> {
            PistonType pistonType = getPistonType(state, pos.getY());

            regularPistonPositions.remove(pos);
            stickyPistonPositions.remove(pos);
            pistonHeadPositions.remove(pos);

            if (pistonType != PistonType.NONE) {
                boolean wasAdded = false;
                String blockType = "";

                switch (pistonType) {
                    case REGULAR_PISTON:
                        wasAdded = regularPistonPositions.add(pos);
                        blockType = "Regular Piston";
                        break;
                    case STICKY_PISTON:
                        wasAdded = stickyPistonPositions.add(pos);
                        blockType = "Sticky Piston";
                        break;
                    case PISTON_HEAD:
                        wasAdded = pistonHeadPositions.add(pos);
                        blockType = "Piston Head";
                        break;
                }

                if (wasAdded && pistonChat.get() && (!useThreading.get() || !limitChatSpam.get())) {
                    info("§c[§6Piston ESP§c] §6" + blockType + " at " + pos.toShortString());
                }
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void scanChunkForPistons(LevelChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getMinBlockX();
        int zStart = cpos.getMinBlockZ();
        int yMin = Math.max(chunk.getMinY(), minY.get());
        int yMax = Math.min(chunk.getMinY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkRegularPistons = new HashSet<>();
        Set<BlockPos> chunkStickyPistons = new HashSet<>();
        Set<BlockPos> chunkPistonHeads = new HashSet<>();
        int foundCount = 0;

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    PistonType pistonType = getPistonType(state, y);

                    if (pistonType != PistonType.NONE) {
                        switch (pistonType) {
                            case REGULAR_PISTON:
                                chunkRegularPistons.add(pos);
                                break;
                            case STICKY_PISTON:
                                chunkStickyPistons.add(pos);
                                break;
                            case PISTON_HEAD:
                                chunkPistonHeads.add(pos);
                                break;
                        }
                        foundCount++;
                    }
                }
            }
        }

        regularPistonPositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            return blockChunk.equals(cpos) && !chunkRegularPistons.contains(pos);
        });
        stickyPistonPositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            return blockChunk.equals(cpos) && !chunkStickyPistons.contains(pos);
        });
        pistonHeadPositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            return blockChunk.equals(cpos) && !chunkPistonHeads.contains(pos);
        });

        int newBlocks = 0;
        for (BlockPos pos : chunkRegularPistons) {
            if (regularPistonPositions.add(pos)) {
                newBlocks++;
            }
        }
        for (BlockPos pos : chunkStickyPistons) {
            if (stickyPistonPositions.add(pos)) {
                newBlocks++;
            }
        }
        for (BlockPos pos : chunkPistonHeads) {
            if (pistonHeadPositions.add(pos)) {
                newBlocks++;
            }
        }

        if (pistonChat.get() && foundCount > 0) {
            if (useThreading.get() && limitChatSpam.get()) {
                if (newBlocks > 0) {
                    info("§c[§6Piston ESP§c] §6Chunk " + cpos.x() + "," + cpos.z() + "§c: §6" + newBlocks + " new pistons found");
                }
            } else {
                for (BlockPos pos : chunkRegularPistons) {
                    if (!regularPistonPositions.contains(pos)) {
                        info("§c[§6Piston ESP§c] §6Regular Piston at " + pos.toShortString());
                    }
                }
                for (BlockPos pos : chunkStickyPistons) {
                    if (!stickyPistonPositions.contains(pos)) {
                        info("§c[§6Piston ESP§c] §6Sticky Piston at " + pos.toShortString());
                    }
                }
                for (BlockPos pos : chunkPistonHeads) {
                    if (!pistonHeadPositions.contains(pos)) {
                        info("§c[§6Piston ESP§c] §6Piston Head at " + pos.toShortString());
                    }
                }
            }
        }
    }

    private enum PistonType {
        NONE,
        REGULAR_PISTON,
        STICKY_PISTON,
        PISTON_HEAD
    }

    private PistonType getPistonType(BlockState state, int y) {
        if (y < minY.get() || y > maxY.get()) return PistonType.NONE;

        if (includeRegularPistons.get() && state.is(Blocks.PISTON)) {
            return PistonType.REGULAR_PISTON;
        }

        if (includeStickyPistons.get() && state.is(Blocks.STICKY_PISTON)) {
            return PistonType.STICKY_PISTON;
        }

        if (includeExtendedPistons.get() && state.is(Blocks.PISTON_HEAD)) {
            return PistonType.PISTON_HEAD;
        }

        return PistonType.NONE;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3 playerPos = mc.player.getPosition(event.tickDelta);
        Color regularPistonSide = new Color(pistonColor.get());
        Color regularPistonOutline = new Color(pistonColor.get());
        Color stickyPistonSide = new Color(stickyPistonColor.get());
        Color stickyPistonOutline = new Color(stickyPistonColor.get());
        Color tracerColorValue = new Color(tracerColor.get());

        int renderDistance = mc.options.renderDistance().get() * 16;

        for (BlockPos pos : regularPistonPositions) {
            if (!isWithinRenderDistance(playerPos, pos, renderDistance)) continue;

            event.renderer.box(pos, regularPistonSide, regularPistonOutline, pistonShapeMode.get(), 0);

            if (tracers.get()) {
                renderTracer(event, playerPos, pos, tracerColorValue);
            }
        }

        for (BlockPos pos : stickyPistonPositions) {
            if (!isWithinRenderDistance(playerPos, pos, renderDistance)) continue;

            event.renderer.box(pos, stickyPistonSide, stickyPistonOutline, pistonShapeMode.get(), 0);

            if (tracers.get()) {
                renderTracer(event, playerPos, pos, tracerColorValue);
            }
        }

        Color pistonHeadSide = new Color((pistonColor.get().r + stickyPistonColor.get().r) / 2,
            (pistonColor.get().g + stickyPistonColor.get().g) / 2,
            (pistonColor.get().b + stickyPistonColor.get().b) / 2,
            pistonColor.get().a);
        Color pistonHeadOutline = new Color(pistonHeadSide);

        for (BlockPos pos : pistonHeadPositions) {
            if (!isWithinRenderDistance(playerPos, pos, renderDistance)) continue;

            event.renderer.box(pos, pistonHeadSide, pistonHeadOutline, pistonShapeMode.get(), 0);

            if (tracers.get()) {
                renderTracer(event, playerPos, pos, tracerColorValue);
            }
        }
    }

    private boolean isWithinRenderDistance(Vec3 playerPos, BlockPos blockPos, int renderDistance) {
        double dx = playerPos.x - blockPos.getX() - 0.5;
        double dz = playerPos.z - blockPos.getZ() - 0.5;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return horizontalDistance <= renderDistance;
    }

    private void renderTracer(Render3DEvent event, Vec3 playerPos, BlockPos pos, Color tracerColorValue) {
        Vec3 blockCenter = Vec3.atCenterOf(pos);

        Vec3 startPos;
        // push it half a block forward or first person clips the whole line
        if (mc.options.getCameraType().isFirstPerson()) {
            Vec3 lookDirection = mc.player.getLookAngle();
            startPos = new Vec3(
                playerPos.x + lookDirection.x * 0.5,
                playerPos.y + mc.player.getEyeHeight(mc.player.getPose()) + lookDirection.y * 0.5,
                playerPos.z + lookDirection.z * 0.5
            );
        } else {
            startPos = new Vec3(
                playerPos.x,
                playerPos.y + mc.player.getEyeHeight(mc.player.getPose()),
                playerPos.z
            );
        }

        event.renderer.line(startPos.x, startPos.y, startPos.z,
            blockCenter.x, blockCenter.y, blockCenter.z, tracerColorValue);
    }
}
