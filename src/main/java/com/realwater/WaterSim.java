package com.realwater;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Simulacion de fluido por particulas (SPH simplificado).
 * - Las particulas son permanentes (solo se borran con la tecla H o al caer fuera del mundo).
 * - Al llegar al limite se reciclan las mas viejas.
 * - Las particulas quietas "se duermen" y casi no cuestan CPU.
 */
public final class WaterSim {
    public static final int CAP = 25000;
    public static final int[] LIMITS = {3000, 8000, 15000, 25000};
    public static int limitIndex = 1;
    public static int limit = LIMITS[1];

    // Las particulas miden 0.10 de ancho (SIZE 0.05 en el renderer).
    private static final float H_REP = 0.105f;   // radio de repulsion (solo al tocarse)
    private static final float MIN_D = 0.095f;   // distancia minima: nunca se meten una en otra
    private static final float ADH = 0.15f;      // radio de adherencia (velocidad compartida al caer)
    private static final int TABLE = 32768;
    private static final int MAX_NEIGHBORS = 40;
    private static final int SLEEP_TICKS = 40;
    private static final float SLEEP_SPEED2 = 0.0005f;
    private static final int MAX_ENTITIES = 32;
    private static final int DEAD = Integer.MAX_VALUE;
    private static final Random RNG = new Random();

    public static int count = 0;
    public static final float[] px = new float[CAP], py = new float[CAP], pz = new float[CAP];
    public static final float[] ox = new float[CAP], oy = new float[CAP], oz = new float[CAP];
    public static final int[] col = new int[CAP];
    private static final float[] vx = new float[CAP], vy = new float[CAP], vz = new float[CAP];
    private static final int[] age = new int[CAP], rest = new int[CAP];
    private static final boolean[] onBlock = new boolean[CAP];
    private static final int[] head = new int[TABLE];
    private static final int[] next = new int[CAP];
    private static final int[] seen = new int[27];
    private static final BlockPos.Mutable pos = new BlockPos.Mutable();
    private static int cursor = 0;
    private static int tickCounter = 0;

    private static final float[] eMinX = new float[MAX_ENTITIES], eMaxX = new float[MAX_ENTITIES];
    private static final float[] eMinY = new float[MAX_ENTITIES], eMaxY = new float[MAX_ENTITIES];
    private static final float[] eMinZ = new float[MAX_ENTITIES], eMaxZ = new float[MAX_ENTITIES];
    private static final float[] eVx = new float[MAX_ENTITIES], eVz = new float[MAX_ENTITIES];
    private static final boolean[] eSelf = new boolean[MAX_ENTITIES];
    private static int eCount = 0;

    private WaterSim() {}

    public static void clear() { count = 0; cursor = 0; }

    public static int cycleLimit() {
        limitIndex = (limitIndex + 1) % LIMITS.length;
        limit = LIMITS[limitIndex];
        if (count > limit) count = limit;
        return limit;
    }

    private static int color(float light, float foam) {
        int r = (int) ((45 + (255 - 45) * foam) * light);
        int g = (int) ((115 + (255 - 115) * foam) * light);
        int b = (int) ((215 + (255 - 215) * foam) * light);
        return (r << 16) | (g << 8) | b;
    }

    public static void emit(double x, double y, double z, double dx, double dy, double dz) {
        int i;
        if (count < limit) {
            i = count++;
        } else {
            if (count == 0) return;
            i = cursor % count;          // reciclar la mas vieja
            cursor = (i + 1) % count;
        }
        px[i] = ox[i] = (float) x; py[i] = oy[i] = (float) y; pz[i] = oz[i] = (float) z;
        vx[i] = (float) dx; vy[i] = (float) dy; vz[i] = (float) dz;
        age[i] = 0; rest[i] = 0; onBlock[i] = false;
        col[i] = color(1f, 0.4f);
    }

    private static int bucket(int cx, int cy, int cz) {
        return ((cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791)) & (TABLE - 1);
    }

