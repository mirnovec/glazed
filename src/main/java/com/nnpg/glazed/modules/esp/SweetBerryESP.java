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
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SweetBerryESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> berryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("berry-color")
        .description("Sweet berry bush box color")
        .defaultValue(new SettingColor(220, 20, 60, 100))
        .build());

    private final Setting<ShapeMode> berryShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Sweet berry bush box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to sweet berry bushes")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Sweet berry bush tracer color")
        .defaultValue(new SettingColor(220, 20, 60, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> berryChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce sweet berry bushes in chat")
        .defaultValue(true)
        .build());

    private final SettingGroup sgFiltering = settings.createGroup("Berry Ages");

    private final Setting<Boolean> includeAge0 = sgFiltering.add(new BoolSetting.Builder()
        .name("age-0")
        .description("Include age 0 berry bushes (just planted)")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> includeAge1 = sgFiltering.add(new BoolSetting.Builder()
        .name("age-1")
        .description("Include age 1 berry bushes (growing)")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> includeAge2 = sgFiltering.add(new BoolSetting.Builder()
        .name("age-2")
        .description("Include age 2 berry bushes (harvestable - few berries)")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeAge3 = sgFiltering.add(new BoolSetting.Builder()
        .name("age-3")
        .description("Include age 3 berry bushes (fully grown - many berries)")
        .defaultValue(true)
        .build());

    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> minY = sgRange.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan for sweet berry bushes")
        .defaultValue(50)
        .min(-64)
        .max(128)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan for sweet berry bushes")
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

    private final Set<BlockPos> berryPositions = ConcurrentHashMap.newKeySet();

    private ExecutorService threadPool;

    public SweetBerryESP() {
        super(GlazedAddon.esp, "sweet-berry-esp", "ESP for sweet berry bushes at specific growth ages with threading and tracer support.");
    }

    @Override
    public void onActivate() {
        if (mc.level == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        berryPositions.clear();

        if (useThreading.get()) {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk worldChunk) {
                    threadPool.submit(() -> scanChunkForBerries(worldChunk));
                }
            }
        } else {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk worldChunk) scanChunkForBerries(worldChunk);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }

        berryPositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunkForBerries(event.chunk()));
        } else {
            scanChunkForBerries(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        Runnable updateTask = () -> {
            boolean isBerry = isSweetBerryBush(state, pos.getY());
            if (isBerry) {
                boolean wasAdded = berryPositions.add(pos);
                if (wasAdded && berryChat.get() && (!useThreading.get() || !limitChatSpam.get())) {
                    int age = getBerryAge(state);
                    String ageDescription = getBerryAgeDescription(age);
                    info("§c[§6Sweet Berry ESP§c] §6" + ageDescription + " at " + pos.toShortString());
                }
            } else {
                berryPositions.remove(pos);
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void scanChunkForBerries(LevelChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getMinBlockX();
        int zStart = cpos.getMinBlockZ();
        int yMin = Math.max(chunk.getMinY(), minY.get());
        int yMax = Math.min(chunk.getMinY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkBerries = new HashSet<>();
        int foundCount = 0;

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isSweetBerryBush(state, y)) {
                        chunkBerries.add(pos);
                        foundCount++;
                    }
                }
            }
        }

        berryPositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            return blockChunk.equals(cpos) && !chunkBerries.contains(pos);
        });

        int newBlocks = 0;
        for (BlockPos pos : chunkBerries) {
            if (berryPositions.add(pos)) {
                newBlocks++;
            }
        }

        if (berryChat.get() && foundCount > 0) {
            if (useThreading.get() && limitChatSpam.get()) {
                if (newBlocks > 0) {
                    info("§c[§6Sweet Berry ESP§c] §6Chunk " + cpos.x() + "," + cpos.z() + "§c: §6" + newBlocks + " new berry bushes found");
                }
            } else {
                for (BlockPos pos : chunkBerries) {
                    if (!berryPositions.contains(pos)) {
                        BlockState state = chunk.getBlockState(pos);
                        int age = getBerryAge(state);
                        String ageDescription = getBerryAgeDescription(age);
                        info("§c[§6Sweet Berry ESP§c] §6" + ageDescription + " at " + pos.toShortString());
                    }
                }
            }
        }
    }

    private boolean isSweetBerryBush(BlockState state, int y) {
        if (y < minY.get() || y > maxY.get()) return false;

        if (!state.is(Blocks.SWEET_BERRY_BUSH)) return false;

        int age = getBerryAge(state);

        if (includeAge0.get() && age == 0) return true;
        if (includeAge1.get() && age == 1) return true;
        if (includeAge2.get() && age == 2) return true;
        if (includeAge3.get() && age == 3) return true;

        return false;
    }

    private int getBerryAge(BlockState state) {
        if (state.is(Blocks.SWEET_BERRY_BUSH)) {
            return state.getValue(SweetBerryBushBlock.AGE);
        }
        return -1;
    }

    private String getBerryAgeDescription(int age) {
        switch (age) {
            case 0:
                return "Planted Berry Bush (Age 0)";
            case 1:
                return "Growing Berry Bush (Age 1)";
            case 2:
                return "Harvestable Berry Bush (Age 2)";
            case 3:
                return "Fully Grown Berry Bush (Age 3)";
            default:
                return "Sweet Berry Bush";
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3 playerPos = mc.player.getPosition(event.tickDelta);
        Color side = new Color(berryColor.get());
        Color outline = new Color(berryColor.get());
        Color tracerColorValue = new Color(tracerColor.get());

        int renderDistance = mc.options.renderDistance().get() * 16;

        for (BlockPos pos : berryPositions) {
            if (!isWithinRenderDistance(playerPos, pos, renderDistance)) continue;

            BlockState state = mc.level.getBlockState(pos);
            int age = getBerryAge(state);

            Color ageAdjustedSide, ageAdjustedOutline;
            switch (age) {
                case 0:
                    ageAdjustedSide = new Color(side.r / 4, side.g / 4, side.b / 4, side.a);
                    ageAdjustedOutline = new Color(outline.r / 4, outline.g / 4, outline.b / 4, outline.a);
                    break;
                case 1:
                    ageAdjustedSide = new Color(side.r / 2, side.g / 2, side.b / 2, side.a);
                    ageAdjustedOutline = new Color(outline.r / 2, outline.g / 2, outline.b / 2, outline.a);
                    break;
                case 2:
                    ageAdjustedSide = side;
                    ageAdjustedOutline = outline;
                    break;
                case 3:
                    ageAdjustedSide = new Color(Math.min(255, (int)(side.r * 1.2)),
                        Math.min(255, (int)(side.g * 1.2)),
                        Math.min(255, (int)(side.b * 1.2)),
                        side.a);
                    ageAdjustedOutline = new Color(Math.min(255, (int)(outline.r * 1.2)),
                        Math.min(255, (int)(outline.g * 1.2)),
                        Math.min(255, (int)(outline.b * 1.2)),
                        outline.a);
                    break;
                default:
                    ageAdjustedSide = side;
                    ageAdjustedOutline = outline;
            }

            event.renderer.box(pos, ageAdjustedSide, ageAdjustedOutline, berryShapeMode.get(), 0);

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

    private boolean isWithinRenderDistance(Vec3 playerPos, BlockPos blockPos, int renderDistance) {
        double dx = playerPos.x - blockPos.getX() - 0.5;
        double dz = playerPos.z - blockPos.getZ() - 0.5;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return horizontalDistance <= renderDistance;
    }
}
