package com.realwater;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/** A bucket that pours a fixed amount of particle water where you look. Reusable. */
public class ParticleBucketItem extends Item {
    private final int amount;

    public ParticleBucketItem(Settings settings, int amount) {
        super(settings);
        this.amount = amount;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        BlockHitResult hit = raycast(world, user, RaycastContext.FluidHandling.NONE);
        if (hit.getType() == HitResult.Type.MISS) return TypedActionResult.pass(stack);

        if (world.isClient && RealWater.pourHandler != null) {
            BlockPos target = hit.getBlockPos().offset(hit.getSide());
            RealWater.pourHandler.pour(target.getX() + 0.5, target.getY() + 0.1, target.getZ() + 0.5, amount);
        }
        user.getItemCooldownManager().set(this, 10);
        return TypedActionResult.success(stack, world.isClient());
    }
}
