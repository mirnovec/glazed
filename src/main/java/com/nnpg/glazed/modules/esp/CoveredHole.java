package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.concurrent.*;

public class CoveredHole extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> chatNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notifications")
        .description("Send chat messages when covered holes are found")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyPlayerCovered = sgGeneral.add(new BoolSetting.Builder()
        .name("only-player-covered")
        .description("Only detect holes that appear to be intentionally covered")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxThreads = sgGeneral.add(new IntSetting.Builder()
        .name("max-threads")
        .description("Maximum number of threads to use for scanning")
        .defaultValue(4)
        .min(1)
        .max(8)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines for covered holes")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides for covered holes")
        .defaultValue(new SettingColor(255, 165, 0, 50))
        .build()
    );

    private final Map<AABB, CoveredHoleInfo> coveredHoles = new ConcurrentHashMap<>();
    private final Set<AABB> processedHoles = ConcurrentHashMap.newKeySet();
    private final Set<AABB> pendingProcessing = ConcurrentHashMap.newKeySet();
    private final Set<AABB> notifiedHoles = ConcurrentHashMap.newKeySet();
    private ExecutorService executorService;
    private final List<Future<Map.Entry<AABB, CoveredHoleInfo>>> pendingTasks = new ArrayList<>();

    private final Map<BlockPos, Boolean> solidBlockCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockState> blockStateCache = new ConcurrentHashMap<>();

    private HoleTunnelStairsESP holeESP;
    private int tickCounter = 0;
    private volatile boolean isScanning = false;
    private long lastCacheClear = 0;
    private long lastCheckTime = 0;
    private String currentWorld = "";

    public CoveredHole() {
        super(GlazedAddon.esp, "covered-hole", "Detects covered holes from HoleTunnelStairsESP with performance optimization.");
    }

    @Override
    public void onActivate() {
        executorService = Executors.newFixedThreadPool(maxThreads.get());

        holeESP = Modules.get().get(HoleTunnelStairsESP.class);
        if (holeESP == null || !holeESP.isActive()) {
            if (chatNotifications.get()) error("HoleTunnelStairsESP must be active for CoveredHole to work!");
            toggle();
            return;
        }

        clearAllData();

        if (mc.level != null) {
            currentWorld = mc.level.dimension().identifier().toString();
        }
    }

    @Override
    public void onDeactivate() {
        shutdownExecutor();
        clearAllData();
    }

    private void clearAllData() {
        coveredHoles.clear();
        processedHoles.clear();
        pendingProcessing.clear();
        solidBlockCache.clear();
        blockStateCache.clear();
        pendingTasks.clear();
        notifiedHoles.clear();
        isScanning = false;
        tickCounter = 0;
        lastCacheClear = System.currentTimeMillis();
        lastCheckTime = 0;
    }

    private void shutdownExecutor() {
        if (executorService != null) {
            isScanning = false;

            for (Future<?> task : pendingTasks) {
                task.cancel(true);
            }
            pendingTasks.clear();

            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkWorldChange() {
        if (mc.level == null) {
            currentWorld = "";
            clearAllData();
            return;
        }

        String newWorld = mc.level.dimension().identifier().toString();
        if (!newWorld.equals(currentWorld)) {
            currentWorld = newWorld;
            clearAllData();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        checkWorldChange();

        if (mc.level == null || mc.player == null) return;

        if (holeESP == null || !holeESP.isActive()) {
            if (chatNotifications.get()) error("HoleTunnelStairsESP was disabled!");
            toggle();
            return;
        }

        tickCounter++;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCacheClear > 5000) {
            clearOldCacheEntries();
            lastCacheClear = currentTime;
        }

        if (currentTime - lastCheckTime > 5000) {
            startAsyncScan();
            lastCheckTime = currentTime;
        }

        processCompletedTasks();
    }

    private void clearOldCacheEntries() {
        if (solidBlockCache.size() > 1000) {
            solidBlockCache.clear();
        }
        if (blockStateCache.size() > 1000) {
            blockStateCache.clear();
        }
    }

    private void startAsyncScan() {
        Set<AABB> holes = getHolesFromHoleESP();
        if (holes == null || holes.isEmpty()) return;

        isScanning = true;

        List<AABB> newHoles = new ArrayList<>();
        for (AABB hole : holes) {
            if (!processedHoles.contains(hole) && !pendingProcessing.contains(hole)) {
                newHoles.add(hole);
                pendingProcessing.add(hole);
            }
        }

        int maxConcurrentTasks = Math.min(newHoles.size(), maxThreads.get());
        for (int i = 0; i < maxConcurrentTasks; i++) {
            if (i < newHoles.size()) {
                Future<Map.Entry<AABB, CoveredHoleInfo>> future =
                    executorService.submit(new HoleCheckTask(newHoles.get(i)));
                pendingTasks.add(future);
            }
        }

        coveredHoles.keySet().retainAll(holes);
        processedHoles.retainAll(holes);
        pendingProcessing.retainAll(holes);
    }

    private void processCompletedTasks() {
        Iterator<Future<Map.Entry<AABB, CoveredHoleInfo>>> iterator = pendingTasks.iterator();
        int processedCount = 0;
        final int maxProcessPerTick = 3;

        while (iterator.hasNext() && processedCount < maxProcessPerTick) {
            Future<Map.Entry<AABB, CoveredHoleInfo>> task = iterator.next();

            if (task.isDone()) {
                try {
                    Map.Entry<AABB, CoveredHoleInfo> result = task.get(1, TimeUnit.MILLISECONDS);
                    if (result != null) {
                        coveredHoles.put(result.getKey(), result.getValue());
                        pendingProcessing.remove(result.getKey());

                        if (chatNotifications.get() && !notifiedHoles.contains(result.getKey())) {
                            AABB hole = result.getKey();
                            BlockPos coverPos = result.getValue().coverPos;
                            int depth = (int) (hole.maxY - hole.minY);
                            info(String.format("Covered Hole found at %s (depth: %d)",
                                coverPos.toShortString(), depth));
                            notifiedHoles.add(result.getKey());
                        }
                    }
                } catch (Exception e) {
                } finally {
                    iterator.remove();
                    processedCount++;
                }
            }
        }

        if (pendingTasks.isEmpty()) {
            isScanning = false;
        }
    }

    private Set<AABB> getHolesFromHoleESP() {
        try {
            return holeESP != null ? holeESP.getHoles() : Collections.emptySet();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null) return;

        for (Map.Entry<AABB, CoveredHoleInfo> entry : coveredHoles.entrySet()) {
            AABB hole = entry.getKey();
            CoveredHoleInfo info = entry.getValue();

            try {
                event.renderer.box(
                    hole.minX, hole.minY, hole.minZ,
                    hole.maxX, hole.maxY, hole.maxZ,
                    sideColor.get(), lineColor.get(),
                    shapeMode.get(), 0
                );

                event.renderer.box(
                    info.coverPos.getX(), info.coverPos.getY(), info.coverPos.getZ(),
                    info.coverPos.getX() + 1, info.coverPos.getY() + 1, info.coverPos.getZ() + 1,
                    sideColor.get(), lineColor.get(),
                    shapeMode.get(), 0
                );
            } catch (Exception e) {
            }
        }
    }

    private class HoleCheckTask implements Callable<Map.Entry<AABB, CoveredHoleInfo>> {
        private final AABB hole;

        public HoleCheckTask(AABB hole) {
            this.hole = hole;
        }

        @Override
        public Map.Entry<AABB, CoveredHoleInfo> call() {
            try {
                if (mc.level == null) return null;

                BlockPos topPos = new BlockPos(
                    (int) hole.minX,
                    (int) hole.maxY,
                    (int) hole.minZ
                );

                if (isSolidBlockCached(topPos)) {
                    boolean isPlayerCovered = !onlyPlayerCovered.get() ||
                        isLikelyPlayerCovered(topPos, hole);

                    if (isPlayerCovered) {
                        return new AbstractMap.SimpleEntry<>(hole, new CoveredHoleInfo(topPos, hole));
                    }
                }

                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private boolean isLikelyPlayerCovered(BlockPos coverPos, AABB hole) {
            try {
                BlockState coverBlock = getBlockStateCached(coverPos);
                if (coverBlock == null) return false;

                if (isCommonBuildingBlock(coverBlock)) {
                    return true;
                }

                int matchingBlocks = 0;
                BlockPos[] checkPositions = {
                    coverPos.north(),
                    coverPos.south(),
                    coverPos.east(),
                    coverPos.west()
                };

                for (BlockPos pos : checkPositions) {
                    BlockState state = getBlockStateCached(pos);
                    if (state != null && state.getBlock() == coverBlock.getBlock()) {
                        matchingBlocks++;
                    }
                }

                return matchingBlocks < 2;
            } catch (Exception e) {
                return false;
            }
        }

        private boolean isCommonBuildingBlock(BlockState state) {
            if (state == null) return false;

            String blockName = state.getBlock().getDescriptionId().toLowerCase();
            return blockName.contains("cobblestone") ||
                blockName.contains("stone_brick") ||
                blockName.contains("plank") ||
                blockName.contains("log") ||
                blockName.contains("wool") ||
                blockName.contains("concrete") ||
                blockName.contains("terracotta") ||
                blockName.contains("glass");
        }

        private boolean isSolidBlockCached(BlockPos pos) {
            if (mc.level == null) return false;

            return solidBlockCache.computeIfAbsent(pos, p -> {
                try {
                    BlockState state = mc.level.getBlockState(p);
                    return state != null && state.isRedstoneConductor(mc.level, p);
                } catch (Exception e) {
                    return false;
                }
            });
        }

        private BlockState getBlockStateCached(BlockPos pos) {
            if (mc.level == null) return null;

            return blockStateCache.computeIfAbsent(pos, p -> {
                try {
                    return mc.level.getBlockState(p);
                } catch (Exception e) {
                    return null;
                }
            });
        }
    }

    private static class CoveredHoleInfo {
        public final BlockPos coverPos;
        public final AABB holeBox;

        public CoveredHoleInfo(BlockPos coverPos, AABB holeBox) {
            this.coverPos = coverPos;
            this.holeBox = holeBox;
        }
    }
}