    private static boolean solid(ClientWorld w, float x, float y, float z) {
        pos.set(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        BlockState s = w.getBlockState(pos);
        return !s.getCollisionShape(w, pos).isEmpty();
    }

    private static void gatherEntities(ClientWorld w, PlayerEntity self) {
        eCount = 0;
        Box area = self.getBoundingBox().expand(24);
        List<LivingEntity> list = w.getEntitiesByClass(LivingEntity.class, area, e -> true);
        for (LivingEntity e : list) {
            if (eCount >= MAX_ENTITIES) break;
            Box b = e.getBoundingBox();
            int k = eCount++;
            eMinX[k] = (float) b.minX - 0.03f; eMaxX[k] = (float) b.maxX + 0.03f;
            eMinY[k] = (float) b.minY - 0.03f; eMaxY[k] = (float) b.maxY + 0.03f;
            eMinZ[k] = (float) b.minZ - 0.03f; eMaxZ[k] = (float) b.maxZ + 0.03f;
            eVx[k] = (float) e.getVelocity().x; eVz[k] = (float) e.getVelocity().z;
            eSelf[k] = e == self;
        }
    }

    /** Choque con entidades. Devuelve true si la particula toco alguna. */
    private static boolean collideEntities(int i) {
        boolean hit = false;
        for (int e = 0; e < eCount; e++) {
            if (eSelf[e] && age[i] < 15) continue;
            if (px[i] < eMinX[e] || px[i] > eMaxX[e] || py[i] < eMinY[e] || py[i] > eMaxY[e]
                    || pz[i] < eMinZ[e] || pz[i] > eMaxZ[e]) continue;
            hit = true;
            if (py[i] > eMaxY[e] - 0.25f) {
                py[i] = eMaxY[e] + 0.02f;
                vy[i] = Math.abs(vy[i]) * 0.1f;
            } else {
                float cx = (eMinX[e] + eMaxX[e]) * 0.5f, cz = (eMinZ[e] + eMaxZ[e]) * 0.5f;
                float dx = px[i] - cx, dz = pz[i] - cz;
                float d = (float) Math.sqrt(dx * dx + dz * dz) + 1e-4f;
                float half = (eMaxX[e] - eMinX[e]) * 0.5f + 0.03f;
                px[i] = cx + dx / d * half;
                pz[i] = cz + dz / d * half;
                vx[i] += dx / d * 0.15f + eVx[e] * 0.6f;
                vz[i] += dz / d * 0.15f + eVz[e] * 0.6f;
                vy[i] += 0.03f;
            }
        }
        return hit;
    }

    public static void tick(ClientWorld w, PlayerEntity self) {
        if (count == 0) return;
        tickCounter++;
        gatherEntities(w, self);

        Arrays.fill(head, -1);
        for (int i = 0; i < count; i++) {
            ox[i] = px[i]; oy[i] = py[i]; oz[i] = pz[i];
            int b = bucket(MathHelper.floor(px[i] / ADH), MathHelper.floor(py[i] / ADH), MathHelper.floor(pz[i] / ADH));
            next[i] = head[b];
            head[b] = i;
        }

        // Fuerzas: repulsion al tocarse, separacion dura (sin solaparse) y adherencia por velocidad.
        // NO hay atraccion a distancia: las particulas no se mueven solas para juntarse.
        for (int i = 0; i < count; i++) {
            if (rest[i] >= SLEEP_TICKS) continue;
            float spi = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            int cx = MathHelper.floor(px[i] / ADH), cy = MathHelper.floor(py[i] / ADH), cz = MathHelper.floor(pz[i] / ADH);
            int ns = 0, neigh = 0;
            float sx = 0, sy = 0, sz = 0;
            scan:
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
                    if (d2 >= ADH * ADH || d2 < 1e-8f) continue;
                    if (++neigh > MAX_NEIGHBORS) break scan;
                    boolean jAsleep = rest[j] >= SLEEP_TICKS;
                    if (jAsleep && spi > 0.004f) rest[j] = 0;   // despertar vecina
                    float d = (float) Math.sqrt(d2);

                    if (d < H_REP) {                              // empuje suave al tocarse
                        float q = 1f - d / H_REP;
                        float push = q * 0.04f / d;
                        vx[i] += ddx * push; vy[i] += ddy * push; vz[i] += ddz * push;
                    }
                    if (d < MIN_D) {                              // separacion dura: nunca solapadas
                        float overlap = MIN_D - d;
                        float corr = overlap * (jAsleep ? 1.0f : 0.5f) / d;
                        sx += ddx * corr; sy += ddy * corr; sz += ddz * corr;
                        if (Math.abs(ddx) + Math.abs(ddz) < 0.3f * d) {   // columna vertical: tumbarla
                            sx += (RNG.nextFloat() - 0.5f) * overlap * 0.6f;
                            sz += (RNG.nextFloat() - 0.5f) * overlap * 0.6f;
                        }
                    }
                    float visc = 0.12f * (1f - d / ADH);          // adherencia: se mueven juntas al caer
                    vx[i] += (vx[j] - vx[i]) * visc;
                    vy[i] += (vy[j] - vy[i]) * visc;
                    vz[i] += (vz[j] - vz[i]) * visc;
                }
            }
            if (sx != 0 || sy != 0 || sz != 0) {
                float m2 = sx * sx + sy * sy + sz * sz;
                if (m2 > 0.0025f) { float k = 0.05f / (float) Math.sqrt(m2); sx *= k; sy *= k; sz *= k; }
                if (!solid(w, px[i] + sx, py[i] + sy, pz[i] + sz)) {
                    px[i] += sx; py[i] += sy; pz[i] += sz;
                }
            }
        }

