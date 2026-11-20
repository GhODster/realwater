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
    private static final float SIZE = 0.05f;
    private static final float MAX_DIST2 = 72f * 72f;

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
        float rx = right.x, ry = right.y, rz = right.z;
        float ux = up.x, uy = up.y, uz = up.z;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int n = WaterSim.count;
        int drawn = 0;
        float cx = (float) cp.x, cy = (float) cp.y, cz = (float) cp.z;
        for (int i = 0; i < n; i++) {
            float x = WaterSim.ox[i] + (WaterSim.px[i] - WaterSim.ox[i]) * pt;
            float y = WaterSim.oy[i] + (WaterSim.py[i] - WaterSim.oy[i]) * pt;
            float z = WaterSim.oz[i] + (WaterSim.pz[i] - WaterSim.oz[i]) * pt;
            float dx = x - cx, dy = y - cy, dz = z - cz;
            if (dx * dx + dy * dy + dz * dz > MAX_DIST2) continue;

            int c = WaterSim.col[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int a = 120;
            buf.vertex(m, x - rx - ux, y - ry - uy, z - rz - uz).color(r, g, b, a);
            buf.vertex(m, x + rx - ux, y + ry - uy, z + rz - uz).color(r, g, b, a);
            buf.vertex(m, x + rx + ux, y + ry + uy, z + rz + uz).color(r, g, b, a);
            buf.vertex(m, x - rx + ux, y - ry + uy, z - rz + uz).color(r, g, b, a);
            drawn++;
        }
        if (drawn > 0) BufferRenderer.drawWithGlobalProgram(buf.end());
        else buf.endNullable();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        ms.pop();
    }
}
