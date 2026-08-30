package com.nnpg.glazed.validation;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.reflect.Field;

public final class RegistryProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        Object lookup = provider.lookupOrThrow(Registries.BIOME);
        System.out.println("provider=" + provider.getClass());
        System.out.println("lookup=" + lookup.getClass());
        System.out.println("overworld-carver-replaceables="
            + BuiltInRegistries.BLOCK.getTagOrEmpty(BlockTags.OVERWORLD_CARVER_REPLACEABLES).spliterator().getExactSizeIfKnown());
        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            System.out.println("status=" + status + " maps=" + status.heightmapsAfter());
        }
        dump(provider, "  ");
        dump(lookup, "  ");
    }

    private static void dump(Object object, String prefix) throws Exception {
        for (Field field : object.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(object);
            System.out.println(prefix + field.getName() + "=" + (value == null ? null : value.getClass()) + " " + value);
        }
    }
}
