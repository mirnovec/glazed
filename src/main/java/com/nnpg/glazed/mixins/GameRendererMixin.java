package com.nnpg.glazed.mixins;

import com.nnpg.glazed.modules.main.GlazedFreecam;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    // duh
    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void glazed$hideOutline(CallbackInfoReturnable<Boolean> cir) {
        GlazedFreecam freecam = Modules.get().get(GlazedFreecam.class);
        if (freecam != null && freecam.isActive()) cir.setReturnValue(false);
    }
}
