package com.realwater.mixin;

import net.minecraft.fluid.WaterFluid;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WaterFluid.class)
public class WaterFluidMixin {
    // Vanilla: 5 ticks. Aqui 2 => el agua cae y se esparce mas del doble de rapido.
    @Inject(method = "getTickRate", at = @At("HEAD"), cancellable = true)
    private void rw$tickRate(WorldView world, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(2);
    }

    // Vanilla: 4 bloques. Aqui 6 => el agua se expande mas lejos por el suelo.
    @Inject(method = "getMaxFlowDistance", at = @At("HEAD"), cancellable = true)
    private void rw$flowDistance(WorldView world, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(6);
    }

    // Sin fuentes infinitas: el agua se agota.
    @Inject(method = "isInfinite", at = @At("HEAD"), cancellable = true)
    private void rw$noInfinite(World world, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
