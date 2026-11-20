package com.realwater.mixin;

import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WaterFluid.class)
public class WaterFluidClientMixin {
    // Se llama solo cerca del jugador y de forma aleatoria => muy barato.
    @Inject(method = "randomDisplayTick", at = @At("TAIL"))
    private void rw$splash(World world, BlockPos pos, FluidState state, Random random, CallbackInfo ci) {
        if (state.isStill()) return;
        boolean falling = state.get(FlowableFluid.FALLING);
        boolean belowIsWater = !world.getFluidState(pos.down()).isEmpty();
        double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;

        if (falling && !belowIsWater) {
            // Impacto de la cascada: salpicaduras hacia arriba.
            for (int i = 0; i < 3; i++) {
                world.addParticle(ParticleTypes.SPLASH,
                        x + (random.nextDouble() - 0.5) * 0.7, y + 0.1,
                        z + (random.nextDouble() - 0.5) * 0.7,
                        (random.nextDouble() - 0.5) * 0.1, 0.25 + random.nextDouble() * 0.15,
                        (random.nextDouble() - 0.5) * 0.1);
            }
        } else if (falling && random.nextInt(3) == 0) {
            // Gotas dentro de la columna de agua que cae.
            world.addParticle(ParticleTypes.FALLING_WATER,
                    x + (random.nextDouble() - 0.5) * 0.6, y + random.nextDouble(),
                    z + (random.nextDouble() - 0.5) * 0.6, 0, -0.1, 0);
        } else if (!falling && random.nextInt(8) == 0) {
            // Agua corriendo por el suelo: chispitas ocasionales.
            world.addParticle(ParticleTypes.SPLASH,
                    x + (random.nextDouble() - 0.5), y + state.getHeight(world, pos),
                    z + (random.nextDouble() - 0.5), 0, 0.05, 0);
        }
    }
}
