package com.nnpg.glazed.utils;

import com.nnpg.glazed.mixins.CountPlacementAccessor;
import com.nnpg.glazed.mixins.HeightRangePlacementAccessor;
import com.nnpg.glazed.mixins.RarityFilterAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The vanilla ore placements that run in the nether, with the numbers that decide where each vein
 * lands.
 *
 * The important part is {@link #index} and {@link #step}. Feature placement is seeded with
 * setFeatureSeed(populationSeed, index, step), where index is the feature's position in the
 * flattened list of every feature that runs at that step across every biome in the dimension.
 * That ordering is derived here from the real vanilla data through FeatureSorter, so it is exact
 * rather than guessed. Nether ores sit in UNDERGROUND_DECORATION, not UNDERGROUND_ORES like the
 * overworld ones.
 */
public class NetherOre {

    public enum Type {
        DEBRIS,
        QUARTZ,
        GOLD,
        // not ores as far as anti-xray is concerned, so the server sends these truthfully even
        // when buried. That makes them the only way to check feature ordering without mining.
        MAGMA,
        GRAVEL,
        BLACKSTONE
    }

    private static Map<ResourceKey<Biome>, List<NetherOre>> cache;

    public final Type type;
    public final int step;
    public final int index;
    public final int size;
    public final float discardOnAirChance;
    public final boolean scattered;
    /** What vanilla is allowed to replace, e.g. ancient debris only replaces netherrack. */
    public final List<OreConfiguration.TargetBlockState> targetStates;
    public final BlockState oreState;
    public final IntProvider count;
    public final HeightProvider heightProvider;
    public final WorldGenerationContext heightContext;
    public final int rarity;

    private NetherOre(PlacedFeature feature, Type type, int step, int index, WorldGenerationContext heightContext) {
        this.type = type;
        this.step = step;
        this.index = index;
        this.heightContext = heightContext;

        IntProvider count = ConstantInt.of(1);
        HeightProvider height = null;
        int rarity = 1;

        for (PlacementModifier modifier : feature.placement()) {
            if (modifier instanceof CountPlacement) count = ((CountPlacementAccessor) modifier).getCount();
            else if (modifier instanceof HeightRangePlacement) height = ((HeightRangePlacementAccessor) modifier).getHeight();
            else if (modifier instanceof RarityFilter) rarity = ((RarityFilterAccessor) modifier).getChance();
        }

        this.count = count;
        this.heightProvider = height;
        this.rarity = rarity;

        FeatureConfiguration config = feature.feature().value().config();
        if (!(config instanceof OreConfiguration ore)) {
            throw new IllegalStateException("placed feature is not an ore: " + feature);
        }

        this.targetStates = ore.targetStates;
        this.oreState = ore.targetStates.isEmpty() ? null : ore.targetStates.get(0).state;
        this.size = ore.size;
        this.discardOnAirChance = ore.discardChanceOnAirExposure;
        this.scattered = feature.feature().value().feature() instanceof ScatteredOreFeature;
    }

    /**
     * Builds the biome to ore map from the vanilla worldgen data shipped inside the client jar.
     * Costs a second or so the first time, so the result is cached. Height values are passed in
     * rather than read off the level, so this is safe to run off the main thread.
     */
    public static Map<ResourceKey<Biome>, List<NetherOre>> get(int minY, int logicalHeight) {
        if (cache != null) return cache;

        HolderLookup.Provider registry = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<PlacedFeature> placed = registry.lookupOrThrow(Registries.PLACED_FEATURE);

        LevelStem nether = WorldPresets.createNormalWorldDimensions(registry).dimensions().get(LevelStem.NETHER);
        List<Holder<Biome>> biomes = nether.generator().getBiomeSource().possibleBiomes().stream().toList();

        List<FeatureSorter.StepFeatureData> indexer = FeatureSorter.buildFeaturesPerStep(
            biomes, biome -> biome.value().getGenerationSettings().features(), true);

        int step = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();

        LevelHeightAccessor heights = LevelHeightAccessor.create(minY, logicalHeight);
        // 26.1.2 dereferences the generator in this constructor, so a null one crashes here
        WorldGenerationContext heightContext = new WorldGenerationContext(nether.generator(), heights);

        Map<PlacedFeature, NetherOre> byFeature = new HashMap<>();
        register(byFeature, indexer, placed, OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, Type.DEBRIS, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, Type.DEBRIS, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_QUARTZ_NETHER, Type.QUARTZ, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_QUARTZ_DELTAS, Type.QUARTZ, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_GOLD_NETHER, Type.GOLD, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_GOLD_DELTAS, Type.GOLD, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_MAGMA, Type.MAGMA, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_GRAVEL_NETHER, Type.GRAVEL, step, heightContext);
        register(byFeature, indexer, placed, OrePlacements.ORE_BLACKSTONE, Type.BLACKSTONE, step, heightContext);

        Map<ResourceKey<Biome>, List<NetherOre>> byBiome = new HashMap<>();

        for (Holder<Biome> biome : biomes) {
            ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
            if (key == null) continue;

            List<NetherOre> ores = new ArrayList<>();
            biome.value().getGenerationSettings().features().stream()
                .flatMap(HolderSet::stream)
                .map(Holder::value)
                .filter(byFeature::containsKey)
                .forEach(feature -> ores.add(byFeature.get(feature)));

            byBiome.put(key, ores);
        }

        cache = byBiome;
        return cache;
    }

    /** Dropped when you leave the world, since the height context is tied to the dimension. */
    public static void invalidate() {
        cache = null;
    }

    private static void register(
        Map<PlacedFeature, NetherOre> map,
        List<FeatureSorter.StepFeatureData> indexer,
        HolderLookup.RegistryLookup<PlacedFeature> placed,
        ResourceKey<PlacedFeature> key,
        Type type,
        int step,
        WorldGenerationContext heightContext
    ) {
        PlacedFeature feature = placed.getOrThrow(key).value();
        int index = indexer.get(step).indexMapping().applyAsInt(feature);
        map.put(feature, new NetherOre(feature, type, step, index, heightContext));
    }
}
