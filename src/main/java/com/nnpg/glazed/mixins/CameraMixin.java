package com.nnpg.glazed.mixins;

import com.nnpg.glazed.modules.main.GlazedFreecam;
import com.nnpg.glazed.modules.main.GlazedFreelook;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public class CameraMixin {

    @Shadow
    private boolean detached;

    // sus
    @Inject(method = "setup", at = @At("HEAD"))
    private void glazed$stepFreecam(BlockGetter area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);
        if (freecam != null && freecam.isActive()) freecam.onGameRender();
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void glazed$showBody(BlockGetter area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);
        if (freecam != null && freecam.isActive()) detached = true;
    }

    @ModifyArgs(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void glazed$setPos(Args args) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);
        if (freecam == null || !freecam.isActive()) return;

        args.set(0, freecam.getInterpolatedX(0.0f));
        args.set(1, freecam.getInterpolatedY(0.0f));
        args.set(2, freecam.getInterpolatedZ(0.0f));
    }

    @ModifyArgs(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void glazed$setRotation(Args args) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);

        if (freecam != null && freecam.isActive()) {
            args.set(0, (float) freecam.getInterpolatedYaw(0.0f));
            args.set(1, (float) freecam.getInterpolatedPitch(0.0f));
            return;
        }

        GlazedFreelook freelook = Modules.get().get(GlazedFreelook.class);

        if (freelook != null && freelook.isActive()) {
            args.set(0, (float) freelook.getInterpolatedYaw(0.0f));
            args.set(1, (float) freelook.getInterpolatedPitch(0.0f));
        }
    }

    // aye
    @ModifyVariable(method = "getMaxZoom", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float glazed$noPullback(float distance) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);
        return freecam != null && freecam.isActive() ? 0.0f : distance;
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void glazed$freelookNoClip(float desiredDistance, CallbackInfoReturnable<Float> cir) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);
        if (freecam != null && freecam.isActive()) return;

        GlazedFreelook freelook = Modules.get().get(GlazedFreelook.class);
        if (freelook != null && freelook.isActive() && freelook.allowCameraNoClip()) cir.setReturnValue(desiredDistance);
    }
}
