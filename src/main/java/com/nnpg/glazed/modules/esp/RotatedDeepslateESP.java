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
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RotatedDeepslateESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> deepslateColor = sgGeneral.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Rotated deepslate box color")
        .defaultValue(new SettingColor(255, 0, 255, 100))
        .build());

    private final Setting<ShapeMode> deepslateShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Rotated deepslate box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to rotated deepslate blocks")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Rotated deepslate tracer color")
        .defaultValue(new SettingColor(255, 0, 255, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> deepslateChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce rotated deepslate in chat")
        .defaultValue(true)
        .build());

    private final SettingGroup sgFiltering = settings.createGroup("Block Types");

    private final Setting<Boolean> includeRegularDeepslate = sgFiltering.add(new BoolSetting.Builder()
        .name("regular-deepslate")
        .description("Include rotated regular deepslate blocks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includePolishedDeepslate = sgFiltering.add(new BoolSetting.Builder()
        .name("polished-deepslate")
        .description("Include rotated polished deepslate blocks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeDeepslateBricks = sgFiltering.add(new BoolSetting.Builder()
        .name("deepslate-bricks")
        .description("Include rotated deepslate bricks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeDeepslateTiles = sgFiltering.add(new BoolSetting.Builder()
        .name("deepslate-tiles")
        .description("Include rotated deepslate tiles")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeChiseledDeepslate = sgFiltering.add(new BoolSetting.Builder()
        .name("chiseled-deepslate")
        .description("Include rotated chiseled deepslate")
        .defaultValue(true)
        .build());

    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> minY = sgRange.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan for rotated deepslate")
        .defaultValue(-64)
        .min(-64)
        .max(128)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan for rotated deepslate")
        .defaultValue(128)
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

    private final Set<BlockPos> rotatedDeepslatePositions = ConcurrentHashMap.newKeySet();

    private ExecutorService threadPool;

    public RotatedDeepslateESP() {
        super(GlazedAddon.esp, "rotated-deepslate-esp", "ESP for rotated deepslate blocks with threading and tracer support.");
    }

    @Override
    public void onActivate() {
        if (mc.level == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        rotatedDeepslatePositions.clear();

        if (useThreading.get()) {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk worldChunk) {
                    threadPool.submit(() -> scanChunkForRotatedDeepslate(worldChunk));
                }
            }
        } else {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk worldChunk) scanChunkForRotatedDeepslate(worldChunk);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }

        rotatedDeepslatePositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunkForRotatedDeepslate(event.chunk()));
        } else {
            scanChunkForRotatedDeepslate(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        Runnable updateTask = () -> {
            boolean isRotated = isRotatedDeepslate(state, pos.getY());
            if (isRotated) {
                boolean wasAdded = rotatedDeepslatePositions.add(pos);
                if (wasAdded && deepslateChat.get() && (!useThreading.get() || !limitChatSpam.get())) {
                    String blockType = getBlockTypeName(state);
                    info("§5[§dRotated Deepslate§5] §dRotated Deepslate§5: §d" + blockType + " at " + pos.toShortString());
                }
            } else {
                rotatedDeepslatePositions.remove(pos);
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void scanChunkForRotatedDeepslate(LevelChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getMinBlockX();
        int zStart = cpos.getMinBlockZ();
        int yMin = Math.max(chunk.getMinY(), minY.get());
        int yMax = Math.min(chunk.getMinY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkRotatedDeepslate = new HashSet<>();
        int foundCount = 0;

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isRotatedDeepslate(state, y)) {
                        chunkRotatedDeepslate.add(pos);
                        foundCount++;
                    }
                }
            }
        }

        rotatedDeepslatePositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            return blockChunk.equals(cpos) && !chunkRotatedDeepslate.contains(pos);
        });

        int newBlocks = 0;
        for (BlockPos pos : chunkRotatedDeepslate) {
            if (rotatedDeepslatePositions.add(pos)) {
                newBlocks++;
            }
        }

        if (deepslateChat.get() && foundCount > 0) {
            if (useThreading.get() && limitChatSpam.get()) {
                if (newBlocks > 0) {
                    info("§5[§dRotated Deepslate§5] §dChunk " + cpos.x() + "," + cpos.z() + "§5: §d" + newBlocks + " new rotated deepslate blocks found");
                }
            } else {
                for (BlockPos pos : chunkRotatedDeepslate) {
                    if (!rotatedDeepslatePositions.contains(pos)) {
                        BlockState state = chunk.getBlockState(pos);
                        String blockType = getBlockTypeName(state);
                        info("§5[§dRotated Deepslate§5] §dRotated Deepslate§5: §d" + blockType + " at " + pos.toShortString());
                    }
                }
            }
        }
    }

    private boolean isRotatedDeepslate(BlockState state, int y) {
        if (y < minY.get() || y > maxY.get()) return false;

        if (!state.hasProperty(BlockStateProperties.AXIS)) return false;

        Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
        if (axis == Direction.Axis.Y) return false;

        if (includeRegularDeepslate.get() && state.is(Blocks.DEEPSLATE)) {
            return true;
        }

        if (includePolishedDeepslate.get() && state.is(Blocks.POLISHED_DEEPSLATE)) {
            return true;
        }

        if (includeDeepslateBricks.get() && state.is(Blocks.DEEPSLATE_BRICKS)) {
            return true;
        }

        if (includeDeepslateTiles.get() && state.is(Blocks.DEEPSLATE_TILES)) {
            return true;
        }

        if (includeChiseledDeepslate.get() && state.is(Blocks.CHISELED_DEEPSLATE)) {
            return true;
        }

        return false;
    }

    private String getBlockTypeName(BlockState state) {
        if (state.is(Blocks.DEEPSLATE)) {
            return "Rotated Deepslate";
        } else if (state.is(Blocks.POLISHED_DEEPSLATE)) {
            return "Rotated Polished Deepslate";
        } else if (state.is(Blocks.DEEPSLATE_BRICKS)) {
            return "Rotated Deepslate Bricks";
        } else if (state.is(Blocks.DEEPSLATE_TILES)) {
            return "Rotated Deepslate Tiles";
        } else if (state.is(Blocks.CHISELED_DEEPSLATE)) {
            return "Rotated Chiseled Deepslate";
        }
        return "Rotated Deepslate Block";
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3 playerPos = mc.player.getPosition(event.tickDelta);
        Color side = new Color(deepslateColor.get());
        Color outline = new Color(deepslateColor.get());
        Color tracerColorValue = new Color(tracerColor.get());

        for (BlockPos pos : rotatedDeepslatePositions) {
            event.renderer.box(pos, side, outline, deepslateShapeMode.get(), 0);

            if (tracers.get()) {
                Vec3 blockCenter = Vec3.atCenterOf(pos);

                Vec3 startPos;
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
    }
}