        boolean anyDead = false;
        int n0 = count;
        for (int i = 0; i < n0; i++) {
            if (rest[i] >= SLEEP_TICKS) {
                // Dormida: solo revisar entidades y, de vez en cuando, el bloque de abajo y la luz.
                if (collideEntities(i)) { rest[i] = 0; continue; }
                if ((i + tickCounter) % 10 == 0) {
                    if (onBlock[i] && !solid(w, px[i], py[i] - 0.12f, pz[i])) { rest[i] = 0; continue; }
                    pos.set(MathHelper.floor(px[i]), MathHelper.floor(py[i]), MathHelper.floor(pz[i]));
                    col[i] = color(0.35f + 0.65f * (w.getLightLevel(pos) / 15f), 0f);
                }
                continue;
            }

            vy[i] -= 0.045f;
            vx[i] *= 0.99f; vy[i] *= 0.99f; vz[i] *= 0.99f;
            float sp2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (sp2 > 0.81f) {
                float s = 0.9f / (float) Math.sqrt(sp2);
                vx[i] *= s; vy[i] *= s; vz[i] *= s;
            }
            float nx = px[i] + vx[i];
            if (solid(w, nx, py[i], pz[i])) { vx[i] *= -0.15f; vy[i] *= 0.92f; } else px[i] = nx;
            float ny = py[i] + vy[i];
            if (solid(w, px[i], ny, pz[i])) {
                float impact = vy[i];
                if (impact < 0) { vx[i] *= 0.96f; vz[i] *= 0.96f; }
                vy[i] *= -0.1f;
                if (impact < -0.5f && count < limit - 50 && RNG.nextFloat() < 0.35f) {
                    for (int k = 0; k < 2; k++) {
                        emit(px[i], py[i] + 0.05, pz[i],
                                RNG.nextGaussian() * 0.08, -impact * 0.35 + RNG.nextDouble() * 0.08,
                                RNG.nextGaussian() * 0.08);
                    }
                }
            } else py[i] = ny;
            float nz = pz[i] + vz[i];
            if (solid(w, px[i], py[i], nz)) { vz[i] *= -0.15f; vy[i] *= 0.92f; } else pz[i] = nz;

            collideEntities(i);

            pos.set(MathHelper.floor(px[i]), MathHelper.floor(py[i]), MathHelper.floor(pz[i]));
            if (py[i] < w.getBottomY()) {
                age[i] = DEAD; anyDead = true; continue;
            }
            if (!w.getFluidState(pos).isEmpty()) {   // en agua normal: mas frenado
                vx[i] *= 0.85f; vy[i] *= 0.85f; vz[i] *= 0.85f;
            }
            float sp = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (sp < SLEEP_SPEED2) rest[i]++; else rest[i] = 0;
            if (rest[i] >= SLEEP_TICKS) {
                vx[i] = 0; vy[i] = 0; vz[i] = 0;
                onBlock[i] = solid(w, px[i], py[i] - 0.12f, pz[i]);
            }
            if (age[i] < 1000) age[i]++;
            float foam = Math.min(1f, (float) Math.sqrt(sp) * 1.5f) * 0.6f;
            col[i] = color(0.35f + 0.65f * (w.getLightLevel(pos) / 15f), foam);
        }

        if (anyDead) {
            int n = 0;
            for (int i = 0; i < count; i++) {
                if (age[i] == DEAD) continue;
                if (n != i) {
                    px[n] = px[i]; py[n] = py[i]; pz[n] = pz[i];
                    ox[n] = ox[i]; oy[n] = oy[i]; oz[n] = oz[i];
                    vx[n] = vx[i]; vy[n] = vy[i]; vz[n] = vz[i];
                    age[n] = age[i]; rest[n] = rest[i]; col[n] = col[i]; onBlock[n] = onBlock[i];
                }
                n++;
            }
            count = n;
            if (cursor >= count) cursor = 0;
        }
    }
}
