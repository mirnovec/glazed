package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Queue;

public class ChunkFinder extends Module {
    public enum Mode {
        Chat,
        Toast,
        Both
    }

    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgBaseDetect = settings.createGroup("Base Detectors");
    private final SettingGroup sgAmethyst = settings.createGroup("Amethyst");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgBlockHighlight = settings.createGroup("Block Highlighting");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");

    private final Setting<Boolean> baseDetectors = sgBaseDetect.add(new BoolSetting.Builder()
        .name("base-detectors")
        .description("Score chunks against the player-activity detectors below.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> minScore = sgBaseDetect.add(new IntSetting.Builder()
        .name("min-score")
        .description("Combined detector score needed to flag a chunk.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 30)
        .visible(baseDetectors::get)
        .build());

    private final Setting<SettingColor> baseChunkColor = sgBaseDetect.add(new ColorSetting.Builder()
        .name("base-chunk-color")
        .description("Colour for chunks flagged by the detectors.")
        .defaultValue(new SettingColor(0, 255, 80, 60))
        .visible(baseDetectors::get)
        .build());

    private final Setting<Boolean> detCobble = sgBaseDetect.add(new BoolSetting.Builder()
        .name("cobble-lines").description("Runs of cobblestone below y=0.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detDeepslateBand = sgBaseDetect.add(new BoolSetting.Builder()
        .name("deepslate-band").description("Polished/brick/tile/chiselled deepslate below y=0.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detObsidian = sgBaseDetect.add(new BoolSetting.Builder()
        .name("obsidian-backbone").description("Obsidian below y=20.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detVeinInt = sgBaseDetect.add(new BoolSetting.Builder()
        .name("vein-integrity").description("Valuable ore sitting next to storage.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detVertVein = sgBaseDetect.add(new BoolSetting.Builder()
        .name("vertical-vein").description("Ore stacked several deep in one column.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detNeedle = sgBaseDetect.add(new BoolSetting.Builder()
        .name("needle-hole").description("1x1 shafts mined straight down.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detHiddenOre = sgBaseDetect.add(new BoolSetting.Builder()
        .name("hidden-ore").description("Ore walled in on five of six sides.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detPiston = sgBaseDetect.add(new BoolSetting.Builder()
        .name("piston-array").description("Pistons below y=20.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detMobCluster = sgBaseDetect.add(new BoolSetting.Builder()
        .name("mob-cluster").description("Unusually dense mob counts.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detDropped = sgBaseDetect.add(new BoolSetting.Builder()
        .name("dropped-items").description("Item entities below y=0.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detAirPocket = sgBaseDetect.add(new BoolSetting.Builder()
        .name("air-pocket").description("Large hollowed-out cavities.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detStair = sgBaseDetect.add(new BoolSetting.Builder()
        .name("synthetic-stair").description("Crafted stair blocks below y=0.").defaultValue(true).visible(baseDetectors::get).build());
    private final Setting<Boolean> detPolarBear = sgBaseDetect.add(new BoolSetting.Builder()
        .name("polar-bear").description("Polar bears, which do not spawn underground naturally.").defaultValue(true).visible(baseDetectors::get).build());

    private final Setting<Boolean> detectAmethyst = sgAmethyst.add(new BoolSetting.Builder()
        .name("detect-amethyst")
        .description("Flag chunks containing amethyst geodes.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> amethystThreshold = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-threshold")
        .description("Connected amethyst blocks needed to flag the chunk.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 100)
        .visible(detectAmethyst::get)
        .build());

    private final Setting<SettingColor> amethystChunkColor = sgAmethyst.add(new ColorSetting.Builder()
        .name("amethyst-chunk-color")
        .description("Colour for chunks holding a geode.")
        .defaultValue(new SettingColor(180, 100, 255, 60))
        .visible(detectAmethyst::get)
        .build());

    private final Setting<Boolean> detectDeepslate = sgDetection.add(new BoolSetting.Builder()
        .name("detect-deepslate")
        .description("Find deepslate blocks")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> detectCobbledDeepslate = sgDetection.add(new BoolSetting.Builder()
        .name("detect-cobbled-deepslate")
        .description("Find cobbled deepslate blocks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> detectRotatedDeepslate = sgDetection.add(new BoolSetting.Builder()
        .name("detect-rotated-deepslate")
        .description("Find rotated deepslate blocks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> detectEndStone = sgDetection.add(new BoolSetting.Builder()
        .name("detect-end-stone")
        .description("Find end stone blocks (disabled in The End dimension)")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreExposed = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-exposed")
        .description("Ignore suspicious blocks that are exposed to air or fluid (treats water/lava like air)")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreTrialChambers = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-trial-chambers")
        .description("Ignore chunks containing trial chambers (based on waxed copper blocks and tuff bricks)")
        .defaultValue(true)
        .build());

    private final Setting<Integer> trialChamberThreshold = sgDetection.add(new IntSetting.Builder()
        .name("trial-chamber-threshold")
        .description("Minimum waxed copper or tuff brick blocks to identify a trial chamber")
        .defaultValue(50)
        .range(1, 50)
        .sliderRange(1, 50)
        .visible(ignoreTrialChambers::get)
        .build());

    private final Setting<Integer> deepslateThreshold = sgDetection.add(new IntSetting.Builder()
        .name("deepslate-threshold")
        .description("Min deepslate to flag chunk")
        .defaultValue(1)
        .range(1, 15)
        .sliderRange(1, 15)
        .visible(detectDeepslate::get)
        .build());

    private final Setting<Integer> cobbledDeepslateThreshold = sgDetection.add(new IntSetting.Builder()
        .name("cobbled-deepslate-threshold")
        .description("Min cobbled deepslate to flag chunk")
        .defaultValue(4)
        .range(1, 15)
        .sliderRange(1, 15)
        .visible(detectCobbledDeepslate::get)
        .build());

    private final Setting<Integer> rotatedDeepslateThreshold = sgDetection.add(new IntSetting.Builder()
        .name("rotated-threshold")
        .description("Min rotated deepslate to flag chunk")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 20)
        .visible(detectRotatedDeepslate::get)
        .build());

    private final Setting<Integer> endStoneThreshold = sgDetection.add(new IntSetting.Builder()
        .name("end-stone-threshold")
        .description("Min end stone count to flag chunk")
        .defaultValue(2)
        .range(1, 15)
        .sliderRange(1, 15)
        .visible(detectEndStone::get)
        .build());

    private final Setting<Double> renderY = sgRender.add(new DoubleSetting.Builder()
        .name("render-height")
        .description("Height to render chunk highlights")
        .defaultValue(64.0)
        .range(-64.0, 320.0)
        .sliderRange(-64.0, 320.0)
        .build());

    private final Setting<ShapeMode> renderMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("render-mode")
        .description("How to render highlighted chunks")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color for suspicious chunks")
        .defaultValue(new SettingColor(255, 215, 0, 120))
        .build());

    private final Setting<Double> thickness = sgRender.add(new DoubleSetting.Builder()
        .name("thickness")
        .description("Thickness of highlight box")
        .defaultValue(0.3)
        .range(0.1, 2.0)
        .sliderRange(0.1, 2.0)
        .build());

    private final Setting<Boolean> highlightBlocks = sgBlockHighlight.add(new BoolSetting.Builder()
        .name("highlight-blocks")
        .description("Highlight individual suspicious blocks")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxBlocksToRender = sgBlockHighlight.add(new IntSetting.Builder()
        .name("max-blocks-render")
        .description("Maximum number of blocks to highlight (performance)")
        .defaultValue(200)
        .range(50, 1000)
        .sliderRange(50, 1000)
        .visible(highlightBlocks::get)
        .build());

    private final Setting<ShapeMode> blockRenderMode = sgBlockHighlight.add(new EnumSetting.Builder<ShapeMode>()
        .name("block-render-mode")
        .description("How to render individual blocks")
        .defaultValue(ShapeMode.Lines)
        .visible(highlightBlocks::get)
        .build());

    private final Setting<SettingColor> deepslateBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("deepslate-color")
        .description("Color for deepslate blocks")
        .defaultValue(new SettingColor(100, 100, 100, 200))
        .visible(highlightBlocks::get)
        .build());

    private final Setting<SettingColor> cobbledDeepslateBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("cobbled-deepslate-color")
        .description("Color for cobbled deepslate blocks")
        .defaultValue(new SettingColor(80, 80, 80, 200))
        .visible(highlightBlocks::get)
        .build());

    private final Setting<SettingColor> rotatedDeepslateBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("rotated-deepslate-color")
        .description("Color for rotated deepslate blocks")
        .defaultValue(new SettingColor(120, 0, 120, 200))
        .visible(highlightBlocks::get)
        .build());

    private final Setting<SettingColor> endStoneBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("end-stone-color")
        .description("Color for end stone blocks")
        .defaultValue(new SettingColor(255, 255, 200, 200))
        .visible(highlightBlocks::get)
        .build());

    private final Setting<Boolean> useMultiThreading = sgPerformance.add(new BoolSetting.Builder()
        .name("threading")
        .description("Use background threads for scanning")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadCount = sgPerformance.add(new IntSetting.Builder()
        .name("thread-count")
        .description("Number of worker threads")
        .defaultValue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))
        .range(1, 4)
        .sliderRange(1, 4)
        .visible(useMultiThreading::get)
        .build());

    private final Setting<Integer> scanInterval = sgPerformance.add(new IntSetting.Builder()
        .name("scan-delay")
        .description("Milliseconds between scans")
        .defaultValue(100)
        .range(50, 2000)
        .sliderRange(50, 2000)
        .build());

    private final Setting<Integer> maxConcurrentScans = sgPerformance.add(new IntSetting.Builder()
        .name("max-concurrent-scans")
        .description("Max chunks scanned simultaneously")
        .defaultValue(3)
        .range(1, 8)
        .sliderRange(1, 8)
        .build());

    private final Setting<Integer> cleanupInterval = sgPerformance.add(new IntSetting.Builder()
        .name("cleanup-interval")
        .description("Seconds between distant chunk cleanup")
        .defaultValue(30)
        .range(15, 300)
        .sliderRange(15, 300)
        .build());

    private final Setting<Mode> notificationMode = sgNotifications.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("How to notify when suspicious chunks are detected")
        .defaultValue(Mode.Both)
        .build());

    private final Setting<Boolean> playSound = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-alerts")
        .description("Play sound when suspicious chunks or blocks are found")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chatAlerts = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Send chat notifications for suspicious chunks or blocks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> trialChamberAlerts = sgNotifications.add(new BoolSetting.Builder()
        .name("trial-chamber-alerts")
        .description("Send chat notifications for trial chambers")
        .defaultValue(false)
        .build());

    private final Setting<Integer> maxAlerts = sgNotifications.add(new IntSetting.Builder()
        .name("max-alerts")
        .description("Max alerts per minute")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build());

    private final Set<ChunkPos> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> baseFlaggedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> amethystChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, ChunkAnalysis> chunkData = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, Long> notificationTimes = new ConcurrentHashMap<>();
    private final Queue<Long> recentAlerts = new ConcurrentLinkedQueue<>();
    private final AtomicLong activeScanCount = new AtomicLong(0);
    private final Map<BlockPos, SuspiciousBlock> suspiciousBlocks = new ConcurrentHashMap<>();

    private ExecutorService scannerPool;
    private volatile boolean shouldScan = false;
    private long lastCleanup = 0;

    public ChunkFinder() {
        super(GlazedAddon.esp, "chunk-finder", "ChunkFinderV4");
    }

    @Override
    public void onActivate() {
        if (mc.level == null) return;

        clearAll();
        shouldScan = true;
        lastCleanup = System.currentTimeMillis();

        if (useMultiThreading.get()) {
            scannerPool = Executors.newFixedThreadPool(threadCount.get(), r -> {
                Thread t = new Thread(r, "ChunkFinder-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            startInitialScan();
        } else {
            startInitialScan();
        }
    }

    @Override
    public void onDeactivate() {
        shouldScan = false;

        if (scannerPool != null) {
            scannerPool.shutdownNow();
            scannerPool = null;
        }

        clearAll();
    }

    private void clearAll() {
        flaggedChunks.clear();
        baseFlaggedChunks.clear();
        amethystChunks.clear();
        chunkData.clear();
        scannedChunks.clear();
        notificationTimes.clear();
        recentAlerts.clear();
        suspiciousBlocks.clear();
        activeScanCount.set(0);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.level == null || mc.player == null) return;

        long now = System.currentTimeMillis();

        while (!recentAlerts.isEmpty() && now - recentAlerts.peek() > 60000) {
            recentAlerts.poll();
        }

        if (now - lastCleanup > cleanupInterval.get() * 1000L) {
            performCleanup();
            lastCleanup = now;
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (!shouldScan || activeScanCount.get() >= maxConcurrentScans.get()) return;

        ChunkPos pos = event.chunk().getPos();
        if (!scannedChunks.contains(pos)) {
            scheduleChunkScan(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!shouldScan) return;

        BlockPos blockPos = event.pos;
        if (blockPos.getY() < 0 || blockPos.getY() > 128) return;

        BlockState newState = event.newState;
        if (isRelevantBlock(newState) || newState.isAir()) {
            ChunkPos chunkPos = new ChunkPos(blockPos);
            LevelChunk chunk = (LevelChunk) mc.level.getChunk(chunkPos.x, chunkPos.z);
            scheduleChunkScan(chunk);
        }
    }

    private boolean isRelevantBlock(BlockState state) {
        Block block = state.getBlock();
        return
            block == Blocks.DEEPSLATE ||
                block == Blocks.COBBLED_DEEPSLATE ||
                block == Blocks.POLISHED_DEEPSLATE ||
                block == Blocks.DEEPSLATE_BRICKS ||
                block == Blocks.DEEPSLATE_TILES ||
                block == Blocks.CHISELED_DEEPSLATE ||
                block == Blocks.END_STONE ||
                block == Blocks.WAXED_COPPER_BLOCK ||
                block == Blocks.WAXED_OXIDIZED_COPPER ||
                block == Blocks.TUFF_BRICKS;
    }

    private void startInitialScan() {
        Runnable initialScanTask = () -> {
            try {
                for (ChunkAccess chunk : Utils.chunks()) {
                    if (!shouldScan) break;
                    if (chunk instanceof LevelChunk worldChunk) {
                        if (activeScanCount.get() < maxConcurrentScans.get()) {
                            if (useMultiThreading.get() && scannerPool != null) {
                                scannerPool.submit(() -> analyzeChunk(worldChunk));
                            } else {
                                analyzeChunk(worldChunk);
                            }
                        }
                        Thread.sleep(scanInterval.get());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        if (useMultiThreading.get() && scannerPool != null) {
            scannerPool.submit(initialScanTask);
        } else {
            new Thread(initialScanTask, "ChunkFinder-Initial").start();
        }
    }

    private void scheduleChunkScan(ChunkAccess chunk) {
        if (!(chunk instanceof LevelChunk worldChunk)) return;
        if (activeScanCount.get() >= maxConcurrentScans.get()) return;

        Runnable scanTask = () -> {
            try {
                Thread.sleep(scanInterval.get() / 2);
                analyzeChunk(worldChunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        if (useMultiThreading.get() && scannerPool != null) {
            scannerPool.submit(scanTask);
        } else {
            new Thread(scanTask, "ChunkFinder-Scan").start();
        }
    }

    private void analyzeChunk(LevelChunk chunk) {
        if (!shouldScan || chunk == null) return;

        ChunkPos pos = chunk.getPos();
        if (scannedChunks.contains(pos)) return;

        activeScanCount.incrementAndGet();
        try {
            scannedChunks.add(pos);

            int minY = 0;
            int maxY = Math.min(chunk.getMinY() + chunk.getHeight(), 128);

            ChunkAnalysis analysis = new ChunkAnalysis();

            scanChunkSections(chunk, analysis, minY, maxY);

            if (baseDetectors.get()) analyzeBaseSignals(chunk, analysis);
            if (detectAmethyst.get()) analysis.amethystGeode = largestAmethystGeode(chunk);

            chunkData.put(pos, analysis);
            evaluateChunk(pos, analysis);
        } finally {
            activeScanCount.decrementAndGet();
        }
    }

    private void scanChunkSections(LevelChunk chunk, ChunkAnalysis analysis, int minY, int maxY) {
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            if (!shouldScan) return;

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY = chunk.getMinY() + sectionIndex * 16;
            int startY = Math.max(0, minY - sectionY);
            int endY = Math.min(15, maxY - sectionY);

            if (startY > 15 || endY < 0) continue;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = startY; y <= endY; y++) {
                        if (!shouldScan) return;

                        BlockState state = section.getBlockState(x, y, z);
                        int worldY = sectionY + y;
                        BlockPos blockPos = new BlockPos(chunk.getPos().getMinBlockX() + x, worldY, chunk.getPos().getMinBlockZ() + z);

                        analyzeBlock(blockPos, state, worldY, analysis);
                    }
                }
            }
        }
    }

    private void analyzeBlock(BlockPos blockPos, BlockState state, int worldY, ChunkAnalysis analysis) {
        SuspiciousBlockType blockType = null;

        if (ignoreTrialChambers.get() && isTrialChamberBlock(state)) {
            analysis.trialChamberCount++;
        }

        boolean exposed = false;
        if (ignoreExposed.get()) {
            exposed = isExposedToAirOrFluid(blockPos);
        }

        if (detectDeepslate.get() && isNormalDeepslate(state) && !exposed && !isInLargeDeepslateLine(blockPos, worldY)) {
            analysis.deepslateCount++;
            blockType = SuspiciousBlockType.DEEPSLATE;
        }

        if (detectRotatedDeepslate.get() && isRotatedDeepslateBlock(state) && !exposed) {
            analysis.rotatedDeepslateCount++;
            blockType = SuspiciousBlockType.ROTATED_DEEPSLATE;
        }

        if (detectCobbledDeepslate.get() && isCobbledDeepslate(state) && !exposed) {
            analysis.cobbledDeepslateCount++;
            blockType = SuspiciousBlockType.COBBLED_DEEPSLATE;
        }

        if (detectEndStone.get() && isEndStone(state) && mc.level.dimension() != Level.END && !exposed) {
            analysis.endStoneCount++;
            blockType = SuspiciousBlockType.END_STONE;
        }

        if (blockType != null && highlightBlocks.get()) {
            suspiciousBlocks.put(blockPos, new SuspiciousBlock(blockType, System.currentTimeMillis()));
        }
    }

    private boolean isValidBlockPos(BlockPos pos) {
        return pos.getY() >= mc.level.getMinY() && pos.getY() < mc.level.getHeight();
    }

    private boolean isExposedToAirOrFluid(BlockPos pos) {
        if (mc.level == null) return false;

        for (Direction dir : Direction.values()) {
            BlockPos offset = pos.relative(dir);
            if (!isValidBlockPos(offset)) continue;
            BlockState neighbor = mc.level.getBlockState(offset);
            if (neighbor.isAir()) return true;

            FluidState f = neighbor.getFluidState();
            if (f != null && !f.isEmpty()) return true;
        }
        return false;
    }

    private boolean isInLargeDeepslateLine(BlockPos pos, int worldY) {
        if (mc.level == null) return false;

        final int lineThreshold = worldY > -8 ? 50 : 20;

        int xCount = 1;
        for (int i = 1; i < lineThreshold; i++) {
            BlockPos next = pos.relative(Direction.EAST, i);
            if (!isValidBlockPos(next) || !isNormalDeepslate(mc.level.getBlockState(next))) break;
            xCount++;
        }
        for (int i = 1; i < lineThreshold; i++) {
            BlockPos prev = pos.relative(Direction.WEST, i);
            if (!isValidBlockPos(prev) || !isNormalDeepslate(mc.level.getBlockState(prev))) break;
            xCount++;
        }
        if (xCount >= lineThreshold) return true;

        int zCount = 1;
        for (int i = 1; i < lineThreshold; i++) {
            BlockPos next = pos.relative(Direction.SOUTH, i);
            if (!isValidBlockPos(next) || !isNormalDeepslate(mc.level.getBlockState(next))) break;
            zCount++;
        }
        for (int i = 1; i < lineThreshold; i++) {
            BlockPos prev = pos.relative(Direction.NORTH, i);
            if (!isValidBlockPos(prev) || !isNormalDeepslate(mc.level.getBlockState(prev))) break;
            zCount++;
        }
        if (zCount >= lineThreshold) return true;

        if (worldY > 0) {
            int yCount = 1;
            for (int i = 1; i < lineThreshold; i++) {
                BlockPos up = pos.relative(Direction.UP, i);
                if (!isValidBlockPos(up) || !isNormalDeepslate(mc.level.getBlockState(up))) break;
                yCount++;
            }
            for (int i = 1; i < lineThreshold; i++) {
                BlockPos down = pos.relative(Direction.DOWN, i);
                if (!isValidBlockPos(down) || !isNormalDeepslate(mc.level.getBlockState(down))) break;
                yCount++;
            }
            if (yCount >= lineThreshold) return true;
        }

        return false;
    }

    // like 300k block reads per chunk. dont max the radius then complain abt fps
    private void analyzeBaseSignals(LevelChunk chunk, ChunkAnalysis analysis) {
        ChunkPos pos = chunk.getPos();
        int originX = pos.getMinBlockX();
        int originZ = pos.getMinBlockZ();
        int bottom = chunk.getMinY();
        int top = Math.min(chunk.getMinY() + chunk.getHeight() - 1, 60);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (!shouldScan) return;

                int worldX = originX + x;
                int worldZ = originZ + z;

                int airRun = 0;
                int oreColumn = 0;
                int deepAirRun = 0;

                for (int y = bottom; y <= top; y++) {
                    cursor.set(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(cursor);
                    Block block = state.getBlock();

                    boolean isAir = block == Blocks.AIR || block == Blocks.CAVE_AIR;

                    if (isAir) {
                        airRun++;
                        if (y < 0) deepAirRun++;
                    } else {
                        // original never reset this so one big cave scored over and over
                        if (airRun >= 12 && y < 20) analysis.airPocket += airRun / 4;
                        airRun = 0;
                        if (y < 0) deepAirRun = 0;
                    }

                    if (y < 0 && (block == Blocks.COBBLESTONE || block == Blocks.COBBLED_DEEPSLATE || block == Blocks.MOSSY_COBBLESTONE)) {
                        cursor.set(worldX + 1, y, worldZ);
                        boolean sameEast = worldX + 1 < originX + 16 && chunk.getBlockState(cursor).getBlock() == block;
                        cursor.set(worldX, y, worldZ);
                        if (sameEast) analysis.cobbleLines++;
                    }

                    if (y < 0 && (block == Blocks.POLISHED_DEEPSLATE || block == Blocks.DEEPSLATE_BRICKS
                        || block == Blocks.DEEPSLATE_TILES || block == Blocks.CHISELED_DEEPSLATE)) {
                        analysis.deepslateBand++;
                    }

                    if (y < 20 && block == Blocks.OBSIDIAN) analysis.obsidian++;
                    if (y < 20 && isPiston(block)) analysis.pistons++;
                    if (y < 0 && isStairBlock(block)) analysis.stairs++;

                    if (isOre(block)) {
                        oreColumn++;
                        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE || block == Blocks.ANCIENT_DEBRIS) {
                            analysis.valuableOre++;
                        }
                        if (isHiddenOre(chunk, worldX, y, worldZ)) analysis.hiddenOre += 2;
                    } else {
                        oreColumn = 0;
                    }

                    if (oreColumn == 4) analysis.vertVeins++;
                }

                if (deepAirRun >= 10) analysis.needles++;
            }
        }

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof HopperBlockEntity) analysis.storage++;
        }

        if (mc.level == null) return;

        int mobs = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!new ChunkPos(entity.blockPosition()).equals(pos)) continue;

            if (entity instanceof ItemEntity && entity.getY() < 0.0) analysis.droppedItems++;
            if (entity instanceof PolarBear) analysis.polarBears += 3;
            if (entity instanceof Player) continue;

            mobs++;
        }

        if (mobs > 20) analysis.mobCluster = mobs / 5;
    }

    private int largestAmethystGeode(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int originX = pos.getMinBlockX();
        int originZ = pos.getMinBlockZ();
        int bottom = Math.max(chunk.getMinY(), -64);
        int top = Math.min(chunk.getMinY() + chunk.getHeight() - 1, 30);

        Set<BlockPos> amethyst = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = bottom; y <= top; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    cursor.set(originX + x, y, originZ + z);
                    if (isAmethystLike(chunk.getBlockState(cursor))) amethyst.add(cursor.immutable());
                }
            }
        }

        if (amethyst.isEmpty()) return 0;

        Set<BlockPos> visited = new HashSet<>();
        int largest = 0;

        for (BlockPos seed : amethyst) {
            if (!visited.add(seed)) continue;

            int size = 0;
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                size++;

                for (Direction direction : Direction.values()) {
                    BlockPos next = current.relative(direction);
                    if (amethyst.contains(next) && visited.add(next)) queue.add(next);
                }
            }

            if (size > largest) largest = size;
        }

        return largest;
    }

    private boolean isHiddenOre(LevelChunk chunk, int x, int y, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int enclosed = 0;

        for (Direction direction : Direction.values()) {
            cursor.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());

            // stay in this chunk, neighbours might not be loaded on the scan thread
            if (cursor.getX() < chunk.getPos().getMinBlockX() || cursor.getX() > chunk.getPos().getMaxBlockX()) continue;
            if (cursor.getZ() < chunk.getPos().getMinBlockZ() || cursor.getZ() > chunk.getPos().getMaxBlockZ()) continue;

            Block neighbour = chunk.getBlockState(cursor).getBlock();
            if (neighbour == Blocks.COBBLESTONE || neighbour == Blocks.COBBLED_DEEPSLATE || neighbour == Blocks.STONE
                || neighbour == Blocks.DEEPSLATE || neighbour == Blocks.OBSIDIAN || neighbour == Blocks.NETHERRACK) {
                enclosed++;
            }
        }

        return enclosed >= 5;
    }

    private static boolean isPiston(Block block) {
        return block == Blocks.PISTON || block == Blocks.STICKY_PISTON || block == Blocks.MOVING_PISTON || block == Blocks.PISTON_HEAD;
    }

    private static boolean isAmethystLike(BlockState state) {
        return state.is(Blocks.AMETHYST_CLUSTER)
            || state.is(Blocks.LARGE_AMETHYST_BUD)
            || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.SMALL_AMETHYST_BUD)
            || state.is(Blocks.AMETHYST_BLOCK)
            || state.is(Blocks.BUDDING_AMETHYST);
    }

    private static boolean isOre(Block block) {
        return block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
            || block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
            || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
            || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
            || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
            || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
            || block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
            || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
            || block == Blocks.ANCIENT_DEBRIS;
    }

    private static boolean isStairBlock(Block block) {
        return block == Blocks.STONE_STAIRS || block == Blocks.COBBLESTONE_STAIRS
            || block == Blocks.DEEPSLATE_BRICK_STAIRS || block == Blocks.POLISHED_DEEPSLATE_STAIRS
            || block == Blocks.OAK_STAIRS || block == Blocks.SPRUCE_STAIRS
            || block == Blocks.DARK_OAK_STAIRS || block == Blocks.COBBLED_DEEPSLATE_STAIRS;
    }

    private int scoreBaseSignals(ChunkAnalysis a, List<String> tags) {
        int score = 0;

        if (detCobble.get() && a.cobbleLines >= 3) { score += Math.min(a.cobbleLines, 6); tags.add("CobbleLine(" + a.cobbleLines + ")"); }
        if (detDeepslateBand.get() && a.deepslateBand >= 4) { score += Math.min(a.deepslateBand / 2, 5); tags.add("Deepslate(" + a.deepslateBand + ")"); }
        if (detObsidian.get() && a.obsidian >= 3) { score += Math.min(a.obsidian, 6); tags.add("Obsidian(" + a.obsidian + ")"); }
        if (detVeinInt.get() && a.valuableOre >= 1 && a.storage >= 1) { score += 4; tags.add("VeinInt"); }
        if (detVertVein.get() && a.vertVeins >= 2) { score += a.vertVeins; tags.add("VertVein(" + a.vertVeins + ")"); }
        if (detNeedle.get() && a.needles >= 2) { score += a.needles * 2; tags.add("Needle(" + a.needles + ")"); }
        if (detHiddenOre.get() && a.hiddenOre >= 2) { score += a.hiddenOre; tags.add("HiddenOre(" + a.hiddenOre + ")"); }
        if (detPiston.get() && a.pistons >= 3) { score += Math.min(a.pistons, 8); tags.add("PistonArray(" + a.pistons + ")"); }
        if (detMobCluster.get() && a.mobCluster >= 2) { score += a.mobCluster; tags.add("MobCluster(" + a.mobCluster * 5 + ")"); }
        if (detDropped.get() && a.droppedItems >= 2) { score += a.droppedItems * 2; tags.add("Dropped(" + a.droppedItems + ")"); }
        if (detAirPocket.get() && a.airPocket >= 5) { score += a.airPocket / 3; tags.add("AirPocket(" + a.airPocket + ")"); }
        if (detStair.get() && a.stairs >= 5) { score += a.stairs / 3; tags.add("Stairs(" + a.stairs + ")"); }
        if (detPolarBear.get() && a.polarBears > 0) { score += a.polarBears; tags.add("PolarBear"); }
        if (a.storage >= 3) { score += a.storage; tags.add("Storage(" + a.storage + ")"); }

        return score;
    }

    private void evaluateChunk(ChunkPos pos, ChunkAnalysis analysis) {
        if (ignoreTrialChambers.get() && analysis.trialChamberCount >= trialChamberThreshold.get()) {
            if (trialChamberAlerts.get() && mc.player != null) {
                String message = String.format("ChunkFinder [%d, %d] - Trial chamber detected - Copper/Tuff blocks: %d",
                    pos.x, pos.z, analysis.trialChamberCount);
                notifyTrialChamber(message);
            }
            flaggedChunks.remove(pos);
            notificationTimes.remove(pos);
            return;
        }

        boolean suspicious = false;
        StringBuilder reasons = new StringBuilder();

        if (detectDeepslate.get() && analysis.deepslateCount >= deepslateThreshold.get()) {
            suspicious = true;
            reasons.append("Deepslate[").append(analysis.deepslateCount).append("] ");
        }

        if (detectCobbledDeepslate.get() && analysis.cobbledDeepslateCount >= cobbledDeepslateThreshold.get()) {
            suspicious = true;
            reasons.append("CobbledDeepslate[").append(analysis.cobbledDeepslateCount).append("] ");
        }

        if (detectRotatedDeepslate.get() && analysis.rotatedDeepslateCount >= rotatedDeepslateThreshold.get()) {
            suspicious = true;
            reasons.append("RotatedDeepslate[").append(analysis.rotatedDeepslateCount).append("] ");
        }

        if (detectEndStone.get() && analysis.endStoneCount >= endStoneThreshold.get()) {
            suspicious = true;
            reasons.append("EndStone[").append(analysis.endStoneCount).append("] ");
        }

        if (baseDetectors.get()) {
            List<String> tags = new ArrayList<>();
            int score = scoreBaseSignals(analysis, tags);

            if (score >= minScore.get()) {
                if (baseFlaggedChunks.add(pos)) {
                    notifyChunkFound(pos, "Base? S:" + score + " [" + String.join(", ", tags) + "]");
                }
            } else {
                baseFlaggedChunks.remove(pos);
            }
        } else {
            baseFlaggedChunks.remove(pos);
        }

        if (detectAmethyst.get() && analysis.amethystGeode >= amethystThreshold.get()) {
            if (amethystChunks.add(pos)) {
                notifyChunkFound(pos, "Amethyst[" + analysis.amethystGeode + "]");
            }
        } else {
            amethystChunks.remove(pos);
        }

        if (suspicious) {
            if (flaggedChunks.add(pos)) {
                notifyChunkFound(pos, reasons.toString().trim());
            }
        } else {
            flaggedChunks.remove(pos);
            notificationTimes.remove(pos);
        }
    }

    private boolean isNormalDeepslate(BlockState state) {
        Block block = state.getBlock();
        if (block != Blocks.DEEPSLATE || !state.hasProperty(BlockStateProperties.AXIS)) return false;
        Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
        return axis == Direction.Axis.Y;
    }

    private boolean isCobbledDeepslate(BlockState state) {
        return state.getBlock() == Blocks.COBBLED_DEEPSLATE;
    }

    private boolean isRotatedDeepslateBlock(BlockState state) {
        Block block = state.getBlock();
        if (block != Blocks.DEEPSLATE || !state.hasProperty(BlockStateProperties.AXIS)) return false;
        Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
        return axis != Direction.Axis.Y;
    }

    private boolean isEndStone(BlockState state) {
        return state.getBlock() == Blocks.END_STONE;
    }

    private boolean isTrialChamberBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.WAXED_COPPER_BLOCK ||
            block == Blocks.WAXED_OXIDIZED_COPPER ||
            block == Blocks.TUFF_BRICKS;
    }

    private void notifyChunkFound(ChunkPos pos, String details) {
        long now = System.currentTimeMillis();

        if (recentAlerts.size() >= maxAlerts.get()) return;

        Long lastNotification = notificationTimes.get(pos);
        if (lastNotification != null && now - lastNotification < 45000) return;

        String message = String.format("ChunkFinder [%d, %d] - Suspicious chunk detected - %s", pos.x, pos.z, details);

        mc.execute(() -> {
            switch (notificationMode.get()) {
                case Chat -> {
                    if (chatAlerts.get() && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    }
                }
                case Toast -> {
                    mc.getToastManager().addToast(new MeteorToast(Items.CHEST, "ChunkFinder", message));
                }
                case Both -> {
                    if (chatAlerts.get() && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    }
                    mc.getToastManager().addToast(new MeteorToast(Items.CHEST, "ChunkFinder", message));
                }
            }

            if (playSound.get()) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f));
            }

            recentAlerts.offer(now);
            notificationTimes.put(pos, now);
        });
    }

    private void notifyTrialChamber(String message) {
        long now = System.currentTimeMillis();

        if (recentAlerts.size() >= maxAlerts.get()) return;

        String[] parts = message.split(" - ", 2);
        String coordsPart = parts[0].replace("ChunkFinder ", "");
        String detailsPart = parts.length > 1 ? parts[1] : "";

        mc.execute(() -> {
            switch (notificationMode.get()) {
                case Chat -> {
                    if (trialChamberAlerts.get() && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    }
                }
                case Toast -> {
                    mc.getToastManager().addToast(new MeteorToast(Items.CHEST, "ChunkFinder", String.format("%s - %s", coordsPart, detailsPart)));
                }
                case Both -> {
                    if (trialChamberAlerts.get() && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    }
                    mc.getToastManager().addToast(new MeteorToast(Items.CHEST, "ChunkFinder", String.format("%s - %s", coordsPart, detailsPart)));
                }
            }

            if (playSound.get()) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f));
            }

            recentAlerts.offer(now);
        });
    }

    private void performCleanup() {
        if (mc.player == null) return;

        int viewDist = mc.options.renderDistance().get();
        int playerChunkX = (int) mc.player.getX() / 16;
        int playerChunkZ = (int) mc.player.getZ() / 16;

        flaggedChunks.removeIf(pos -> {
            int dx = Math.abs(pos.x - playerChunkX);
            int dz = Math.abs(pos.z - playerChunkZ);
            boolean tooFar = dx > viewDist + 5 || dz > viewDist + 5;

            if (tooFar) {
                chunkData.remove(pos);
                notificationTimes.remove(pos);
            }
            return tooFar;
        });

        scannedChunks.removeIf(pos -> {
            int dx = Math.abs(pos.x - playerChunkX);
            int dz = Math.abs(pos.z - playerChunkZ);
            return dx > viewDist + 3 || dz > viewDist + 3;
        });

        suspiciousBlocks.entrySet().removeIf(entry -> {
            BlockPos blockPos = entry.getKey();
            double distance = mc.player.position().distanceTo(Vec3.atCenterOf(blockPos));
            return distance > viewDist * 16 + 80;
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        if (!flaggedChunks.isEmpty()) {
            Color highlight = new Color(chunkColor.get());
            int rendered = 0;
            for (ChunkPos pos : flaggedChunks) {
                if (rendered++ > 50) break;
                renderChunkHighlight(event, pos, highlight);
            }
        }

        if (!baseFlaggedChunks.isEmpty()) {
            Color highlight = new Color(baseChunkColor.get());
            int rendered = 0;
            for (ChunkPos pos : baseFlaggedChunks) {
                if (rendered++ > 50) break;
                renderChunkHighlight(event, pos, highlight);
            }
        }

        if (!amethystChunks.isEmpty()) {
            Color highlight = new Color(amethystChunkColor.get());
            int rendered = 0;
            for (ChunkPos pos : amethystChunks) {
                if (rendered++ > 50) break;
                renderChunkHighlight(event, pos, highlight);
            }
        }

        if (highlightBlocks.get()) {
            renderSuspiciousBlocks(event);
        }
    }

    private void renderChunkHighlight(Render3DEvent event, ChunkPos pos, Color color) {
        int startX = pos.getMinBlockX();
        int startZ = pos.getMinBlockZ();
        int endX = pos.getMaxBlockX();
        int endZ = pos.getMaxBlockZ();

        double y = renderY.get();
        double h = thickness.get();

        AABB box = new AABB(startX, y, startZ, endX + 1, y + h, endZ + 1);
        event.renderer.box(box, color, color, renderMode.get(), 0);
    }

    private void renderSuspiciousBlocks(Render3DEvent event) {
        int rendered = 0;

        for (Map.Entry<BlockPos, SuspiciousBlock> entry : suspiciousBlocks.entrySet()) {
            if (rendered >= maxBlocksToRender.get()) break;

            BlockPos pos = entry.getKey();
            SuspiciousBlock suspiciousBlock = entry.getValue();

            double distance = mc.player.position().distanceTo(Vec3.atCenterOf(pos));
            if (distance > mc.options.renderDistance().get() * 16) continue;

            Color blockColor = getColorForBlockType(suspiciousBlock.type);
            if (blockColor != null) {
                AABB box = new AABB(pos);
                event.renderer.box(box, blockColor, blockColor, blockRenderMode.get(), 0);
                rendered++;
            }
        }
    }

    private Color getColorForBlockType(SuspiciousBlockType type) {
        return switch (type) {
            case DEEPSLATE -> new Color(deepslateBlockColor.get());
            case COBBLED_DEEPSLATE -> new Color(cobbledDeepslateBlockColor.get());
            case ROTATED_DEEPSLATE -> new Color(rotatedDeepslateBlockColor.get());
            case END_STONE -> new Color(endStoneBlockColor.get());
            default -> null;
        };
    }

    @Override
    public String getInfoString() {
        if (highlightBlocks.get()) {
            return String.format("C:%d B:%d", flaggedChunks.size(), suspiciousBlocks.size());
        }
        return String.valueOf(flaggedChunks.size());
    }

    private static class ChunkAnalysis {
        int deepslateCount = 0;
        int cobbledDeepslateCount = 0;
        int rotatedDeepslateCount = 0;
        int endStoneCount = 0;
        int trialChamberCount = 0;

        int cobbleLines = 0;
        int deepslateBand = 0;
        int obsidian = 0;
        int pistons = 0;
        int stairs = 0;
        int airPocket = 0;
        int needles = 0;
        int hiddenOre = 0;
        int vertVeins = 0;
        int valuableOre = 0;
        int storage = 0;
        int mobCluster = 0;
        int droppedItems = 0;
        int polarBears = 0;

        int amethystGeode = 0;
    }

    private static class SuspiciousBlock {
        final SuspiciousBlockType type;
        final long detectedTime;

        SuspiciousBlock(SuspiciousBlockType type, long detectedTime) {
            this.type = type;
            this.detectedTime = detectedTime;
        }
    }

    private enum SuspiciousBlockType {
        DEEPSLATE,
        COBBLED_DEEPSLATE,
        ROTATED_DEEPSLATE,
        END_STONE
    }
}
