package com.realwater;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;

/** Simulacion ligera de fluido por particulas (estilo SPH simplificado). */
public final class WaterSim {
    public static final int MAX = 1500;
    private static final int MAX_AGE = 400;
    private static final float H = 0.5f;
    private static final int TABLE = 4096;
    private static final int DEAD = Integer.MAX_VALUE;

    public static int count = 0;
    public static final float[] px = new float[MAX], py = new float[MAX], pz = new float[MAX];
    public static final float[] ox = new float[MAX], oy = new float[MAX], oz = new float[MAX];
    private static final float[] vx = new float[MAX], vy = new float[MAX], vz = new float[MAX];
    private static final int[] age = new int[MAX], rest = new int[MAX];
    private static final int[] head = new int[TABLE];
    private static final int[] next = new int[MAX];
    private static final int[] seen = new int[27];
    private static final BlockPos.Mutable pos = new BlockPos.Mutable();

    private WaterSim() {}

    public static void clear() { count = 0; }

    public static void emit(double x, double y, double z, double dx, double dy, double dz) {
        if (count >= MAX) return;
        int i = count++;
        px[i] = ox[i] = (float) x; py[i] = oy[i] = (float) y; pz[i] = oz[i] = (float) z;
        vx[i] = (float) dx; vy[i] = (float) dy; vz[i] = (float) dz;
        age[i] = 0; rest[i] = 0;
    }

    private static int bucket(int cx, int cy, int cz) {
        return ((cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791)) & (TABLE - 1);
    }

    private static boolean solid(ClientWorld w, float x, float y, float z) {
        pos.set(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        BlockState s = w.getBlockState(pos);
        return !s.getCollisionShape(w, pos).isEmpty();
    }

    public static void tick(ClientWorld w) {
        if (count == 0) return;
        Arrays.fill(head, -1);
        for (int i = 0; i < count; i++) {
            ox[i] = px[i]; oy[i] = py[i]; oz[i] = pz[i];
            int b = bucket(MathHelper.floor(px[i] / H), MathHelper.floor(py[i] / H), MathHelper.floor(pz[i] / H));
            next[i] = head[b];
            head[b] = i;
        }

        // Fuerzas entre particulas: presion, cohesion y viscosidad.
        for (int i = 0; i < count; i++) {
            int cx = MathHelper.floor(px[i] / H), cy = MathHelper.floor(py[i] / H), cz = MathHelper.floor(pz[i] / H);
            int ns = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int b = bucket(cx + dx, cy + dy, cz + dz);
                boolean dup = false;
                for (int k = 0; k < ns; k++) if (seen[k] == b) { dup = true; break; }
                if (dup) continue;
                seen[ns++] = b;
                for (int j = head[b]; j != -1; j = next[j]) {
                    if (j == i) continue;
                    float ddx = px[i] - px[j], ddy = py[i] - py[j], ddz = pz[i] - pz[j];
                    float d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d2 >= H * H || d2 < 1e-8f) continue;
                    float d = (float) Math.sqrt(d2);
                    float q = 1f - d / H;
                    float push = q * q * 0.06f / d;
                    vx[i] += ddx * push; vy[i] += ddy * push; vz[i] += ddz * push;
                    if (q < 0.4f) {
                        float pull = 0.01f * (0.4f - q) / d;
                        vx[i] -= ddx * pull; vy[i] -= ddy * pull; vz[i] -= ddz * pull;
                    }
                    float visc = 0.04f * q;
                    vx[i] += (vx[j] - vx[i]) * visc;
                    vy[i] += (vy[j] - vy[i]) * visc;
                    vz[i] += (vz[j] - vz[i]) * visc;
                }
            }
        }

        // Movimiento y colision con bloques.
        for (int i = 0; i < count; i++) {
            vy[i] -= 0.045f;
            vx[i] *= 0.995f; vy[i] *= 0.995f; vz[i] *= 0.995f;
            float sp2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (sp2 > 1.44f) {
                float s = 1.2f / (float) Math.sqrt(sp2);
                vx[i] *= s; vy[i] *= s; vz[i] *= s;
            }
            float nx = px[i] + vx[i];
            if (solid(w, nx, py[i], pz[i])) vx[i] *= -0.15f; else px[i] = nx;
            float ny = py[i] + vy[i];
            if (solid(w, px[i], ny, pz[i])) {
                if (vy[i] < 0) { vx[i] *= 0.85f; vz[i] *= 0.85f; }
                vy[i] *= -0.1f;
            } else py[i] = ny;
            float nz = pz[i] + vz[i];
            if (solid(w, px[i], py[i], nz)) vz[i] *= -0.15f; else pz[i] = nz;

            boolean dead = false;
            pos.set(MathHelper.floor(px[i]), MathHelper.floor(py[i]), MathHelper.floor(pz[i]));
            if (!w.getFluidState(pos).isEmpty() || py[i] < w.getBottomY()) dead = true;
            float sp = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (sp < 0.0006f) rest[i]++; else rest[i] = 0;
            if (rest[i] > 60 || ++age[i] > MAX_AGE) dead = true;
            if (dead) age[i] = DEAD;
        }

        // Compactar: quitar particulas muertas.
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (age[i] == DEAD) continue;
            if (n != i) {
                px[n] = px[i]; py[n] = py[i]; pz[n] = pz[i];
                ox[n] = ox[i]; oy[n] = oy[i]; oz[n] = oz[i];
                vx[n] = vx[i]; vy[n] = vy[i]; vz[n] = vz[i];
                age[n] = age[i]; rest[n] = rest[i];
            }
            n++;
        }
        count = n;
    }

    public static float speed(int i) {
        return (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
    }
}
