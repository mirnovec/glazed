package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.ChunkSnapshotStore;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlayerBypass extends Module {
    private static final int SCAN_RADIUS = 8;
    private static final int FLAG_RADIUS = 9;

    private static final Set<Block> IGNORED_OLD_STATE = Set.of(
        Blocks.AMETHYST_BLOCK, Blocks.STONE, Blocks.FROSTED_ICE
    );

    private static final Set<Block> IGNORED_NEW_STATE = Set.of(
        Blocks.FIRE, Blocks.FROSTED_ICE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.OAK_LEAVES, Blocks.ACACIA_LEAVES,
        Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.PALE_OAK_LEAVES,
        Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
        Blocks.MOSSY_COBBLESTONE, Blocks.RAW_COPPER_BLOCK, Blocks.STONE, Blocks.OBSERVER,
        Blocks.REPEATER, Blocks.COMPARATOR, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_LAMP,
        Blocks.REDSTONE_WIRE, Blocks.DROPPER
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notif-debug")
        .description("Print the chunk coordinates of anything it flags.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-debug")
        .description("Print the old and new block state of every flagged change, and draw the raw blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearOnToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-toggle")
        .description("Drop every flag when the module is toggled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderFlags = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the flagged chunks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderCentre = sgRender.add(new BoolSetting.Builder()
        .name("render-flagged-chunk")
        .description("Draw the chunk the anomaly was actually in, in its own colour.")
        .defaultValue(true)
        .visible(renderFlags::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour for the flagged area.")
        .defaultValue(new SettingColor(255, 40, 40, 40))
        .visible(renderFlags::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour for the flagged area.")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .visible(renderFlags::get)
        .build()
    );

    private final Setting<SettingColor> centreSideColor = sgRender.add(new ColorSetting.Builder()
        .name("centre-side-color")
        .description("Fill colour for the chunk the anomaly was in.")
        .defaultValue(new SettingColor(0, 255, 255, 40))
        .visible(renderFlags::get)
        .build()
    );

    private final Setting<SettingColor> centreLineColor = sgRender.add(new ColorSetting.Builder()
        .name("centre-line-color")
        .description("Outline colour for the chunk the anomaly was in.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(renderFlags::get)
        .build()
    );

    private final Setting<Double> renderHeight = sgRender.add(new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
        .name("render-height")
        .description("Y level the boxes are drawn at.")
        .defaultValue(63.0)
        .range(-64.0, 320.0)
        .sliderRange(-64.0, 320.0)
        .visible(renderFlags::get)
        .build()
    );

    private final ChunkSnapshotStore snapshots = new ChunkSnapshotStore();
    private final Set<ChunkPos> flaggedChunks = new HashSet<>();
    private final Set<ChunkPos> centreChunks = new HashSet<>();
    private final Set<ChunkPos> freshChunks = new HashSet<>();
    private final List<BlockPos> flaggedBlocks = new ArrayList<>();

    private List<ChunkPos> previousChunks = new ArrayList<>();
    private ChunkPos lastPos;

    public PlayerBypass() {
        super(GlazedAddon.CATEGORY, "player-bypass", "Flags chunks where blocks changed in ways only a player explains.");
    }

    @Override
    public void onActivate() {
        snapshots.clear();
        freshChunks.clear();
        previousChunks.clear();
        flaggedBlocks.clear();
        lastPos = null;

        if (clearOnToggle.get()) {
            flaggedChunks.clear();
            centreChunks.clear();
        }
    }

    @Override
    public void onDeactivate() {
        snapshots.clear();
        freshChunks.clear();
        previousChunks.clear();
        flaggedBlocks.clear();

        if (clearOnToggle.get()) {
            flaggedChunks.clear();
            centreChunks.clear();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) return;

        ChunkPos current = mc.player.chunkPosition();

        if (lastPos != null && !lastPos.equals(current)) {
            List<ChunkPos> chunks = chunksInRange();

            for (ChunkPos pos : chunks) {
                if (!previousChunks.contains(pos)) storeSnapshot(pos);
            }

            freshChunks.retainAll(new HashSet<>(chunks));
            previousChunks = chunks;
        }

        lastPos = current;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.level == null || mc.player == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) return;

        ChunkPos pos = event.chunk().getPos();
        if (!chunksInRange().contains(pos)) return;

        int[][] sections = extractSections(event.chunk());

        for (ChunkSnapshotStore.BlockChange change : snapshots.compare(pos.x, pos.z, sections)) {
            Block added = Block.stateById(change.newState()).getBlock();
            Block removed = Block.stateById(change.oldState()).getBlock();

            if (IGNORED_NEW_STATE.contains(added) || IGNORED_OLD_STATE.contains(removed)) continue;

            if (debug.get()) info("new state: " + added + " old state: " + removed);
            else if (notify.get()) info("Chunk at: " + pos.x + " " + pos.z);

            flag(pos, new BlockPos(change.worldX(), change.worldY(), change.worldZ()));
            break;
        }

        freshChunks.add(pos);
        snapshots.remove(pos.x, pos.z);
    }

    private void flag(ChunkPos pos, BlockPos block) {
        centreChunks.add(pos);
        flaggedBlocks.add(block);

        List<ChunkPos> square = new ArrayList<>();

        for (int dx = -FLAG_RADIUS; dx <= FLAG_RADIUS; dx++) {
            for (int dz = -FLAG_RADIUS; dz <= FLAG_RADIUS; dz++) {
                square.add(new ChunkPos(pos.x + dx, pos.z + dz));
            }
        }

        if (flaggedChunks.isEmpty()) {
            flaggedChunks.addAll(square);
            return;
        }

        List<ChunkPos> overlap = new ArrayList<>();

        for (ChunkPos candidate : square) {
            if (flaggedChunks.contains(candidate)) overlap.add(candidate);
        }

        flaggedChunks.clear();
        flaggedChunks.addAll(overlap.isEmpty() ? square : overlap);
    }

    private void storeSnapshot(ChunkPos pos) {
        ChunkAccess chunk = mc.level.getChunk(pos.x, pos.z);
        if (chunk == null) return;

        snapshots.store(pos.x, pos.z, extractSections(chunk));
    }

    private List<ChunkPos> chunksInRange() {
        List<ChunkPos> chunks = new ArrayList<>();
        ChunkPos centre = mc.player.chunkPosition();

        for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
            int cut = Math.max(0, 3 - (SCAN_RADIUS - Math.abs(dz)));
            int maxX = SCAN_RADIUS - cut;

            for (int dx = -maxX; dx <= maxX; dx++) {
                chunks.add(new ChunkPos(centre.x + dx, centre.z + dz));
            }
        }

        return chunks;
    }

    private static int[][] extractSections(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int[][] result = new int[sections.length][];

        for (int s = ChunkSnapshotStore.MIN_SECTION; s <= ChunkSnapshotStore.MAX_SECTION && s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;

            int[] states = new int[4096];
            int i = 0;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        states[i++] = Block.getId(state);
                    }
                }
            }

            result[s] = states;
        }

        return result;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderFlags.get()) return;

        double y = renderHeight.get();

        for (ChunkPos pos : flaggedChunks) {
            if (renderCentre.get() && centreChunks.contains(pos)) continue;

            drawChunk(event, pos, y, sideColor.get(), lineColor.get());
        }

        if (renderCentre.get()) {
            for (ChunkPos pos : centreChunks) drawChunk(event, pos, y, centreSideColor.get(), centreLineColor.get());
        }

        if (!debug.get()) return;

        for (BlockPos pos : flaggedBlocks) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }
    }

    private void drawChunk(Render3DEvent event, ChunkPos pos, double y, Color side, Color line) {
        int x = pos.x * 16;
        int z = pos.z * 16;

        event.renderer.box(x, y, z, x + 16, y, z + 16, side, line, ShapeMode.Both, 0);
    }

    @Override
    public String getInfoString() {
        return String.valueOf(centreChunks.size());
    }
}
