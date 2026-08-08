package com.nnpg.glazed.mixins;

import com.nnpg.glazed.modules.main.FakePay;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

    // ugh
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void glazed$onSendChatCommand(String command, CallbackInfo ci) {
        FakePay fakePay = Modules.get().get(FakePay.class);
        if (fakePay != null && fakePay.isActive() && fakePay.handlePay(command)) ci.cancel();
    }
}
