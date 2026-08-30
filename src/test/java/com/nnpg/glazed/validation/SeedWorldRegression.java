package com.nnpg.glazed.validation;

import com.nnpg.glazed.utils.OverworldOre;
import com.nnpg.glazed.utils.SeedWorld;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Drives the production {@link SeedWorld} — the same class the Em Ore module runs in game — over
 * the emerald candidates that produced the n-17.22 disappearing tracers, and checks it two ways:
 *
 *   1. its post-carver OCEAN_FLOOR_WG against floors captured out of a real vanilla server at the
 *      moment it decorated those chunks (build/tmp/t10agent/exact-floors.csv)
 *   2. the full emerald replay driven by its generated biome palette and those floors, against the
 *      ten targets whose ground truth was read off the server world
 *
 * {@link EmeraldBiomeHarness} proved the approach with a throwaway oracle. This one proves the
 * shipped code, so a regression in SeedWorld itself cannot pass unnoticed.
 */
public final class SeedWorldRegression {
    private static final long SEED = 6608149111735331168L;
    private static final int STEP = 6;
    private static final int INDEX = 33;
    /** OCEAN_FLOOR_WG as a real vanilla server saw it while decorating these chunks. */
    private static final String EXACT_FLOOR_RESOURCE = "exact-floors.csv";

    private record Target(String id, BlockPos pos, boolean emeraldInVanilla) {}

    private static final List<Target> TARGETS = List.of(
        new Target("T1", new BlockPos(-209824, -9, -191311), true),
        new Target("T2", new BlockPos(-209858, -7, -191004), true),
        new Target("T3", new BlockPos(-209920, -7, -190968), false),
        new Target("T4", new BlockPos(-209930, -1, -190928), true),
        new Target("T5", new BlockPos(-209664, -8, -191203), true),
        new Target("T6", new BlockPos(-209823, -9, -191311), true),
        new Target("T7", new BlockPos(-209538, -4, -191158), true),
        new Target("T8", new BlockPos(-209517, -1, -191149), false),
        new Target("T9", new BlockPos(-209476, 0, -191084), false),
        new Target("T10", new BlockPos(-209350, -7, -191156), true)
    );

    /** The attempt whose raw-sampler biome said forest while vanilla's palette said meadow. */
    private static final BlockPos BOUNDARY = new BlockPos(-209351, -9, -191165);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindVanillaTags();

        HolderLookup.Provider registry = VanillaRegistries.createLookup();
        LevelStem stem = WorldPresets.createNormalWorldDimensions(registry).dimensions().get(LevelStem.OVERWORLD);
        LevelHeightAccessor heights = LevelHeightAccessor.create(-64, 384);
        PlacedFeature emerald = registry.lookupOrThrow(Registries.PLACED_FEATURE)
            .getOrThrow(OrePlacements.ORE_EMERALD).value();
        HolderLookup.RegistryLookup<Biome> biomes = registry.lookupOrThrow(Registries.BIOME);

        int actualIndex = FeatureSorter.buildFeaturesPerStep(
            stem.generator().getBiomeSource().possibleBiomes().stream().toList(),
            biome -> biome.value().getGenerationSettings().features(), true)
            .get(STEP).indexMapping().applyAsInt(emerald);
        if (actualIndex != INDEX) throw new AssertionError("Emerald feature index changed: " + actualIndex);

        // OverworldOre.get() reads the placement modifiers through mixin accessors, which only
        // exist inside a loaded client. The three caches SeedWorld needs are built from plain
        // vanilla data, so prime them directly instead.
        setStatic("registryCache", registry);
        setStatic("generatorCache", stem.generator());
        setStatic("heightsCache", heights);

        SeedWorld world = SeedWorld.create(SEED, biomeOnlyAccess());
        if (world == null) throw new AssertionError("SeedWorld.create returned null");

