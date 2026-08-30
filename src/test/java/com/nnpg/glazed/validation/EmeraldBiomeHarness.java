package com.nnpg.glazed.validation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deterministic regression for the n-17.22 false targets captured in the live log. It replays
 * vanilla emerald placement twice: once with the old raw-quart biome lookup and once with the
 * BiomeManager lookup that WorldGenLevel.getBiome actually uses.
 */
public final class EmeraldBiomeHarness {
    private static final long SEED = 6608149111735331168L;
    private static final int STEP = 6;
    private static final int INDEX = 33;

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

    private enum Lookup { RawQuart, VanillaManager, VanillaManagerNoGate }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registry = VanillaRegistries.createLookup();
        LevelStem stem = WorldPresets.createNormalWorldDimensions(registry).dimensions().get(LevelStem.OVERWORLD);
        PlacedFeature emerald = registry.lookupOrThrow(Registries.PLACED_FEATURE)
            .getOrThrow(OrePlacements.ORE_EMERALD).value();

        List<Holder<Biome>> biomes = stem.generator().getBiomeSource().possibleBiomes().stream().toList();
        int actualIndex = FeatureSorter.buildFeaturesPerStep(
            biomes, biome -> biome.value().getGenerationSettings().features(), true)
            .get(STEP).indexMapping().applyAsInt(emerald);
        if (actualIndex != INDEX) throw new AssertionError("Emerald index changed: " + actualIndex);

        List<PlacedFeature> stepFeatures = FeatureSorter.buildFeaturesPerStep(
            biomes, biome -> biome.value().getGenerationSettings().features(), true).get(STEP).features();
        var placedRegistry = registry.lookupOrThrow(Registries.PLACED_FEATURE);
        for (int index = 0; index < stepFeatures.size(); index++) {
            PlacedFeature feature = stepFeatures.get(index);
            String key = placedRegistry.listElements()
                .filter(holder -> holder.value() == feature)
                .findFirst()
                .flatMap(Holder::unwrapKey)
                .map(Object::toString)
                .orElse("unregistered");
            System.out.printf("STEP6[%d]=%s%n", index, key);
        }

        IntProvider count = null;
        HeightProvider height = null;
        for (PlacementModifier modifier : emerald.placement()) {
            if (modifier instanceof CountPlacement) count = field(modifier, "count", IntProvider.class);
            if (modifier instanceof HeightRangePlacement) height = field(modifier, "height", HeightProvider.class);
        }
        if (count == null || height == null) throw new AssertionError("Emerald placement modifiers missing");

        OreConfiguration config = (OreConfiguration) emerald.feature().value().config();
        LevelHeightAccessor levels = LevelHeightAccessor.create(-64, 384);
        WorldGenerationContext heightContext = new WorldGenerationContext(stem.generator(), levels);
        RandomState randomState = RandomState.create(registry, NoiseGeneratorSettings.OVERWORLD, SEED);
        BiomeSource source = stem.generator().getBiomeSource();
        BiomeManager manager = new BiomeManager(
            (x, y, z) -> source.getNoiseBiome(x, y, z, randomState.sampler()),
            BiomeManager.obfuscateSeed(SEED));

        BlockPos t10Origin = new BlockPos(-209349, -5, -191156);
        System.out.printf("T10 origin rawBiome=%s managerBiome=%s%n",
            source.getNoiseBiome(t10Origin.getX() >> 2, t10Origin.getY() >> 2,
                t10Origin.getZ() >> 2, randomState.sampler()).unwrapKey().orElse(null),
            manager.getBiome(t10Origin).unwrapKey().orElse(null));

        Map<Long, Integer> floors = new HashMap<>();
        Set<BlockPos> raw = replay(Lookup.RawQuart, stem, emerald, count, height, config.size,
            heightContext, randomState, source, manager, floors);
        Set<BlockPos> vanilla = replay(Lookup.VanillaManager, stem, emerald, count, height, config.size,
            heightContext, randomState, source, manager, floors);
        Set<BlockPos> noGate = replay(Lookup.VanillaManagerNoGate, stem, emerald, count, height, config.size,
            heightContext, randomState, source, manager, floors);

        int correct = 0;
        for (Target target : TARGETS) {
            boolean rawHit = raw.contains(target.pos());
            boolean vanillaHit = vanilla.contains(target.pos());
            boolean ok = vanillaHit == target.emeraldInVanilla();
            if (ok) correct++;
            System.out.printf("%s raw=%-5s vanillaBiome=%-5s noGate=%-5s groundTruthOre=%-5s %s%n",
                target.id(), rawHit, vanillaHit, noGate.contains(target.pos()), target.emeraldInVanilla(), ok ? "OK" : "MISMATCH");
        }

        if (correct != TARGETS.size()) {
            throw new AssertionError("Vanilla biome replay matched " + correct + "/" + TARGETS.size());
        }
        System.out.println("EMERALD BIOME REGRESSION PASSED " + correct + "/" + TARGETS.size());
    }

    private static Set<BlockPos> replay(
        Lookup lookup,
        LevelStem stem,
        PlacedFeature emerald,
        IntProvider count,
        HeightProvider height,
        int veinSize,
        WorldGenerationContext heightContext,
        RandomState randomState,
        BiomeSource source,
        BiomeManager manager,
        Map<Long, Integer> floors
    ) {
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

                Holder<Biome> biome = lookup == Lookup.RawQuart
                    ? source.getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2, randomState.sampler())
                    : manager.getBiome(origin);
                if (!hasFeature(biome, emerald)) continue;

                List<BlockPos> blocks = geometry(random, origin, veinSize, stem, randomState, floors,
                    lookup == Lookup.VanillaManagerNoGate);
                BlockPos t10 = TARGETS.get(9).pos();
                BlockPos t10Other = new BlockPos(-209349, -7, -191157);
                if (blocks.contains(t10) || blocks.contains(t10Other)) {
                    System.out.printf("%s T10 geometry source=%s attempt=%d origin=%s biome=%s blocks=%s%n",
                        lookup, chunk, attempt, origin,
                        biome.unwrapKey().map(Object::toString).orElse("unregistered"), blocks);
                }
                hits.addAll(blocks);
            }
        }
        return hits;
    }

    private static boolean hasFeature(Holder<Biome> biome, PlacedFeature wanted) {
        return biome.value().getGenerationSettings().features().stream()
            .flatMap(HolderSet::stream)
            .map(Holder::value)
            .anyMatch(feature -> feature == wanted);
    }

    private static List<BlockPos> geometry(
        WorldgenRandom random,
        BlockPos origin,
        int veinSize,
        LevelStem stem,
        RandomState randomState,
        Map<Long, Integer> floors,
        boolean noGate
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

        boolean gate = noGate;
        for (int x = minX; x <= minX + horizontal && !gate; x++) {
            for (int z = minZ; z <= minZ + horizontal; z++) {
                long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                Integer cached = floors.get(key);
                int floor;
                if (cached != null) floor = cached;
                else {
                    floor = stem.generator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                        LevelHeightAccessor.create(-64, 384), randomState);
                    floors.put(key, floor);
                }
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

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
}
