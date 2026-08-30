package com.nnpg.glazed.utils;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Predicate;

/**
 * Answers the two questions the ore simulation normally asks the client world — what biome is
 * this, and how high is the ground here — from the seed alone, so a chunk the client has never
 * received can still be simulated.
 *
 * Everything here is vanilla worldgen run client side: same BiomeSource and same
 * NoiseBasedChunkGenerator the server used, seeded the same way. Deep emerald candidates also use
 * a lazily generated noise-and-carver chunk, so a cave is rejected before it can become a target.
 *
 * Building one costs a noise router (RandomState), so create it off the main thread.
 */
public class SeedWorld {

    /** ProtoChunks are large. Only candidate chunks are generated, and old ones are discarded. */
    private static final int TERRAIN_CACHE_SIZE = 96;

    /**
     * One exact heightmap is only 1 KiB. Keep a whole maximum search square (129x129 chunks),
     * plus its vein border, so a placement retry cannot lose an earlier floor result merely
     * because the much larger ProtoChunk that produced it was evicted.
     */
    private static final int SEARCH_CACHE_SIZE = 20_000;

    /** No overworld surface sits this low, so a vein under it clears the gate without sampling. */
    public static final int ALWAYS_UNDERGROUND = -20;

    public final long seed;

    private final NoiseBasedChunkGenerator generator;
    private final BiomeSource biomeSource;
    /** Feature lookups use the generated per-chunk palette, exactly like WorldGenRegion. */
    private final BiomeManager featureBiomeManager;
    /** Surface generation may synchronously prepare an edge-neighbour on the terrain worker. */
    private final BiomeManager surfaceBiomeManager;
    /** Vanilla deliberately swaps back to the raw source while carving. */
    private final BiomeManager carverBiomeManager;
    private final RandomState randomState;
    private final LevelHeightAccessor heights;
    private final RegistryAccess registry;
    private final Holder<NoiseGeneratorSettings> noiseSettings;
    private final PalettedContainerFactory containerFactory;
    private final List<Holder<Biome>> biomePalette;
    private final Map<ResourceKey<Biome>, Short> biomeIds;
    private final Map<Long, int[]> oceanFloors = Collections.synchronizedMap(
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
                return size() > SEARCH_CACHE_SIZE;
            }
        });
    private final Map<Long, BiomeSnapshot> biomeSnapshots = Collections.synchronizedMap(
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, BiomeSnapshot> eldest) {
                return size() > SEARCH_CACHE_SIZE;
            }
        });
    private final Map<Long, ProtoChunk> terrain = Collections.synchronizedMap(
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ProtoChunk> eldest) {
                return size() > TERRAIN_CACHE_SIZE;
            }
        });
    private final Map<Long, CompletableFuture<ProtoChunk>> terrainWork = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<BiomeSnapshot>> biomeWork = new ConcurrentHashMap<>();
    private final ExecutorService terrainExecutor;
    private volatile Throwable terrainFailure;

    private SeedWorld(
        long seed,
        NoiseBasedChunkGenerator generator,
        RandomState randomState,
        LevelHeightAccessor heights,
        RegistryAccess registry,
        Holder<NoiseGeneratorSettings> noiseSettings,
        List<Holder<Biome>> biomePalette
    ) {
        this.seed = seed;
        this.generator = generator;
        this.biomeSource = generator.getBiomeSource();
        this.randomState = randomState;
        this.heights = heights;
        this.registry = registry;
        this.noiseSettings = noiseSettings;
        this.biomePalette = List.copyOf(biomePalette);
        this.biomeIds = buildBiomeIds(this.biomePalette);
        this.containerFactory = createContainerFactory(this.biomePalette);
        this.featureBiomeManager = new BiomeManager(
            this::featureBiome,
            BiomeManager.obfuscateSeed(seed));
        this.surfaceBiomeManager = new BiomeManager(
            this::surfaceBiome,
            BiomeManager.obfuscateSeed(seed));
        this.carverBiomeManager = new BiomeManager(
            (quartX, quartY, quartZ) -> biomeSource.getNoiseBiome(quartX, quartY, quartZ, randomState.sampler()),
            BiomeManager.obfuscateSeed(seed));

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Glazed emerald terrain");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
        this.terrainExecutor = Executors.newSingleThreadExecutor(factory);
    }

    /** Null until {@link OverworldOre#get} has run, since it is what builds the registry. */
    public static SeedWorld create(long seed, RegistryAccess runtimeRegistry) {
        HolderLookup.Provider worldgenRegistry = OverworldOre.registry();
        ChunkGenerator chunkGenerator = OverworldOre.generator();
        LevelHeightAccessor heights = OverworldOre.heights();

        if (worldgenRegistry == null || !(chunkGenerator instanceof NoiseBasedChunkGenerator generator) || heights == null) return null;

        Holder<NoiseGeneratorSettings> noiseSettings = worldgenRegistry.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        RandomState randomState = RandomState.create(worldgenRegistry, NoiseGeneratorSettings.OVERWORLD, seed);
        List<Holder<Biome>> biomePalette = new ArrayList<>(generator.getBiomeSource().possibleBiomes());
        Holder<Biome> plains = worldgenRegistry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        if (biomePalette.stream().noneMatch(holder -> sameBiome(holder, plains))) biomePalette.add(plains);
        return new SeedWorld(seed, generator, randomState, heights, runtimeRegistry, noiseSettings, biomePalette);
    }

    /**
     * Vanilla's BiomeFilter asks WorldGenLevel.getBiome(BlockPos), which goes through
     * BiomeManager's seed-fiddled Voronoi lookup over the BIOMES-stage palette stored in each
     * ProtoChunk. Calling BiomeSource directly is not equivalent: vanilla fills that palette with
     * NoiseChunk.cachedClimateSampler, and the two sources disagree at real biome boundaries.
     */
    public ResourceKey<Biome> biome(int x, int y, int z) {
        Throwable failure = terrainFailure;
        if (failure != null) throw new IllegalStateException("Vanilla biome generation failed", failure);

        Holder<Biome> holder = featureBiomeManager.getBiome(new BlockPos(x, y, z));
        return holder.unwrapKey().orElse(null);
    }

    /** Missing palette work is surfaced to the finder without ever blocking its render thread. */
    public static final class GenerationPending extends RuntimeException {
        private final CompletableFuture<?> future;

        private GenerationPending(CompletableFuture<?> future) {
            super(null, null, false, false);
            this.future = future;
        }

        public CompletableFuture<?> future() {
            return future;
        }
    }

    /**
     * Exact feature-time OCEAN_FLOOR_WG, or null while its post-carver chunk is not ready.
     *
     * getBaseHeight is not equivalent here: a surface-opening carver can lower OCEAN_FLOOR_WG.
     * If that flips even one of emerald's 100 whole-vein gates, vanilla skips that attempt's
     * shape random calls and every later emerald coordinate moves. WorldGenRegion.getHeight(),
     * which OreFeature calls, returns ChunkAccess.getHeight() + 1; the cached values below use
     * precisely that contract.
     */
    public Integer oceanFloor(int x, int z) {
        int[] values = oceanFloors.get(ChunkPos.pack(x >> 4, z >> 4));
        return values == null ? null : values[(z & 15) << 4 | (x & 15)];
    }

    /**
     * Starts generation for the chunk containing {@code pos}. The future completes off the render
     * thread and is shared by every candidate in that chunk.
     */
    public CompletableFuture<ProtoChunk> prepareTerrain(BlockPos pos) {
        return prepareTerrain(ChunkPos.containing(pos));
    }

    public CompletableFuture<ProtoChunk> prepareTerrain(ChunkPos pos) {
        long key = pos.pack();
        ProtoChunk cached = terrain.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        Throwable failure = terrainFailure;
        if (failure != null) return CompletableFuture.failedFuture(failure);

        return terrainWork.computeIfAbsent(key, ignored -> CompletableFuture
            .supplyAsync(() -> generateTerrain(pos), terrainExecutor)
            .whenComplete((chunk, error) -> {
                terrainWork.remove(key);
                if (error == null) terrain.put(key, chunk);
                else terrainFailure = unwrap(error);
            }));
    }

    private CompletableFuture<BiomeSnapshot> prepareBiome(ChunkPos pos) {
        long key = pos.pack();
        BiomeSnapshot cached = biomeSnapshots.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        Throwable failure = terrainFailure;
        if (failure != null) return CompletableFuture.failedFuture(failure);

        return biomeWork.computeIfAbsent(key, ignored -> CompletableFuture
            .supplyAsync(() -> generateBiomeSnapshot(pos), terrainExecutor)
            .whenComplete((snapshot, error) -> {
                biomeWork.remove(key);
                if (error == null) biomeSnapshots.put(key, snapshot);
                else terrainFailure = unwrap(error);
            }));
    }

    /** Null means generation is still in flight. A recorded failure is thrown fail-closed. */
    public BlockState terrainBlock(BlockPos pos) {
        Throwable failure = terrainFailure;
        if (failure != null) throw new IllegalStateException("Vanilla terrain generation failed", failure);

        ProtoChunk chunk = terrain.get(ChunkPos.pack(pos));
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    public boolean terrainReady(BlockPos pos) {
        return terrain.containsKey(ChunkPos.pack(pos));
    }

    public Throwable terrainFailure() {
        return terrainFailure;
    }

    public int terrainChunks() {
        return terrain.size();
    }

    public int terrainPending() {
        return terrainWork.size() + biomeWork.size();
    }

    public void close() {
        terrainExecutor.shutdownNow();
        terrainWork.clear();
        biomeWork.clear();
        terrain.clear();
        oceanFloors.clear();
        biomeSnapshots.clear();
    }

    private ProtoChunk generateTerrain(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heights, containerFactory, null);
        StructureManager structures = new EmptyStructureManager();

        // This ordering is essential. createBiomes creates the NoiseChunk and fills the chunk's
        // palette with its cached climate sampler; fillFromNoise must then reuse that NoiseChunk.
        generator.createBiomes(randomState, Blender.empty(), structures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        cacheBiomeSnapshot(chunk);

        generator.fillFromNoise(Blender.empty(), randomState, structures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        generator.buildSurface(
            chunk,
            new WorldGenerationContext(generator, heights),
            randomState,
            structures,
            surfaceBiomeManager,
            registry.lookupOrThrow(Registries.BIOME),
            Blender.empty());
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        carve(chunk);

        // Rebuild from the final post-surface/post-carver block states before taking the snapshot.
        // EMPTY currently maintains the WG maps, but this standalone chunk is deliberately not
        // advanced by vanilla's status scheduler; an explicit prime makes that lifecycle detail
        // irrelevant and prevents a stale cave opening from moving emerald's random stream.
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG));
        cacheOceanFloor(chunk);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        return chunk;
    }

    private BiomeSnapshot generateBiomeSnapshot(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heights, containerFactory, null);
        generator.createBiomes(randomState, Blender.empty(), new EmptyStructureManager(), chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        return snapshotBiomes(chunk);
    }

    private void cacheBiomeSnapshot(ProtoChunk chunk) {
        biomeSnapshots.put(chunk.getPos().pack(), snapshotBiomes(chunk));
    }

    /** Compact 4x4x96 copy: about 3 KiB instead of retaining a whole biome-only ProtoChunk. */
    private BiomeSnapshot snapshotBiomes(ProtoChunk chunk) {
        int minQuartY = QuartPos.fromBlock(heights.getMinY());
        int quartHeight = QuartPos.fromBlock(heights.getHeight());
        short[] values = new short[4 * 4 * quartHeight];
        int baseQuartX = QuartPos.fromSection(chunk.getPos().x());
        int baseQuartZ = QuartPos.fromSection(chunk.getPos().z());

        for (int y = 0; y < quartHeight; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> holder = chunk.getNoiseBiome(baseQuartX + x, minQuartY + y, baseQuartZ + z);
                    ResourceKey<Biome> key = holder.unwrapKey().orElseThrow(
                        () -> new IllegalStateException("Generated an unregistered biome"));
                    Short id = biomeIds.get(key);
                    if (id == null) throw new IllegalStateException("Generated biome is outside BiomeSource possibleBiomes: " + key);
                    values[y * 16 + z * 4 + x] = id;
                }
            }
        }

        return new BiomeSnapshot(minQuartY, quartHeight, values);
    }

    private Holder<Biome> featureBiome(int quartX, int quartY, int quartZ) {
        ChunkPos pos = new ChunkPos(QuartPos.toSection(quartX), QuartPos.toSection(quartZ));
        BiomeSnapshot snapshot = biomeSnapshots.get(pos.pack());
        if (snapshot == null) throw new GenerationPending(prepareBiome(pos));
        return snapshot.get(quartX, quartY, quartZ, biomePalette);
    }

    /** Only called by the single terrain worker, so generating an edge palette here cannot stall UI. */
    private Holder<Biome> surfaceBiome(int quartX, int quartY, int quartZ) {
        ChunkPos pos = new ChunkPos(QuartPos.toSection(quartX), QuartPos.toSection(quartZ));
        long key = pos.pack();
        BiomeSnapshot snapshot = biomeSnapshots.get(key);
        if (snapshot == null) {
            snapshot = generateBiomeSnapshot(pos);
            biomeSnapshots.put(key, snapshot);
        }
        return snapshot.get(quartX, quartY, quartZ, biomePalette);
    }

    /** Snapshot the post-carver map before the large ProtoChunk can leave the LRU. */
    private void cacheOceanFloor(ProtoChunk chunk) {
        int[] values = new int[16 * 16];

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                values[z << 4 | x] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
            }
        }

        oceanFloors.put(chunk.getPos().pack(), values);
    }

    /**
     * NoiseBasedChunkGenerator.applyCarvers needs a ServerLevel-backed WorldGenRegion. The carver
     * loop itself does not: it only needs the target ProtoChunk, vanilla registries, biome source,
     * aquifer and seed. Keeping the loop here lets the client reproduce caves without fabricating
     * a server level.
     */
    private void carve(ProtoChunk chunk) {
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(unused -> {
            throw new IllegalStateException("Noise chunk disappeared after fillFromNoise");
        });
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext context = new CarvingContext(
            generator, registry, heights, noiseChunk, randomState, noiseSettings.value().surfaceRule());
        CarvingMask mask = chunk.getOrCreateCarvingMask();
        BiomeManager biomeManager = carverBiomeManager;
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        ChunkPos target = chunk.getPos();

        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos source = new ChunkPos(target.x() + dx, target.z() + dz);
                Holder<Biome> sourceBiome = biomeSource.getNoiseBiome(
                    QuartPos.fromBlock(source.getMinBlockX()), 0,
                    QuartPos.fromBlock(source.getMinBlockZ()), randomState.sampler());
                BiomeGenerationSettings generation = sourceBiome.value().getGenerationSettings();
                int index = 0;

                for (Holder<ConfiguredWorldCarver<?>> carverHolder : generation.getCarvers()) {
                    ConfiguredWorldCarver<?> carver = carverHolder.value();
                    random.setLargeFeatureSeed(seed + index, source.x(), source.z());
                    if (carver.isStartChunk(random)) {
                        carver.carve(context, chunk, biomeManager::getBiome, random, aquifer, source, mask);
                    }
                    index++;
                }
            }
        }
    }

    private record BiomeSnapshot(int minQuartY, int quartHeight, short[] values) {
        private Holder<Biome> get(int quartX, int quartY, int quartZ, List<Holder<Biome>> palette) {
            int y = Math.max(0, Math.min(quartHeight - 1, quartY - minQuartY));
            int index = y * 16 + QuartPos.quartLocal(quartZ) * 4 + QuartPos.quartLocal(quartX);
            return palette.get(Short.toUnsignedInt(values[index]));
        }
    }

    private static Map<ResourceKey<Biome>, Short> buildBiomeIds(List<Holder<Biome>> palette) {
        if (palette.size() > 65_536) throw new IllegalStateException("Too many possible biomes: " + palette.size());

        Map<ResourceKey<Biome>, Short> result = new HashMap<>();
        for (int index = 0; index < palette.size(); index++) {
            ResourceKey<Biome> key = palette.get(index).unwrapKey().orElseThrow(
                () -> new IllegalStateException("BiomeSource contains an unregistered biome"));
            result.put(key, (short) index);
        }
        return Map.copyOf(result);
    }

    private static PalettedContainerFactory createContainerFactory(List<Holder<Biome>> palette) {
        IdMapper<Holder<Biome>> biomeIds = new IdMapper<>(palette.size());
        for (Holder<Biome> biome : palette) biomeIds.add(biome);

        return new PalettedContainerFactory(
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
            Blocks.AIR.defaultBlockState(),
            null,
            Strategy.createForBiomes(biomeIds),
            palette.get(0),
            null);
    }

    private static boolean sameBiome(Holder<Biome> first, Holder<Biome> second) {
        return first.unwrapKey().equals(second.unwrapKey());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    /** Structures are intentionally absent from candidate terrain; deep emeralds do not need them. */
    private static final class EmptyStructureManager extends StructureManager {
        private EmptyStructureManager() {
            super(null, null, null);
        }

        @Override
        public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> matcher) {
            return List.of();
        }
    }
}
