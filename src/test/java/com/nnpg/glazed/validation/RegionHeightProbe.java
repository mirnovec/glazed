package com.nnpg.glazed.validation;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.server.Bootstrap;

import java.io.DataInputStream;
import java.nio.file.Path;

public final class RegionHeightProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ChunkPos chunk = new ChunkPos(-13085, -11948);
        Path regionDir = Path.of("build/ore-validation-server/world/dimensions/minecraft/overworld/region");
        Path path = regionDir.resolve("r." + (chunk.x() >> 5) + "." + (chunk.z() >> 5) + ".mca");
        RegionStorageInfo info = new RegionStorageInfo("world", Level.OVERWORLD, "chunk");

        try (RegionFile region = new RegionFile(info, path, regionDir, true);
             DataInputStream input = region.getChunkDataInputStream(chunk)) {
            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            CompoundTag maps = root.getCompoundOrEmpty("Heightmaps");
            System.out.println("root=" + root.keySet());
            System.out.println("heightmaps=" + maps.keySet());
            long[] packed = maps.getLongArray("OCEAN_FLOOR_WG").orElseThrow();
            SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(385), 256, packed);
            System.out.println("heightmaps=" + maps.keySet() + " longs=" + packed.length);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int featureHeight = -64 + storage.get(x + z * 16);
                    System.out.printf("%d,%d=%d%n", chunk.getMinBlockX() + x, chunk.getMinBlockZ() + z, featureHeight);
                }
            }
        }
    }
}
