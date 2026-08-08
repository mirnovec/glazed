package com.nnpg.glazed.utils.glazed;

import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class BlockUtil {
    public static Stream<LevelChunk> getLoadedChunks() {
        if (mc.level == null || mc.player == null) return Stream.empty();

        int radius = Math.max(2, mc.options.getEffectiveRenderDistance());
        ChunkPos center = mc.player.chunkPosition();

        // this was a Stream.iterate with a throw in it if the limit was off by one
        // wtf was that. normal loop
        return IntStream.rangeClosed(center.x - radius, center.x + radius)
            .boxed()
            .flatMap(x -> IntStream.rangeClosed(center.z - radius, center.z + radius)
                .filter(z -> mc.level.hasChunk(x, z))
                .mapToObj(z -> mc.level.getChunk(x, z)))
            .filter(Objects::nonNull)
            // isChunkLoaded still gives you EmptyChunks and those read as pure air
            .filter(chunk -> !(chunk instanceof EmptyLevelChunk));
    }

    public static boolean isBlockAtPosition(final BlockPos blockPos, final Block block) {
        return mc.level != null && mc.level.getBlockState(blockPos).getBlock() == block;
    }

    public static boolean isRespawnAnchorCharged(final BlockPos blockPos) {
        return getRespawnAnchorCharges(blockPos) > 0;
    }

    public static boolean isRespawnAnchorUncharged(final BlockPos blockPos) {
        return getRespawnAnchorCharges(blockPos) == 0;
    }

    private static int getRespawnAnchorCharges(final BlockPos blockPos) {
        if (mc.level == null) return -1;

        BlockState state = mc.level.getBlockState(blockPos);
        if (state.getBlock() != Blocks.RESPAWN_ANCHOR) return -1;

        return state.getValue(RespawnAnchorBlock.CHARGE);
    }

    public static void interactWithBlock(final BlockHitResult blockHitResult, final boolean shouldSwingHand) {
        if (mc.gameMode == null || mc.player == null) return;

        final InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHitResult);
        if (result.consumesAction() && shouldSwingHand) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
