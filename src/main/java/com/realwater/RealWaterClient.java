package com.realwater;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class RealWaterClient implements ClientModInitializer {
    private static KeyBinding spray;
    private static KeyBinding clear;
    private static final Random RNG = new Random();

    @Override
    public void onInitializeClient() {
        spray = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.realwater.spray", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.realwater"));
        clear = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.realwater.clear", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.realwater"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) {
                WaterSim.clear();
                return;
            }
            if (client.isPaused()) return;
            while (clear.wasPressed()) WaterSim.clear();
            if (spray.isPressed()) emit(client.player);
            WaterSim.tick(client.world);
        });

        WorldRenderEvents.LAST.register(WaterRenderer::render);
    }

    private static void emit(PlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d vel = p.getVelocity();
        for (int i = 0; i < 10; i++) {
            WaterSim.emit(
                    eye.x + look.x * 0.8, eye.y - 0.2 + look.y * 0.8, eye.z + look.z * 0.8,
                    look.x * 0.6 + vel.x * 0.5 + RNG.nextGaussian() * 0.03,
                    look.y * 0.6 + vel.y * 0.5 + RNG.nextGaussian() * 0.03,
                    look.z * 0.6 + vel.z * 0.5 + RNG.nextGaussian() * 0.03);
        }
    }
}
