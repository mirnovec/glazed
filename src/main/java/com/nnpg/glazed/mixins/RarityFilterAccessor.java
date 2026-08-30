package com.nnpg.glazed.mixins;

import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RarityFilter.class)
public interface RarityFilterAccessor {
    // int here, unlike the float this is often written as on older versions
    @Accessor("chance")
    int getChance();
}