        try {
            checkBoundaryBiome(world);
            int checkedChunks = checkFloors(world);
            checkReplay(world, stem, emerald, biomes);
            System.out.printf("SEED WORLD REGRESSION PASSED: %d floor chunks exact, %d/%d targets%n",
                checkedChunks, TARGETS.size(), TARGETS.size());
        } finally {
            world.close();
        }
    }

    /** The single lookup that used to poison the random stream and erase real tracers. */
    private static void checkBoundaryBiome(SeedWorld world) {
        ResourceKey<Biome> biome = biome(world, BOUNDARY);
        String actual = biome == null ? "null" : biome.identifier().toString();
        if (!actual.equals("minecraft:meadow")) {
            throw new AssertionError("Boundary attempt biome is " + actual + ", vanilla generates meadow");
        }
        System.out.println("boundary attempt " + BOUNDARY.toShortString() + " biome=" + actual);
    }

    /**
     * Every column of every chunk the server was caught decorating. WorldGenRegion.getHeight is
     * ChunkAccess.getHeight + 1 and both sides use that contract, so this is an exact comparison.
     */
    private static int checkFloors(SeedWorld world) throws Exception {
        Map<Long, int[]> expected = new HashMap<>();
        for (String line : exactFloorLines()) {
            if (line.isBlank()) continue;
            String[] values = line.split(",");
            if (values.length != 258) throw new AssertionError("Malformed floor line: " + values.length);
            int[] floors = new int[256];
            for (int i = 0; i < floors.length; i++) floors[i] = Integer.parseInt(values[i + 2]);
            expected.put(ChunkPos.pack(Integer.parseInt(values[0]), Integer.parseInt(values[1])), floors);
        }

        int mismatches = 0;
        Map<Long, Integer> perChunk = new HashMap<>();
        StringBuilder first = new StringBuilder();
        for (Map.Entry<Long, int[]> entry : expected.entrySet()) {
            ChunkPos pos = ChunkPos.unpack(entry.getKey());
            world.prepareTerrain(pos).join();

            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int blockX = pos.getMinBlockX() + x;
                    int blockZ = pos.getMinBlockZ() + z;
                    Integer replay = world.oceanFloor(blockX, blockZ);
                    if (replay == null) throw new AssertionError("Floor missing after prepareTerrain at " + pos);
                    int server = entry.getValue()[(z << 4) | x];
                    if (replay != server) {
                        mismatches++;
                        perChunk.merge(entry.getKey(), 1, Integer::sum);
                        if (first.length() < 400) {
                            first.append(' ').append(blockX).append(',').append(blockZ)
                                .append(':').append(server).append("!=").append(replay);
                        }
                    }
                }
            }
        }

        if (mismatches > 0) {
            perChunk.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> System.out.printf("  chunk %s bad=%d%n", ChunkPos.unpack(e.getKey()), e.getValue()));
            System.out.printf("chunks with any mismatch: %d/%d%n", perChunk.size(), expected.size());
            throw new AssertionError("OCEAN_FLOOR_WG differs from the server in " + mismatches
                + " columns:" + first);
        }
        System.out.printf("floors exact for %d chunks (%d columns)%n", expected.size(), expected.size() * 256);
        return expected.size();
    }

    private static List<String> exactFloorLines() throws Exception {
        try (var stream = SeedWorldRegression.class.getResourceAsStream(EXACT_FLOOR_RESOURCE)) {
            if (stream != null) {
                return new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
                    .lines().toList();
            }
        }

        Path fallback = Path.of("build/tmp/t10agent/" + EXACT_FLOOR_RESOURCE);
        if (!Files.exists(fallback)) throw new AssertionError("Missing server floor capture: " + EXACT_FLOOR_RESOURCE);
        return Files.readAllLines(fallback);
    }

    /** Vanilla emerald placement, with every world question answered by the production SeedWorld. */
    private static void checkReplay(
        SeedWorld world,
        LevelStem stem,
        PlacedFeature emerald,
        HolderLookup.RegistryLookup<Biome> biomes
    ) throws Exception {
        IntProvider count = null;
        HeightProvider height = null;
        for (PlacementModifier modifier : emerald.placement()) {
            if (modifier instanceof CountPlacement) count = field(modifier, "count", IntProvider.class);
            if (modifier instanceof HeightRangePlacement) height = field(modifier, "height", HeightProvider.class);
        }
        if (count == null || height == null) throw new AssertionError("Emerald placement modifiers missing");

        OreConfiguration config = (OreConfiguration) emerald.feature().value().config();
        WorldGenerationContext heightContext = new WorldGenerationContext(
            stem.generator(), LevelHeightAccessor.create(-64, 384));

        Set<Long> sourceChunks = new HashSet<>();
        for (Target target : TARGETS) {
            ChunkPos chunk = ChunkPos.containing(target.pos());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    sourceChunks.add(ChunkPos.pack(chunk.x() + dx, chunk.z() + dz));
                }
            }
        }

        Set<BlockPos> hits = new HashSet<>();
        Map<Long, Integer> floors = new HashMap<>();
        for (long key : sourceChunks) {
            ChunkPos chunk = ChunkPos.unpack(key);
            int chunkX = chunk.getMinBlockX();
            int chunkZ = chunk.getMinBlockZ();
            WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
            long populationSeed = random.setDecorationSeed(SEED, chunkX, chunkZ);
            random.setFeatureSeed(populationSeed, INDEX, STEP);

            int repeats = count.sample(random);
            for (int attempt = 0; attempt < repeats; attempt++) {
                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = height.sample(random, heightContext);
                BlockPos origin = new BlockPos(x, y, z);

                ResourceKey<Biome> biome = biome(world, origin);
                if (biome == null || !hasFeature(biomes.getOrThrow(biome), emerald)) continue;
                hits.addAll(geometry(world, random, origin, config.size, floors));
            }
        }

        int correct = 0;
        for (Target target : TARGETS) {
            boolean hit = hits.contains(target.pos());
            boolean ok = hit == target.emeraldInVanilla();
            if (ok) correct++;
            System.out.printf("%s production=%-5s groundTruthOre=%-5s %s%n",
                target.id(), hit, target.emeraldInVanilla(), ok ? "OK" : "MISMATCH");
        }
        if (correct != TARGETS.size()) {
            throw new AssertionError("Production replay matched " + correct + "/" + TARGETS.size());
        }
    }

    /** OreFeature's vein shape, including the whole-vein OCEAN_FLOOR_WG gate that precedes it. */
    private static List<BlockPos> geometry(
        SeedWorld world,
        WorldgenRandom random,
        BlockPos origin,
        int veinSize,
        Map<Long, Integer> floors
    ) {
        float angle = random.nextFloat() * Mth.PI;
        float spread = (float) veinSize / 8.0F;
        int padding = Mth.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double startX = origin.getX() + Math.sin(angle) * spread;
        double endX = origin.getX() - Math.sin(angle) * spread;
        double startZ = origin.getZ() + Math.cos(angle) * spread;
        double endZ = origin.getZ() - Math.cos(angle) * spread;
        double startY = origin.getY() + random.nextInt(3) - 2;
        double endY = origin.getY() + random.nextInt(3) - 2;
        int minX = origin.getX() - Mth.ceil(spread) - padding;
        int minY = origin.getY() - 2 - padding;
        int minZ = origin.getZ() - Mth.ceil(spread) - padding;
        int horizontal = 2 * (Mth.ceil(spread) + padding);
        int vertical = 2 * (2 + padding);

        boolean gate = false;
        for (int x = minX; x <= minX + horizontal && !gate; x++) {
            for (int z = minZ; z <= minZ + horizontal; z++) {
                long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                Integer cached = floors.get(key);
                int floor = cached != null ? cached : floor(world, x, z);
                if (cached == null) floors.put(key, floor);
                if (minY <= floor) {
                    gate = true;
                    break;
                }
            }
        }
        if (!gate) return List.of();

        BitSet seen = new BitSet(horizontal * vertical * horizontal);
        double[] spheres = new double[veinSize * 4];
        for (int index = 0; index < veinSize; index++) {
            float t = (float) index / veinSize;
            double x = Mth.lerp(t, startX, endX);
            double y = Mth.lerp(t, startY, endY);
            double z = Mth.lerp(t, startZ, endZ);
            double randomRadius = random.nextDouble() * veinSize / 16.0D;
            double radius = ((Mth.sin(Mth.PI * t) + 1.0F) * randomRadius + 1.0D) / 2.0D;
            spheres[index * 4] = x;
            spheres[index * 4 + 1] = y;
            spheres[index * 4 + 2] = z;
            spheres[index * 4 + 3] = radius;
        }

        for (int a = 0; a < veinSize - 1; a++) {
            if (spheres[a * 4 + 3] <= 0) continue;
            for (int b = a + 1; b < veinSize; b++) {
                if (spheres[b * 4 + 3] <= 0) continue;
                double dx = spheres[a * 4] - spheres[b * 4];
                double dy = spheres[a * 4 + 1] - spheres[b * 4 + 1];
                double dz = spheres[a * 4 + 2] - spheres[b * 4 + 2];
                double dr = spheres[a * 4 + 3] - spheres[b * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0) spheres[b * 4 + 3] = -1;
                    else spheres[a * 4 + 3] = -1;
                }
            }
        }

        List<BlockPos> out = new ArrayList<>();
        for (int sphere = 0; sphere < veinSize; sphere++) {
            double radius = spheres[sphere * 4 + 3];
            if (radius < 0) continue;
            double centreX = spheres[sphere * 4];
            double centreY = spheres[sphere * 4 + 1];
            double centreZ = spheres[sphere * 4 + 2];
            int loX = Math.max(Mth.floor(centreX - radius), minX);
            int loY = Math.max(Mth.floor(centreY - radius), minY);
            int loZ = Math.max(Mth.floor(centreZ - radius), minZ);
            int hiX = Math.max(Mth.floor(centreX + radius), loX);
            int hiY = Math.max(Mth.floor(centreY + radius), loY);
            int hiZ = Math.max(Mth.floor(centreZ + radius), loZ);

            for (int x = loX; x <= hiX; x++) {
                double nx = (x + 0.5D - centreX) / radius;
                if (nx * nx >= 1) continue;
                for (int y = loY; y <= hiY; y++) {
                    double ny = (y + 0.5D - centreY) / radius;
                    if (nx * nx + ny * ny >= 1) continue;
                    for (int z = loZ; z <= hiZ; z++) {
                        double nz = (z + 0.5D - centreZ) / radius;
                        if (nx * nx + ny * ny + nz * nz >= 1) continue;
                        int bit = x - minX + (y - minY) * horizontal + (z - minZ) * horizontal * vertical;
                        if (seen.get(bit)) continue;
                        seen.set(bit);
                        out.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    /** Blocking is fine here; in game the finder retries the chunk once the future completes. */
    private static ResourceKey<Biome> biome(SeedWorld world, BlockPos pos) {
        while (true) {
            try {
                return world.biome(pos.getX(), pos.getY(), pos.getZ());
            } catch (SeedWorld.GenerationPending pending) {
                pending.future().join();
            }
        }
    }

    private static int floor(SeedWorld world, int x, int z) {
        Integer value = world.oceanFloor(x, z);
        if (value != null) return value;
        world.prepareTerrain(new ChunkPos(x >> 4, z >> 4)).join();
        value = world.oceanFloor(x, z);
        if (value == null) throw new AssertionError("Floor still missing at " + x + "," + z);
        return value;
    }

    private static boolean hasFeature(Holder<Biome> biome, PlacedFeature wanted) {
        return biome.value().getGenerationSettings().features().stream()
            .flatMap(HolderSet::stream)
            .map(Holder::value)
            .anyMatch(feature -> feature == wanted);
    }

    /**
     * SeedWorld only ever asks a RegistryAccess for the biome registry, and the two places it goes
     * — buildSurface and CarvingContext — never read it back, so an empty one is enough to run the
     * real code path without fabricating a server level.
     */
    private static RegistryAccess biomeOnlyAccess() {
        Registry<Biome> empty = new MappedRegistry<>(Registries.BIOME, Lifecycle.experimental()).freeze();
        return new RegistryAccess() {
            @Override
            @SuppressWarnings("unchecked")
            public <E> Optional<Registry<E>> lookup(ResourceKey<? extends Registry<? extends E>> key) {
                return key.equals(Registries.BIOME) ? Optional.of((Registry<E>) empty) : Optional.empty();
            }

            @Override
            public Stream<RegistryEntry<?>> registries() {
                return Stream.of(new RegistryEntry<>(Registries.BIOME, empty));
            }
        };
    }


    /**
     * Bootstrap alone leaves every block tag unbound, and the carvers gate every block they cut on
     * #overworld_carver_replaceables. Without this the replay silently generates uncarved terrain,
     * which looks like a SeedWorld bug and is really a missing data pack. In game the client has
     * these tags from the server, so binding them here is what makes the offline run comparable.
     */
    private static void bindVanillaTags() throws Exception {
        Path jar = Path.of(Block.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            bindTagsFor(fs, "block", BuiltInRegistries.BLOCK, Registries.BLOCK);
            bindTagsFor(fs, "fluid", BuiltInRegistries.FLUID, Registries.FLUID);
        }

        int replaceables = (int) java.util.stream.StreamSupport
            .stream(BuiltInRegistries.BLOCK.getTagOrEmpty(BlockTags.OVERWORLD_CARVER_REPLACEABLES).spliterator(), false)
            .count();
        if (replaceables == 0) throw new AssertionError("Carver replaceables tag is still empty");
        System.out.println("bound vanilla tags, overworld_carver_replaceables=" + replaceables);
    }

    private static <T> void bindTagsFor(
        FileSystem fs,
        String folder,
        Registry<T> registry,
        ResourceKey<? extends Registry<T>> key
    ) throws Exception {
        Path root = fs.getPath("/data/minecraft/tags/" + folder);
        if (!Files.isDirectory(root)) throw new AssertionError("No " + folder + " tags in the client jar");

        Map<String, List<String>> raw = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList())) {
                String name = root.relativize(file).toString();
                name = name.substring(0, name.length() - ".json".length());
                List<String> entries = new ArrayList<>();
                try (Reader reader = Files.newBufferedReader(file)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonArray values = json.getAsJsonArray("values");
                    if (values == null) continue;
                    for (JsonElement element : values) {
                        if (element.isJsonPrimitive()) entries.add(element.getAsString());
                        else entries.add(element.getAsJsonObject().get("id").getAsString());
                    }
                }
                raw.put("minecraft:" + name, entries);
            }
        }

        Map<TagKey<T>, List<Holder<T>>> bound = new HashMap<>();
        for (String tag : raw.keySet()) {
            List<Holder<T>> holders = new ArrayList<>();
            resolve(registry, raw, tag, holders, new HashSet<>());
            bound.put(TagKey.create(key, Identifier.parse(tag)), holders);
        }
        registry.prepareTagReload(new TagLoader.LoadResult<>(key, bound)).apply();
    }

    private static <T> void resolve(
        Registry<T> registry,
        Map<String, List<String>> raw,
        String tag,
        List<Holder<T>> out,
        Set<String> seen
    ) {
        if (!seen.add(tag)) return;
        for (String entry : raw.getOrDefault(tag, List.of())) {
            if (entry.startsWith("#")) {
                resolve(registry, raw, entry.substring(1), out, seen);
            } else {
                registry.get(Identifier.parse(entry))
                    .filter(holder -> !out.contains(holder))
                    .ifPresent(out::add);
            }
        }
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = OverworldOre.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
}
