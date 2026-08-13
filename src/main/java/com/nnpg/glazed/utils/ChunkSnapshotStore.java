package com.nnpg.glazed.utils;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChunkSnapshotStore {
    public static final int MIN_SECTION = 6;
    public static final int MAX_SECTION = 23;

    private static final int AIR_STATE = Block.getId(Blocks.AIR.defaultBlockState());

    private final Map<Long, Map<Integer, int[]>> snapshots = new HashMap<>();

    public record BlockChange(int worldX, int worldY, int worldZ, int newState, int oldState) {
    }

    public static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (long) chunkZ & 0xFFFFFFFFL;
    }

    public void store(int chunkX, int chunkZ, int[][] sections) {
        Map<Integer, int[]> filtered = new HashMap<>();

        for (int s = MIN_SECTION; s <= MAX_SECTION && s < sections.length; s++) {
            if (sections[s] != null) filtered.put(s, sections[s].clone());
        }

        snapshots.put(key(chunkX, chunkZ), filtered);
    }

    public void remove(int chunkX, int chunkZ) {
        snapshots.remove(key(chunkX, chunkZ));
    }

    public void clear() {
        snapshots.clear();
    }

    public int size() {
        return snapshots.size();
    }

    public void retain(java.util.function.LongPredicate keep) {
        snapshots.keySet().removeIf(k -> !keep.test(k));
    }

    public List<BlockChange> compare(int chunkX, int chunkZ, int[][] newSections) {
        List<BlockChange> changes = new ArrayList<>();
        Map<Integer, int[]> previous = snapshots.get(key(chunkX, chunkZ));

        if (isEmpty(previous)) return changes;

        for (int s = MIN_SECTION; s <= MAX_SECTION; s++) {
            int[] before = previous.get(s);
            int[] after = s < newSections.length ? newSections[s] : null;

            if (before == null && after == null) continue;

            if (before != null && after != null) {
                if (Arrays.equals(before, after)) continue;

                for (int i = 0; i < before.length; i++) {
                    if (before[i] != after[i]) changes.add(toChange(chunkX, chunkZ, s, i, after[i], before[i]));
                }

                continue;
            }

            int[] present = before != null ? before : after;

            for (int i = 0; i < present.length; i++) {
                int oldState = before != null ? before[i] : 0;
                int newState = after != null ? after[i] : 0;

                if (oldState != newState) changes.add(toChange(chunkX, chunkZ, s, i, newState, oldState));
            }
        }

        return changes;
    }

    private BlockChange toChange(int chunkX, int chunkZ, int sectionIndex, int localIndex, int newState, int oldState) {
        int x = localIndex & 15;
        int z = localIndex >> 4 & 15;
        int y = localIndex >> 8 & 15;

        return new BlockChange(chunkX * 16 + x, (sectionIndex - 4) * 16 + y, chunkZ * 16 + z, newState, oldState);
    }

    private static boolean isEmpty(Map<Integer, int[]> sections) {
        if (sections == null) return true;

        for (int s = MIN_SECTION; s <= MAX_SECTION; s++) {
            int[] section = sections.get(s);
            if (section == null) continue;

            for (int state : section) {
                if (state != AIR_STATE) return false;
            }
        }

        return true;
    }
}
