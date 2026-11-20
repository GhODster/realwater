package com.realwater;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class WaterRenderer {
    private static final float SIZE = 0.07f;

    private WaterRenderer() {}

    public static void render(WorldRenderContext ctx) {
        if (WaterSim.count == 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();

        Camera cam = ctx.camera();
        Vec3d cp = cam.getPos();
        float pt = mc.getRenderTickCounter().getTickDelta(false);
        MatrixStack ms = ctx.matrixStack();
        ms.push();
        ms.translate(-cp.x, -cp.y, -cp.z);
        Matrix4f m = ms.peek().getPositionMatrix();

        Quaternionf rot = cam.getRotation();
        Vector3f right = new Vector3f(1, 0, 0).rotate(rot).mul(SIZE);
        Vector3f up = new Vector3f(0, 1, 0).rotate(rot).mul(SIZE);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int n = WaterSim.count;
        for (int i = 0; i < n; i++) {
            float x = WaterSim.ox[i] + (WaterSim.px[i] - WaterSim.ox[i]) * pt;
            float y = WaterSim.oy[i] + (WaterSim.py[i] - WaterSim.oy[i]) * pt;
            float z = WaterSim.oz[i] + (WaterSim.pz[i] - WaterSim.oz[i]) * pt;

            float light = WaterSim.light[i];
            float foam = Math.min(1f, WaterSim.speed(i) * 1.5f) * 0.6f;
            int r = (int) ((50 + (255 - 50) * foam) * light);
            int g = (int) ((120 + (255 - 120) * foam) * light);
            int b = (int) ((220 + (255 - 220) * foam) * light);
            int a = 160;

            float rx = right.x, ry = right.y, rz = right.z;
            float ux = up.x, uy = up.y, uz = up.z;
            buf.vertex(m, x - rx - ux, y - ry - uy, z - rz - uz).color(r, g, b, a);
            buf.vertex(m, x + rx - ux, y + ry - uy, z + rz - uz).color(r, g, b, a);
            buf.vertex(m, x + rx + ux, y + ry + uy, z + rz + uz).color(r, g, b, a);
            buf.vertex(m, x - rx + ux, y - ry + uy, z - rz + uz).color(r, g, b, a);
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        ms.pop();
    }
}
