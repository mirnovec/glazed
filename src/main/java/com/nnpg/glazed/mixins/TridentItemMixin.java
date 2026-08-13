package com.nnpg.glazed.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.nnpg.glazed.modules.main.GodTrident;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(TridentItem.class)
public abstract class TridentItemMixin {

    @ModifyExpressionValue(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean glazed$allowChargeOutOfWater(boolean original) {
        return dry() || original;
    }

    @ModifyExpressionValue(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean glazed$allowLaunchOutOfWater(boolean original) {
        return dry() || original;
    }

    private static boolean dry() {
        GodTrident trident = GodTrident.get();
        return trident != null && trident.isActive() && trident.noWater.get();
    }

    @ModifyConstant(method = "releaseUsing", constant = @Constant(intValue = 10))
    private int glazed$modifyMinCharge(int original) {
        GodTrident trident = GodTrident.get();
        if (trident == null || !trident.isActive()) return original;

        return Math.max(1, (int) Math.round(original * trident.scale.get()));
    }
}
