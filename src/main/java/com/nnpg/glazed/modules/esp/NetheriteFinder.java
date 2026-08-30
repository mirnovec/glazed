package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.NetherOre;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws ancient debris from the world seed instead of from block data, so anti-xray has nothing to
 * hide. Ore placement is deterministic: seed plus chunk coordinate decides every vein.
 *
 * Terrain is never simulated. Candidate vein blocks are computed from the seed and then checked
 * against the real client world, which is both cheaper and more accurate than reimplementing the
 * nether chunk generator.
 *
 * Quartz and gold are here because they are the fastest way to prove a seed is right: they
 * generate exposed on cave walls, so if the boxes sit on ores you can already see, the seed is
 * confirmed without mining anything.
 */
public class NetheriteFinder extends Module {

    public enum AirCheck {
        On,
        Recheck,
        Off
    }

    private record Vein(NetherOre.Type type, List<BlockPos> blocks, List<BlockPos> discarded, double x, double y, double z) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOres = settings.createGroup("Ores");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<String> seedSetting = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("The world seed. Without it this module cannot do anything.")
        .defaultValue("")
        .onChanged(v -> reload())
        .build()
    );

    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Chunk radius around you to simulate.")
        .defaultValue(5)
        .min(1)
        .max(16)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<AirCheck> airCheck = sgGeneral.add(new EnumSetting.Builder<AirCheck>()
        .name("air-check")
        .description("Drops veins that the real world says are in open air. Off shows every candidate, including ones that never generated.")
        .defaultValue(AirCheck.Recheck)
        .build()
    );

    private final Setting<Boolean> debris = sgOres.add(new BoolSetting.Builder()
        .name("ancient-debris")
        .description("What you are here for.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> quartz = sgOres.add(new BoolSetting.Builder()
        .name("quartz")
        .description("Turn on to verify the seed. Quartz sits exposed on cave walls, so the boxes should land on ores you can already see.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> gold = sgOres.add(new BoolSetting.Builder()
        .name("gold")
        .description("Second verification ore, same idea as quartz.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> debrisColor = sgRender.add(new ColorSetting.Builder()
        .name("debris-color")
        .description("Ancient debris colour.")
        .defaultValue(new SettingColor(209, 27, 245))
        .build()
    );

    private final Setting<SettingColor> quartzColor = sgRender.add(new ColorSetting.Builder()
        .name("quartz-color")
        .description("Quartz colour.")
        .defaultValue(new SettingColor(225, 225, 225))
        .build()
    );

    private final Setting<SettingColor> goldColor = sgRender.add(new ColorSetting.Builder()
        .name("gold-color")
        .description("Nether gold colour.")
        .defaultValue(new SettingColor(247, 229, 30))
        .build()
    );

    private final Setting<Integer> sideAlpha = sgRender.add(new IntSetting.Builder()
        .name("side-alpha")
        .description("Fill opacity of the boxes.")
        .defaultValue(40)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Integer> minY = sgRender.add(new IntSetting.Builder()
        .name("min-y")
        .description("Hide predictions below this height. Set to 90 to see only the untouched rock near the roof, which nobody strip mines.")
        .defaultValue(0)
        .min(0)
        .max(127)
        .sliderRange(0, 127)
        .build()
    );

    private final Setting<Integer> maxY = sgRender.add(new IntSetting.Builder()
        .name("max-y")
        .description("Hide predictions above this height.")
        .defaultValue(127)
        .min(0)
        .max(127)
        .sliderRange(0, 127)
        .build()
    );

    private final Setting<Integer> maxDistance = sgRender.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Stop drawing past this many blocks.")
        .defaultValue(160)
        .min(16)
        .max(512)
        .sliderRange(16, 512)
        .build()
    );

    private final Setting<Boolean> requireLeak = sgGeneral.add(new BoolSetting.Builder()
        .name("require-leak")
        .description("Hide predicted debris whose chunk section no longer lists ancient debris in its palette. Those veins were mined out years ago. Needs the section loaded, so it only filters what you can see.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logRealDebris = sgGeneral.add(new BoolSetting.Builder()
        .name("log-real-debris")
        .description("Writes down every real ancient debris block the server actually shows you. Mine normally with this on; the coordinates are what pin down the correct feature index.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDiscarded = sgRender.add(new BoolSetting.Builder()
        .name("show-discarded")
        .description("Also draw candidates the air-exposure rule threw away, in a separate colour. Turn on to test whether that rule is the thing getting it wrong.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> discardedColor = sgRender.add(new ColorSetting.Builder()
        .name("discarded-color")
        .description("Colour for discarded candidates.")
        .defaultValue(new SettingColor(120, 120, 120))
        .visible(showDiscarded::get)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your camera to each vein.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> nearestOnly = sgRender.add(new BoolSetting.Builder()
        .name("nearest-only")
        .description("Trace only the closest vein. A loaded area holds a lot of veins and the screen turns into spaghetti otherwise.")
        .defaultValue(true)
        .visible(tracers::get)
        .build()
    );

    private final Map<Long, List<Vein>> results = new ConcurrentHashMap<>();

    private volatile Map<ResourceKey<Biome>, List<NetherOre>> oreConfig;
    private volatile boolean loadingOres;
    private volatile boolean pendingRescan;
    private boolean forceRaw;

    private final List<BlockPos> discardBuffer = new ArrayList<>();
    private static final Set<Long> loggedDebris = new HashSet<>();
    private List<NetherOre> allOres;
    private long seed;
    private final Map<Long, Boolean> leakCache = new HashMap<>();
    private int tickCounter;

    public NetheriteFinder() {
        super(GlazedAddon.esp, "netherite-finder", "Predicts ancient debris from the world seed. Works through anti-xray because it never asks the server anything.");
    }

    @Override
    public void onActivate() {
        if (mc.level == null || mc.player == null) {
            error("Join a server first.");
            toggle();
            return;
        }

        if (PlayerUtils.getDimension() != Dimension.Nether) {
            error("Nether only.");
            toggle();
            return;
        }

        Long parsed = parseSeed();
        if (parsed == null) {
            error("Set a valid world seed in the module settings first.");
            toggle();
            return;
        }
        seed = parsed;

        results.clear();

        if (oreConfig != null) {
            pendingRescan = true;
            return;
        }

        if (loadingOres) return;
        loadingOres = true;

        // building the vanilla worldgen registry takes a moment, so keep it off the render thread
        int minY = mc.level.getMinY();
        int logicalHeight = mc.level.dimensionType().logicalHeight();

        info("Loading vanilla ore data...");

        Thread thread = new Thread(() -> {
            try {
                Map<ResourceKey<Biome>, List<NetherOre>> config = NetherOre.get(minY, logicalHeight);
                oreConfig = config;
                pendingRescan = true;
            } catch (Throwable t) {
                error("Could not load ore data: %s", t);
            } finally {
                loadingOres = false;
            }
        }, "glazed-netherite-finder");

        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onDeactivate() {
        results.clear();
    }

    private void reload() {
        Long parsed = parseSeed();
        if (parsed != null) seed = parsed;

        results.clear();
        pendingRescan = true;
    }

    private Long parseSeed() {
        String raw = seedSetting.get().trim();
        if (raw.isEmpty()) return null;

        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // a non numeric seed is hashed the same way the world creation screen does it
            return (long) raw.hashCode();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        leakCache.clear();

        if (mc.level == null || oreConfig == null) return;

        if (allOres == null) {
            Set<NetherOre> distinct = new HashSet<>();
            for (List<NetherOre> ores : oreConfig.values()) distinct.addAll(ores);
            allOres = new ArrayList<>(distinct);

            info("Ore data ready, %d placements.", allOres.size());
        }

        if (pendingRescan) {
            pendingRescan = false;
            results.clear();
            for (ChunkAccess chunk : Utils.chunks(false)) compute(chunk);
            return;
        }

        // chunks skipped because their neighbours had not arrived yet get another go here.
        // compute() returns immediately for anything already cached, so this stays cheap.
        if (++tickCounter < 40) return;
        tickCounter = 0;

        if (logRealDebris.get()) scanForRealDebris();

        for (ChunkAccess chunk : Utils.chunks(false)) compute(chunk);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        compute(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (airCheck.get() != AirCheck.Recheck || !event.newState.isAir()) return;

        // something got mined out, so any vein that block belonged to is gone
        List<Vein> veins = results.get(ChunkPos.pack(event.pos));
        if (veins == null) return;

        for (Vein vein : veins) vein.blocks().remove(event.pos);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null || oreConfig == null) return;

        double maxSq = (double) maxDistance.get() * maxDistance.get();
        ChunkPos playerChunk = mc.player.chunkPosition();
        int range = chunkRange.get();

        Vein nearest = null;
        double nearestSq = Double.MAX_VALUE;

        for (int cx = playerChunk.x() - range; cx <= playerChunk.x() + range; cx++) {
            for (int cz = playerChunk.z() - range; cz <= playerChunk.z() + range; cz++) {
                List<Vein> veins = results.get(ChunkPos.pack(cx, cz));
                if (veins == null) continue;

                for (Vein vein : veins) {
                    if (!enabled(vein.type())) continue;

                    double distSq = mc.player.distanceToSqr(vein.x(), vein.y(), vein.z());
                    if (distSq > maxSq) continue;

                    if (vein.type() == NetherOre.Type.DEBRIS && requireLeak.get() && !sectionLeaks(vein)) continue;

                    Color line = colorOf(vein.type());
                    Color side = new Color(line.r, line.g, line.b, sideAlpha.get());

                    for (BlockPos pos : vein.blocks()) {
                        if (pos.getY() < minY.get() || pos.getY() > maxY.get()) continue;

                        event.renderer.box(
                            pos.getX(), pos.getY(), pos.getZ(),
                            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                            side, line, shapeMode.get(), 0);
                    }

                    if (showDiscarded.get()) {
                        Color dLine = discardedColor.get();
                        Color dSide = new Color(dLine.r, dLine.g, dLine.b, sideAlpha.get());

                        for (BlockPos pos : vein.discarded()) {
                            if (pos.getY() < minY.get() || pos.getY() > maxY.get()) continue;

                            event.renderer.box(
                                pos.getX(), pos.getY(), pos.getZ(),
                                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                                dSide, dLine, shapeMode.get(), 0);
                        }
                    }

                    if (vein.blocks().isEmpty() && !showDiscarded.get()) continue;

                    if (!tracers.get()) continue;

                    if (nearestOnly.get()) {
                        if (distSq < nearestSq) {
                            nearestSq = distSq;
                            nearest = vein;
                        }
                    } else {
                        tracer(event, vein);
                    }
                }
            }
        }

        if (tracers.get() && nearestOnly.get() && nearest != null) tracer(event, nearest);
    }

    private boolean sectionLeaks(Vein vein) {
        int x = Mth.floor(vein.x());
        int y = Mth.floor(vein.y());
        int z = Mth.floor(vein.z());

        long key = ((long) (x >> 4 & 0x3FFFFFF) << 38) | ((long) (z >> 4 & 0x3FFFFFF) << 12) | ((y >> 4) + 2048 & 0xFFF);
        Boolean cached = leakCache.get(key);
        if (cached != null) return cached;

        boolean leaks = true;
        ChunkAccess chunk = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);

        if (chunk != null) {
            int index = chunk.getSectionIndex(y);

            if (index >= 0 && index < chunk.getSections().length) {
                LevelChunkSection section = chunk.getSections()[index];
                leaks = section != null && !section.hasOnlyAir() && section.maybeHas(state -> state.is(Blocks.ANCIENT_DEBRIS));
            }
        }

        leakCache.put(key, leaks);
        return leaks;
    }

    private void tracer(Render3DEvent event, Vein vein) {
        event.renderer.line(
            RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
            vein.x(), vein.y(), vein.z(),
            colorOf(vein.type()));
    }

    private boolean enabled(NetherOre.Type type) {
        return switch (type) {
            case DEBRIS -> debris.get();
            case QUARTZ -> quartz.get();
            case GOLD -> gold.get();
            default -> false;
        };
    }

    private Color colorOf(NetherOre.Type type) {
        return switch (type) {
            case DEBRIS -> debrisColor.get();
            case QUARTZ -> quartzColor.get();
            case GOLD -> goldColor.get();
            default -> discardedColor.get();
        };
    }

    // ====================================
    // Simulation
    // ====================================

    private void compute(ChunkAccess chunk) {
        if (mc.level == null || chunk == null || oreConfig == null || allOres == null) return;
        if (PlayerUtils.getDimension() != Dimension.Nether) return;

        long key = chunk.getPos().pack();
        if (results.containsKey(key)) return;

        // a vein on a chunk edge reads its neighbour's blocks; if that chunk has not arrived
        // yet every lookup answers air and the whole vein is wrongly discarded. Leaving it
        // uncached means the sweep above retries once the neighbours land.
        if (!neighboursLoaded(chunk.getPos())) return;

        int chunkX = chunk.getPos().getMinBlockX();
        int chunkZ = chunk.getPos().getMinBlockZ();

        WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
        long populationSeed = random.setDecorationSeed(seed, chunkX, chunkZ);

        List<Vein> veins = new ArrayList<>();

        for (NetherOre ore : allOres) {
            if (ore.heightProvider == null) continue;

            // every feature in the dimension runs for every chunk, the biome filter is what rejects it
            random.setFeatureSeed(populationSeed, ore.index, ore.step);

            int repeat = ore.count.sample(random);

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1 && random.nextFloat() >= 1.0f / ore.rarity) continue;

                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.heightProvider.sample(random, ore.heightContext);

                BlockPos origin = new BlockPos(x, y, z);

                if (!biomeHas(origin, ore)) continue;

                discardBuffer.clear();

                List<BlockPos> blocks = ore.scattered
                    ? generateScattered(random, origin, ore)
                    : generateNormal(random, origin, ore);

                List<BlockPos> discarded = new ArrayList<>(discardBuffer);

                if (blocks.isEmpty() && discarded.isEmpty()) continue;

                List<BlockPos> centreOf = blocks.isEmpty() ? discarded : blocks;
                double cx = 0, cy = 0, cz = 0;
                for (BlockPos pos : centreOf) {
                    cx += pos.getX() + 0.5;
                    cy += pos.getY() + 0.5;
                    cz += pos.getZ() + 0.5;
                }

                veins.add(new Vein(ore.type, blocks, discarded,
                    cx / centreOf.size(), cy / centreOf.size(), cz / centreOf.size()));
            }
        }

        results.put(key, veins);
    }

    /**
     * Anti-xray hides buried ore but reports anything you have exposed, so every block you blast
     * open that turns out to be debris gets recorded here. A handful of confirmed positions is
     * enough to solve for the feature index the server is really using.
     */
    private void scanForRealDebris() {
        if (mc.player == null || mc.level == null) return;

        ChunkPos centre = mc.player.chunkPosition();
        StringBuilder found = new StringBuilder();

        for (int cx = centre.x() - 1; cx <= centre.x() + 1; cx++) {
            for (int cz = centre.z() - 1; cz <= centre.z() + 1; cz++) {
                if (mc.level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        for (int y = 8; y <= 119; y++) {
                            pos.set((cx << 4) + dx, y, (cz << 4) + dz);
                            if (!mc.level.getBlockState(pos).is(Blocks.ANCIENT_DEBRIS)) continue;

                            if (!loggedDebris.add(pos.asLong())) continue;

                            found.append(pos.getX()).append(" ").append(pos.getY()).append(" ")
                                 .append(pos.getZ()).append("\n");
                        }
                    }
                }
            }
        }

        if (found.length() == 0) return;

        Path file = mc.gameDirectory.toPath().resolve("glazed").resolve("found_debris.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, found.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            info("Logged real ancient debris (%d total).", loggedDebris.size());
        } catch (IOException ignored) {
            // not worth interrupting mining over
        }
    }

    private boolean neighboursLoaded(ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (mc.level.getChunk(pos.x() + dx, pos.z() + dz, ChunkStatus.FULL, false) == null) return false;
            }
        }

        return true;
    }


    // ====================================
    // Diagnostic (.debris)
    // ====================================

    /**
     * Re-runs the debris simulation over the loaded chunks and records what the server actually
     * reports at every candidate position, instead of silently filtering. Written to a file so the
     * raw data can be read back rather than guessed at.
     */

    /**
     * Ground truth without mining anything. The server hides buried ore but reports exposed ore
     * honestly, so if the seed and feature indices are right the predicted quartz and gold
     * positions must line up with the ore actually visible on cave walls. Compares the hit rate
     * against what pure chance would give, which is what makes the number mean something.
     */
    public String verifyAgainstVisibleOre() {
        if (mc.level == null || mc.player == null) return "Join a server first.";
        if (oreConfig == null || allOres == null) return "Enable the module first.";

        Long parsed = parseSeed();
        if (parsed == null) return "No valid seed set.";
        seed = parsed;

        int radius = 4;
        int minY = 10, maxY = 117;

        Set<Long> predicted = new HashSet<>();
        ChunkPos centre = mc.player.chunkPosition();

        forceRaw = true;
        try {
            for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
                for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                    ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;

                    int minX = cx << 4, minZ = cz << 4;

                    for (NetherOre ore : allOres) {
                        if (ore.type == NetherOre.Type.DEBRIS || ore.heightProvider == null) continue;

                        WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
                        long populationSeed = random.setDecorationSeed(seed, minX, minZ);
                        random.setFeatureSeed(populationSeed, ore.index, ore.step);

                        int repeat = ore.count.sample(random);

                        for (int i = 0; i < repeat; i++) {
                            if (ore.rarity != 1 && random.nextFloat() >= 1.0f / ore.rarity) continue;

                            int x = random.nextInt(16) + minX;
                            int z = random.nextInt(16) + minZ;
                            int y = ore.heightProvider.sample(random, ore.heightContext);
                            BlockPos origin = new BlockPos(x, y, z);

                            if (!biomeHas(origin, ore)) continue;

                            List<BlockPos> blocks = ore.scattered
                                ? generateScattered(random, origin, ore)
                                : generateNormal(random, origin, ore);

                            for (BlockPos b : blocks) predicted.add(b.asLong());
                        }
                    }
                }
            }
        } finally {
            forceRaw = false;
        }

        int visible = 0, hit = 0;
        long volume = 0;

        for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
            for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                if (mc.level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        for (int y = minY; y <= maxY; y++) {
                            volume++;
                            pos.set((cx << 4) + dx, y, (cz << 4) + dz);
                            BlockState state = mc.level.getBlockState(pos);

                            if (!state.is(Blocks.NETHER_QUARTZ_ORE) && !state.is(Blocks.NETHER_GOLD_ORE)) continue;

                            visible++;
                            if (predicted.contains(pos.asLong())) hit++;
                        }
                    }
                }
            }
        }

        if (visible == 0) return "No visible quartz or gold nearby, move somewhere with open caves and retry.";

        double rate = 100.0 * hit / visible;
        double chance = volume == 0 ? 0 : 100.0 * predicted.size() / volume;

        return String.format(
            "visible ore=%d  predicted=%d  matched=%d (%.1f%%)  chance baseline=%.1f%%  -> %s",
            visible, predicted.size(), hit, rate, chance,
            rate > chance * 5 ? "SEED CORRECT HERE" : "SEED WRONG HERE");
    }


    /**
     * Brute forces the feature index for every ore we can actually see, by trying each index and
     * measuring how well the predictions line up with real blocks. Magma, gravel and blackstone
     * are not treated as ores by anti-xray, so they verify even buried. If these come back at
     * their vanilla indices the server ordering is vanilla; if they are shifted, the same shift
     * is what is breaking ancient debris.
     */
    public String scanDebrisIndices() {
        if (oreConfig == null || allOres == null) return "Enable the module first.";

        Long parsed = parseSeed();
        if (parsed == null) return "No valid seed set.";
        seed = parsed;

        Set<Long> real = new HashSet<>();
        Path source = mc.gameDirectory.toPath().resolve("glazed").resolve("debris_blocks.csv");

        try {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String[] parts = line.split(",");
                if (parts.length < 4) continue;

                try {
                    real.add(BlockPos.asLong(Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            return "Could not read " + source + ": " + e.getMessage();
        }

        if (real.size() < 10) return "Only " + real.size() + " logged debris blocks, need at least 10. Run debris-leak while mining first.";

        Set<Long> chunks = new HashSet<>();
        for (long packed : real) {
            BlockPos pos = BlockPos.of(packed);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    chunks.add(ChunkPos.pack((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz));
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format("seed=%d  realBlocks=%d  chunks=%d%n", seed, real.size(), chunks.size()));
        report.append(String.format("%-14s %-8s %-9s %-9s %-9s %-9s %-10s %-12s%n", "feature", "vanilla", "vanHits", "bestIdx", "bestHits", "decoyBest", "predicted", "predYrange"));

        long decoySeed = seed ^ 0x5DEECE66DL;

        forceRaw = true;
        try {
            for (NetherOre ore : allOres) {
                if (ore.type != NetherOre.Type.DEBRIS || ore.heightProvider == null) continue;

                int bestIndex = -1, bestHits = -1, vanillaHits = 0, decoyBest = 0;
                int predicted = 0, loY = Integer.MAX_VALUE, hiY = Integer.MIN_VALUE;

                for (int idx = 0; idx <= 40; idx++) {
                    int[] stats = countHits(ore, idx, seed, chunks, real);
                    if (idx == ore.index) { vanillaHits = stats[0]; predicted = stats[1]; loY = stats[2]; hiY = stats[3]; }
                    if (stats[0] > bestHits) { bestHits = stats[0]; bestIndex = idx; }

                    decoyBest = Math.max(decoyBest, countHits(ore, idx, decoySeed, chunks, real)[0]);
                }

                report.append(String.format("debris size %-2d %-8d %-9d %-9d %-9d %-9d %-10d %d..%d%n",
                    ore.size, ore.index, vanillaHits, bestIndex, bestHits, decoyBest, predicted,
                    loY == Integer.MAX_VALUE ? 0 : loY, hiY == Integer.MIN_VALUE ? 0 : hiY));
            }
        } finally {
            forceRaw = false;
        }

        report.append("decoyBest is the same search on a wrong seed. A real match must beat it by a mile.\n");
        report.append(selfTest());

        Path file = mc.gameDirectory.toPath().resolve("glazed").resolve("debris_index_scan.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }

        return report.toString().replace("\n", " | ");
    }

    private String selfTest() {
        if (mc.level == null || mc.player == null) return "\nself-test: join a nether world and stand in loaded chunks to validate the scan.\n";

        StringBuilder out = new StringBuilder();
        out.append("\nself-test on visible ore through the exact same code path (this must find a strong index, or the debris result above means nothing)\n");
        out.append(String.format("%-14s %-8s %-9s %-9s %-9s %-10s%n", "feature", "vanilla", "vanHits", "bestIdx", "bestHits", "realBlocks"));

        ChunkPos centre = mc.player.chunkPosition();
        int radius = 4;
        boolean any = false;

        forceRaw = true;
        try {
            for (NetherOre ore : allOres) {
                if (ore.type != NetherOre.Type.QUARTZ && ore.type != NetherOre.Type.GOLD) continue;
                if (ore.oreState == null || ore.heightProvider == null) continue;

                Block want = ore.oreState.getBlock();
                Set<Long> real = new HashSet<>();
                Set<Long> chunks = new HashSet<>();
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
                    for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                        if (mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;

                        for (int dx = 0; dx < 16; dx++)
                            for (int dz = 0; dz < 16; dz++)
                                for (int y = 8; y <= 119; y++) {
                                    pos.set((cx << 4) + dx, y, (cz << 4) + dz);
                                    if (mc.level.getBlockState(pos).is(want)) real.add(pos.asLong());
                                }

                        for (int ox = -1; ox <= 1; ox++)
                            for (int oz = -1; oz <= 1; oz++)
                                chunks.add(ChunkPos.pack(cx + ox, cz + oz));
                    }
                }

                if (real.size() < 20) continue;
                any = true;

                int bestIndex = -1, bestHits = -1, vanillaHits = 0;

                for (int idx = 0; idx <= 40; idx++) {
                    int[] stats = countHits(ore, idx, seed, chunks, real);
                    if (idx == ore.index) vanillaHits = stats[0];
                    if (stats[0] > bestHits) { bestHits = stats[0]; bestIndex = idx; }
                }

                out.append(String.format("%-14s %-8d %-9d %-9d %-9d %-10d%n",
                    ore.type + " s" + ore.size, ore.index, vanillaHits, bestIndex, bestHits, real.size()));
            }
        } finally {
            forceRaw = false;
        }

        if (!any) out.append("no visible quartz or gold nearby, fly to open nether and run again\n");

        return out.toString();
    }

    private int[] countHits(NetherOre ore, int idx, long useSeed, Set<Long> chunks, Set<Long> real) {
        int hits = 0, predicted = 0, loY = Integer.MAX_VALUE, hiY = Integer.MIN_VALUE;

        for (long packed : chunks) {
            int minX = ChunkPos.getX(packed) << 4;
            int minZ = ChunkPos.getZ(packed) << 4;

            WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
            long populationSeed = random.setDecorationSeed(useSeed, minX, minZ);
            random.setFeatureSeed(populationSeed, idx, ore.step);

            int repeat = ore.count.sample(random);

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1 && random.nextFloat() >= 1.0f / ore.rarity) continue;

                int x = random.nextInt(16) + minX;
                int z = random.nextInt(16) + minZ;
                int y = ore.heightProvider.sample(random, ore.heightContext);

                List<BlockPos> blocks = ore.scattered
                    ? generateScattered(random, new BlockPos(x, y, z), ore)
                    : generateNormal(random, new BlockPos(x, y, z), ore);

                for (BlockPos b : blocks) {
                    predicted++;
                    if (b.getY() < loY) loY = b.getY();
                    if (b.getY() > hiY) hiY = b.getY();
                    if (real.contains(b.asLong())) hits++;
                }
            }
        }

        return new int[]{hits, predicted, loY, hiY};
    }

    public String leakScan(int radius) {
        if (mc.level == null || mc.player == null) return "Join a server first.";
        if (oreConfig == null || allOres == null) return "Enable the module first.";

        Long parsed = parseSeed();
        if (parsed == null) return "No valid seed set.";
        seed = parsed;

        Set<Long> leaking = new HashSet<>();
        int sectionsSeen = 0;
        ChunkPos centre = mc.player.chunkPosition();

        for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
            for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                ChunkAccess chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                LevelChunkSection[] sections = chunk.getSections();

                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir()) continue;

                    int sectionY = chunk.getSectionYFromSectionIndex(i);
                    if ((sectionY << 4) + 15 < 8 || (sectionY << 4) > 119) continue;

                    sectionsSeen++;
                    if (section.maybeHas(state -> state.is(Blocks.ANCIENT_DEBRIS))) leaking.add(sectionKey(cx, cz, sectionY));
                }
            }
        }

        if (sectionsSeen == 0) return "No loaded sections in range.";
        if (leaking.isEmpty()) return "No leaking sections nearby, fly somewhere with untouched nether first.";

        double baseline = (double) leaking.size() / sectionsSeen;
        StringBuilder report = new StringBuilder();
        report.append(String.format("leaking %d of %d sections (baseline %.1f%%), seed %d, radius %d%n",
            leaking.size(), sectionsSeen, baseline * 100.0, seed, radius));

        forceRaw = true;
        try {
            for (NetherOre ore : allOres) {
                if (ore.type != NetherOre.Type.DEBRIS || ore.heightProvider == null) continue;

                int bestIndex = -1;
                double bestRate = -1.0;
                int bestHits = 0, bestTotal = 0;
                int vanillaHits = 0, vanillaTotal = 0;

                for (int idx = 0; idx <= 40; idx++) {
                    int hits = 0, total = 0;

                    for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
                        for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                            if (mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;

                            int minX = cx << 4, minZ = cz << 4;
                            WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
                            long populationSeed = random.setDecorationSeed(seed, minX, minZ);
                            random.setFeatureSeed(populationSeed, idx, ore.step);

                            int repeat = ore.count.sample(random);
                            for (int i = 0; i < repeat; i++) {
                                if (ore.rarity != 1 && random.nextFloat() >= 1.0f / ore.rarity) continue;

                                int x = random.nextInt(16) + minX;
                                int z = random.nextInt(16) + minZ;
                                int y = ore.heightProvider.sample(random, ore.heightContext);
                                BlockPos origin = new BlockPos(x, y, z);

                                List<BlockPos> blocks = ore.scattered
                                    ? generateScattered(random, origin, ore)
                                    : generateNormal(random, origin, ore);

                                Set<Long> touched = new HashSet<>();
                                for (BlockPos b : blocks) touched.add(sectionKey(b.getX() >> 4, b.getZ() >> 4, b.getY() >> 4));

                                for (long key : touched) {
                                    total++;
                                    if (leaking.contains(key)) hits++;
                                }
                            }
                        }
                    }

                    if (total == 0) continue;
                    double rate = (double) hits / total;

                    if (idx == ore.index) { vanillaHits = hits; vanillaTotal = total; }
                    if (rate > bestRate) { bestRate = rate; bestIndex = idx; bestHits = hits; bestTotal = total; }
                }

                double vanillaRate = vanillaTotal == 0 ? 0.0 : (double) vanillaHits / vanillaTotal;

                report.append(String.format("debris size %d: vanilla idx %d hit %d/%d = %.1f%% (%.1fx baseline) | best idx %d hit %d/%d = %.1f%% (%.1fx)%n",
                    ore.size, ore.index, vanillaHits, vanillaTotal, vanillaRate * 100.0, vanillaRate / baseline,
                    bestIndex, bestHits, bestTotal, bestRate * 100.0, bestRate / baseline));
            }
        } finally {
            forceRaw = false;
        }

        report.append("A correct index sits far above 1.0x. Everything near 1.0x means the seed says nothing about debris here.\n");

        Path file = mc.gameDirectory.toPath().resolve("glazed").resolve("leak_scan.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }

        return report.toString().replace("\n", " | ");
    }

    private static long sectionKey(int cx, int cz, int sectionY) {
        return ((long) (cx & 0x3FFFFFF) << 38) | ((long) (cz & 0x3FFFFFF) << 12) | (sectionY + 2048 & 0xFFF);
    }

    public String scanIndices() {
        if (mc.level == null || mc.player == null) return "Join a server first.";
        if (oreConfig == null || allOres == null) return "Enable the module first.";

        Long parsed = parseSeed();
        if (parsed == null) return "No valid seed set.";
        seed = parsed;

        int radius = 3;
        StringBuilder report = new StringBuilder();
        ChunkPos centre = mc.player.chunkPosition();

        for (NetherOre ore : allOres) {
            if (ore.type == NetherOre.Type.DEBRIS || ore.oreState == null || ore.heightProvider == null) continue;

            Block want = ore.oreState.getBlock();

            // where does this block really occur nearby?
            Set<Long> actual = new HashSet<>();
            for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
                for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                    if (mc.level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;

                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    for (int dx = 0; dx < 16; dx++)
                        for (int dz = 0; dz < 16; dz++)
                            for (int y = 8; y <= 119; y++) {
                                pos.set((cx << 4) + dx, y, (cz << 4) + dz);
                                if (mc.level.getBlockState(pos).is(want)) actual.add(pos.asLong());
                            }
                }
            }

            if (actual.size() < 20) continue;

            int bestIndex = -1, bestHits = -1;
            int vanillaHits = 0;

            forceRaw = true;
            try {
                for (int idx = 0; idx <= 40; idx++) {
                    int hits = 0;

                    for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
                        for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
                            if (mc.level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;

                            int minX = cx << 4, minZ = cz << 4;
                            WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
                            long populationSeed = random.setDecorationSeed(seed, minX, minZ);
                            random.setFeatureSeed(populationSeed, idx, ore.step);

                            int repeat = ore.count.sample(random);
                            for (int i = 0; i < repeat; i++) {
                                if (ore.rarity != 1 && random.nextFloat() >= 1.0f / ore.rarity) continue;

                                int x = random.nextInt(16) + minX;
                                int z = random.nextInt(16) + minZ;
                                int y = ore.heightProvider.sample(random, ore.heightContext);
                                BlockPos origin = new BlockPos(x, y, z);

                                List<BlockPos> blocks = ore.scattered
                                    ? generateScattered(random, origin, ore)
                                    : generateNormal(random, origin, ore);

                                for (BlockPos b : blocks) if (actual.contains(b.asLong())) hits++;
                            }
                        }
                    }

                    if (idx == ore.index) vanillaHits = hits;
                    if (hits > bestHits) { bestHits = hits; bestIndex = idx; }
                }
            } finally {
                forceRaw = false;
            }

            report.append(String.format("%s: vanillaIndex=%d hits=%d | bestIndex=%d hits=%d | realBlocks=%d%s%n",
                ore.type, ore.index, vanillaHits, bestIndex, bestHits, actual.size(),
                bestIndex == ore.index ? "  OK" : "  <-- SHIFTED"));
        }

        if (report.length() == 0) return "Not enough reference blocks nearby, move to somewhere with more terrain.";

        Path file = mc.gameDirectory.toPath().resolve("glazed").resolve("index_scan.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }

        return report.toString().replace("\n", " | ");
    }

    public String diagnose() {
        if (mc.level == null || mc.player == null) return "Join a server first.";
        if (oreConfig == null || allOres == null) return "Ore data is not loaded yet, enable the module and wait a second.";

        Long parsed = parseSeed();
        if (parsed == null) return "No valid seed set.";
        seed = parsed;

        int candidates = 0, isDebris = 0, targetOk = 0, airOk = 0, shown = 0;
        Map<String, Integer> blockHist = new LinkedHashMap<>();
        Map<String, Integer> biomeHist = new LinkedHashMap<>();
        StringBuilder lines = new StringBuilder();

        ChunkPos centre = mc.player.chunkPosition();
        int range = chunkRange.get();

        for (int cx = centre.x() - range; cx <= centre.x() + range; cx++) {
            for (int cz = centre.z() - range; cz <= centre.z() + range; cz++) {
                ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || !neighboursLoaded(chunk.getPos())) continue;

                int minX = cx << 4, minZ = cz << 4;

                for (NetherOre ore : allOres) {
                    if (ore.type != NetherOre.Type.DEBRIS || ore.heightProvider == null) continue;

                    WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
                    long populationSeed = random.setDecorationSeed(seed, minX, minZ);
                    random.setFeatureSeed(populationSeed, ore.index, ore.step);

                    int repeat = ore.count.sample(random);

                    for (int i = 0; i < repeat; i++) {
                        if (ore.rarity != 1 && random.nextFloat() >= 1.0f / ore.rarity) continue;

                        int x = random.nextInt(16) + minX;
                        int z = random.nextInt(16) + minZ;
                        int y = ore.heightProvider.sample(random, ore.heightContext);
                        BlockPos origin = new BlockPos(x, y, z);

                        boolean biomeOk = biomeHas(origin, ore);
                        String biome = String.valueOf(mc.level.getBiome(origin).unwrapKey()
                            .map(k -> k.identifier().getPath()).orElse("?"));
                        biomeHist.merge(biome, 1, Integer::sum);

                        if (!biomeOk) continue;

                        // same scattered walk as the real path, recording rather than filtering
                        int n = random.nextInt(ore.size + 1);
                        for (int j = 0; j < n; j++) {
                            int spread = Math.min(j, 7);
                            int bx = randomCoord(random, spread) + x;
                            int by = randomCoord(random, spread) + y;
                            int bz = randomCoord(random, spread) + z;
                            BlockPos pos = new BlockPos(bx, by, bz);

                            candidates++;

                            BlockState state = mc.level.getBlockState(pos);
                            String name = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
                            blockHist.merge(name, 1, Integer::sum);

                            boolean debrisHere = ore.oreState != null && state.is(ore.oreState.getBlock());
                            if (debrisHere) isDebris++;

                            boolean tOk = debrisHere;
                            for (OreConfiguration.TargetBlockState t : ore.targetStates) {
                                if (t.target.test(state, random)) { tOk = true; break; }
                            }
                            if (tOk) targetOk++;

                            StringBuilder air = new StringBuilder();
                            for (Direction dir : Direction.values()) {
                                if (mc.level.getBlockState(pos.relative(dir)).isAir()) air.append(dir.getName()).append(" ");
                            }
                            boolean aOk = air.length() == 0;
                            if (aOk) airOk++;
                            if (tOk && aOk) shown++;

                            lines.append(String.format("%d %d %d | %s | block=%s | target=%s air_free=%s%s | %s%n",
                                bx, by, bz, biome, name, tOk, aOk,
                                air.length() == 0 ? "" : " airsides=" + air.toString().trim(),
                                (tOk && aOk) ? "SHOWN" : "hidden"));
                        }
                    }
                }
            }
        }

        // Is the server obfuscating? Real ore is spread evenly between exposed and buried spots.
        // If buried ore is ~0 while exposed ore is normal, the server is faking the hidden ones
        // and no client side check can ever confirm a prediction without mining it.
        int exposedOre = 0, buriedOre = 0, debrisSeen = 0;
        Map<String, Integer> oreSplit = new LinkedHashMap<>();

        for (int cx = centre.x() - 4; cx <= centre.x() + 4; cx++) {
            for (int cz = centre.z() - 4; cz <= centre.z() + 4; cz++) {
                ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        for (int y = 8; y <= 48; y++) {
                            pos.set((cx << 4) + dx, y, (cz << 4) + dz);
                            BlockState state = mc.level.getBlockState(pos);

                            boolean debris = state.is(Blocks.ANCIENT_DEBRIS);
                            boolean ore = debris || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.NETHER_GOLD_ORE);
                            if (!ore) continue;

                            if (debris) debrisSeen++;

                            boolean exposed = false;
                            for (Direction dir : Direction.values()) {
                                if (mc.level.getBlockState(pos.relative(dir)).isAir()) { exposed = true; break; }
                            }

                            String name = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
                            oreSplit.merge(name + (exposed ? "_exposed" : "_buried"), 1, Integer::sum);

                            if (exposed) exposedOre++; else buriedOre++;
                        }
                    }
                }
            }
        }

        Path file = mc.gameDirectory.toPath().resolve("glazed").resolve("debris_diagnostic.txt");
        try {
            Files.createDirectories(file.getParent());
            StringBuilder head = new StringBuilder();
            head.append("seed=").append(seed).append("  chunkRange=").append(range).append("\n");
            head.append("candidates=").append(candidates)
                .append(" readAsDebris=").append(isDebris)
                .append(" targetPass=").append(targetOk)
                .append(" airPass=").append(airOk)
                .append(" shown=").append(shown).append("\n");
            head.append("blocks at candidate positions: ").append(blockHist).append("\n");
            head.append("biomes: ").append(biomeHist).append("\n");
            head.append("ore reality check (9x9 chunks, y8-48): exposed=").append(exposedOre)
                .append(" buried=").append(buriedOre).append(" realDebrisBlocks=").append(debrisSeen).append("\n");
            head.append("ore split: ").append(oreSplit).append("\n\n");
            Files.writeString(file, head + lines.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Could not write diagnostic: " + e.getMessage();
        }

        return String.format("candidates=%d readAsDebris=%d shown=%d | ore exposed=%d buried=%d realDebris=%d -> %s",
            candidates, isDebris, shown, exposedOre, buriedOre, debrisSeen, file);
    }

    private boolean biomeHas(BlockPos pos, NetherOre ore) {
        ResourceKey<Biome> biome = mc.level.getBiome(pos).unwrapKey().orElse(null);
        if (biome == null) return false;

        List<NetherOre> ores = oreConfig.get(biome);
        return ores != null && ores.contains(ore);
    }

    /**
     * Vanilla only converts a block the ore is allowed to replace: ancient debris replaces
     * netherrack and nothing else. Skipping this test was predicting debris inside basalt
     * deltas, soul sand valleys and blackstone blobs, where none can generate.
     *
     * An unmined vein reads back as the ore itself on servers that do not obfuscate, and as
     * netherrack on the ones that do, so both are accepted. A mined out vein reads as air and
     * correctly drops out.
     */
    private boolean targetMatches(BlockPos pos, NetherOre ore, WorldgenRandom random) {
        if (forceRaw || airCheck.get() == AirCheck.Off) return true;

        BlockState state = mc.level.getBlockState(pos);
        if (ore.oreState != null && state.is(ore.oreState.getBlock())) return true;

        for (OreConfiguration.TargetBlockState target : ore.targetStates) {
            if (target.target.test(state, random)) return true;
        }

        return false;
    }

    /**
     * Vanilla's air exposure discard. The random is consumed exactly as vanilla consumes it no
     * matter what the air-check setting says, otherwise the stream would drift and every position
     * after this one would be wrong.
     */
    private boolean shouldPlace(BlockPos pos, float discardOnAir, WorldgenRandom random) {
        boolean skip;

        if (discardOnAir <= 0f) skip = true;
        else if (discardOnAir >= 1f) skip = false;
        else skip = random.nextFloat() >= discardOnAir;

        if (skip || forceRaw || airCheck.get() == AirCheck.Off) return true;

        for (Direction dir : Direction.values()) {
            if (mc.level.getBlockState(pos.relative(dir)).isAir()) return false;
        }

        return true;
    }

    // Mojang's OreFeature, kept in its original shape so it stays easy to diff against vanilla
    private List<BlockPos> generateNormal(WorldgenRandom random, BlockPos blockPos, NetherOre ore) {
        int veinSize = ore.size;

        float f = random.nextFloat() * Mth.PI;
        float g = (float) veinSize / 8.0F;
        int i = Mth.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d = (double) blockPos.getX() + Math.sin(f) * (double) g;
        double e = (double) blockPos.getX() - Math.sin(f) * (double) g;
        double h = (double) blockPos.getZ() + Math.cos(f) * (double) g;
        double j = (double) blockPos.getZ() - Math.cos(f) * (double) g;
        double l = blockPos.getY() + random.nextInt(3) - 2;
        double m = blockPos.getY() + random.nextInt(3) - 2;
        int n = blockPos.getX() - Mth.ceil(g) - i;
        int o = blockPos.getY() - 2 - i;
        int p = blockPos.getZ() - Mth.ceil(g) - i;
        int q = 2 * (Mth.ceil(g) + i);
        int r = 2 * (2 + i);

        if (forceRaw) return generateVeinPart(random, ore, d, e, h, j, l, m, n, o, p, q, r);

        for (int s = n; s <= n + q; ++s) {
            for (int t = p; t <= p + q; ++t) {
                if (o <= mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, s, t)) {
                    return generateVeinPart(random, ore, d, e, h, j, l, m, n, o, p, q, r);
                }
            }
        }

        return new ArrayList<>();
    }

    private List<BlockPos> generateVeinPart(
        WorldgenRandom random, NetherOre ore,
        double startX, double endX, double startZ, double endZ, double startY, double endY,
        int x, int y, int z, int size, int i
    ) {
        int veinSize = ore.size;

        BitSet bitSet = new BitSet(size * i * size);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        double[] ds = new double[veinSize * 4];

        List<BlockPos> poses = new ArrayList<>();

        int n;
        double p;
        double q;
        double r;
        double s;

        for (n = 0; n < veinSize; ++n) {
            float f = (float) n / (float) veinSize;
            p = Mth.lerp(f, startX, endX);
            q = Mth.lerp(f, startY, endY);
            r = Mth.lerp(f, startZ, endZ);
            s = random.nextDouble() * (double) veinSize / 16.0D;
            double m = ((double) (Mth.sin(Mth.PI * f) + 1.0F) * s + 1.0D) / 2.0D;
            ds[n * 4] = p;
            ds[n * 4 + 1] = q;
            ds[n * 4 + 2] = r;
            ds[n * 4 + 3] = m;
        }

        for (n = 0; n < veinSize - 1; ++n) {
            if (!(ds[n * 4 + 3] <= 0.0D)) {
                for (int o = n + 1; o < veinSize; ++o) {
                    if (!(ds[o * 4 + 3] <= 0.0D)) {
                        p = ds[n * 4] - ds[o * 4];
                        q = ds[n * 4 + 1] - ds[o * 4 + 1];
                        r = ds[n * 4 + 2] - ds[o * 4 + 2];
                        s = ds[n * 4 + 3] - ds[o * 4 + 3];
                        if (s * s > p * p + q * q + r * r) {
                            if (s > 0.0D) ds[o * 4 + 3] = -1.0D;
                            else ds[n * 4 + 3] = -1.0D;
                        }
                    }
                }
            }
        }

        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();

        for (n = 0; n < veinSize; ++n) {
            double u = ds[n * 4 + 3];
            if (u < 0.0D) continue;

            double v = ds[n * 4];
            double w = ds[n * 4 + 1];
            double aa = ds[n * 4 + 2];
            int ab = Math.max(Mth.floor(v - u), x);
            int ac = Math.max(Mth.floor(w - u), y);
            int ad = Math.max(Mth.floor(aa - u), z);
            int ae = Math.max(Mth.floor(v + u), ab);
            int af = Math.max(Mth.floor(w + u), ac);
            int ag = Math.max(Mth.floor(aa + u), ad);

            for (int ah = ab; ah <= ae; ++ah) {
                double ai = ((double) ah + 0.5D - v) / u;
                if (ai * ai >= 1.0D) continue;

                for (int aj = ac; aj <= af; ++aj) {
                    double ak = ((double) aj + 0.5D - w) / u;
                    if (ai * ai + ak * ak >= 1.0D) continue;

                    for (int al = ad; al <= ag; ++al) {
                        double am = ((double) al + 0.5D - aa) / u;
                        if (ai * ai + ak * ak + am * am >= 1.0D) continue;

                        int an = ah - x + (aj - y) * size + (al - z) * size * i;
                        if (bitSet.get(an)) continue;
                        bitSet.set(an);

                        mutable.set(ah, aj, al);
                        if (aj < minY || aj > maxY) continue;
                        if (!targetMatches(mutable, ore, random)) continue;

                        if (shouldPlace(mutable, ore.discardOnAirChance, random)) {
                            poses.add(mutable.immutable());
                        }
                    }
                }
            }
        }

        return poses;
    }

    private List<BlockPos> generateScattered(WorldgenRandom random, BlockPos blockPos, NetherOre ore) {
        List<BlockPos> poses = new ArrayList<>();

        int i = random.nextInt(ore.size + 1);

        for (int j = 0; j < i; ++j) {
            int spread = Math.min(j, 7);
            int x = randomCoord(random, spread) + blockPos.getX();
            int y = randomCoord(random, spread) + blockPos.getY();
            int z = randomCoord(random, spread) + blockPos.getZ();

            BlockPos pos = new BlockPos(x, y, z);

            if (!targetMatches(pos, ore, random)) {
                discardBuffer.add(pos);
                continue;
            }

            if (shouldPlace(pos, ore.discardOnAirChance, random)) poses.add(pos);
            else discardBuffer.add(pos);
        }

        return poses;
    }

    private int randomCoord(WorldgenRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) size);
    }
}
