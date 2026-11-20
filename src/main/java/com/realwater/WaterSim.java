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
 * Particle fluid simulation (simplified SPH).
 * Every particle has a physical size (hitbox of 0.10 blocks): it can't overlap other particles
 * and it stops against blocks at its edge, not at its center.
 */
public final class WaterSim {
    public static final int CAP = 25000;
    public static final int[] LIMITS = {3000, 8000, 15000, 25000};
    public static int limit = 8000;

    public static final float R = 0.05f;          // particle radius (hitbox half size)
    private static final float H_REP = 0.11f;     // soft repulsion radius
    private static final float MIN_D = 0.10f;     // hard minimum distance = particle width
    private static final float ADH = 0.15f;       // adhesion radius (shared velocity)
    private static final float MAX_SPEED = 0.45f;
    private static final int TABLE = 32768;
    private static final int MAX_NEIGHBORS = 64;
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
    private static final boolean[] overlapped = new boolean[CAP];
    private static final int[] head = new int[TABLE];
    private static final int[] next = new int[CAP];
    private static final int[] seen = new int[27];
    private static final BlockPos.Mutable pos = new BlockPos.Mutable();
    private static int cursor = 0;
    private static int tickCounter = 0;

    // Water bucket pouring queue.
    private static int pourRemaining = 0;
    private static double pourX, pourY, pourZ;

    private static final float[] eMinX = new float[MAX_ENTITIES], eMaxX = new float[MAX_ENTITIES];
    private static final float[] eMinY = new float[MAX_ENTITIES], eMaxY = new float[MAX_ENTITIES];
    private static final float[] eMinZ = new float[MAX_ENTITIES], eMaxZ = new float[MAX_ENTITIES];
    private static final float[] eVx = new float[MAX_ENTITIES], eVz = new float[MAX_ENTITIES];
    private static final boolean[] eSelf = new boolean[MAX_ENTITIES];
    private static int eCount = 0;

    private WaterSim() {}

    public static void clear() { count = 0; cursor = 0; pourRemaining = 0; }

    public static int cycleLimit() {
        int nextLimit = LIMITS[0];
        for (int l : LIMITS) { if (l > limit) { nextLimit = l; break; } }
        limit = nextLimit;
        if (count > limit) count = limit;
        return limit;
    }

    /** Called by the water buckets: queues `amount` particles to be poured at this point. */
    public static void pour(double x, double y, double z, int amount) {
        pourRemaining += amount;
        pourX = x; pourY = y; pourZ = z;
        int needed = Math.min(CAP, amount);
        if (limit < needed) limit = needed;
    }

    private static void tickPour() {
        if (pourRemaining <= 0) return;
        int n = Math.min(pourRemaining, 120);
        for (int k = 0; k < n; k++) {
            double r = Math.sqrt(RNG.nextDouble()) * 0.45;
            double a = RNG.nextDouble() * Math.PI * 2;
            emit(pourX + Math.cos(a) * r, pourY + RNG.nextDouble() * 0.6, pourZ + Math.sin(a) * r,
                    RNG.nextGaussian() * 0.01, -0.3, RNG.nextGaussian() * 0.01);
        }
        pourRemaining -= n;
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
            i = cursor % count;          // recycle the oldest
            cursor = (i + 1) % count;
        }
        px[i] = ox[i] = (float) x; py[i] = oy[i] = (float) y; pz[i] = oz[i] = (float) z;
        vx[i] = (float) dx; vy[i] = (float) dy; vz[i] = (float) dz;
        age[i] = 0; rest[i] = 0; onBlock[i] = false; overlapped[i] = false;
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
            eMinX[k] = (float) b.minX - R; eMaxX[k] = (float) b.maxX + R;
            eMinY[k] = (float) b.minY - R; eMaxY[k] = (float) b.maxY + R;
            eMinZ[k] = (float) b.minZ - R; eMaxZ[k] = (float) b.maxZ + R;
            eVx[k] = (float) e.getVelocity().x; eVz[k] = (float) e.getVelocity().z;
            eSelf[k] = e == self;
        }
    }

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
                float half = (eMaxX[e] - eMinX[e]) * 0.5f + 0.01f;
                px[i] = cx + dx / d * half;
                pz[i] = cz + dz / d * half;
                vx[i] += dx / d * 0.12f + eVx[e] * 0.6f;
                vz[i] += dz / d * 0.12f + eVz[e] * 0.6f;
                vy[i] += 0.03f;
            }
        }
        return hit;
    }

    /** Pushes particle i out of any particle it overlaps. Returns true if there was overlap. */
    private static boolean separate(ClientWorld w, int i) {
        int cx = MathHelper.floor(px[i] / ADH), cy = MathHelper.floor(py[i] / ADH), cz = MathHelper.floor(pz[i] / ADH);
        int ns = 0, neigh = 0;
        float sx = 0, sy = 0, sz = 0;
        boolean any = false;
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
                if (d2 >= MIN_D * MIN_D) continue;
                if (++neigh > MAX_NEIGHBORS) break scan;
                any = true;
                if (d2 < 1e-8f) {      // exactly the same spot: random nudge
                    sx += (RNG.nextFloat() - 0.5f) * 0.06f;
                    sy += RNG.nextFloat() * 0.03f;
                    sz += (RNG.nextFloat() - 0.5f) * 0.06f;
                    continue;
                }
                float d = (float) Math.sqrt(d2);
                float overlap = MIN_D - d;
                float corr = overlap * (rest[j] >= SLEEP_TICKS ? 1.0f : 0.55f) / d;
                sx += ddx * corr; sy += ddy * corr; sz += ddz * corr;
                if (Math.abs(ddx) + Math.abs(ddz) < 0.3f * d) {   // vertical stack: topple it
                    sx += (RNG.nextFloat() - 0.5f) * overlap * 0.6f;
                    sz += (RNG.nextFloat() - 0.5f) * overlap * 0.6f;
                }
            }
        }
        if (any) {
            float m2 = sx * sx + sy * sy + sz * sz;
            if (m2 > 0.0064f) { float k = 0.08f / (float) Math.sqrt(m2); sx *= k; sy *= k; sz *= k; }
            if (!solid(w, px[i] + sx, py[i] + sy, pz[i] + sz)) {
                px[i] += sx; py[i] += sy; pz[i] += sz;
            }
        }
        return any;
    }

    /** Moves particle i by a fraction f of its velocity, colliding with blocks using its radius. */
    private static void moveStep(ClientWorld w, int i, float f) {
        float dx = vx[i] * f, dy = vy[i] * f, dz = vz[i] * f;

        float nx = px[i] + dx;
        if (solid(w, nx + (dx > 0 ? R : -R), py[i], pz[i])) { vx[i] *= -0.15f; vy[i] *= 0.92f; } else px[i] = nx;

        float ny = py[i] + dy;
        float offY = dy > 0 ? R : -R;
        if (solid(w, px[i], ny + offY, pz[i])) {
            float impact = vy[i];
            float y2 = py[i];
            float dir = dy > 0 ? 1f : -1f;
            for (int k = 0; k < 9; k++) {            // get close to the surface
                float t = y2 + dir * 0.01f;
                if (Math.abs(t - py[i]) > Math.abs(dy)) break;
                if (solid(w, px[i], t + offY, pz[i])) break;
                y2 = t;
            }
            py[i] = y2;
            if (impact < 0) { vx[i] *= 0.96f; vz[i] *= 0.96f; }
            vy[i] *= -0.1f;
            if (impact < -0.2f && count < limit - 50 && RNG.nextFloat() < 0.35f) {
                for (int k = 0; k < 2; k++) {
                    emit(px[i], py[i] + 0.06, pz[i],
                            RNG.nextGaussian() * 0.05, -impact * 0.35 + RNG.nextDouble() * 0.05,
                            RNG.nextGaussian() * 0.05);
                }
            }
        } else py[i] = ny;

        float nz = pz[i] + dz;
        if (solid(w, px[i], py[i], nz + (dz > 0 ? R : -R))) { vz[i] *= -0.15f; vy[i] *= 0.92f; } else pz[i] = nz;
    }

    public static void tick(ClientWorld w, PlayerEntity self) {
        tickPour();
        if (count == 0) return;
        tickCounter++;
        gatherEntities(w, self);

        Arrays.fill(head, -1);
        for (int i = 0; i < count; i++) {
            ox[i] = px[i]; oy[i] = py[i]; oz[i] = pz[i];
            overlapped[i] = false;
            int b = bucket(MathHelper.floor(px[i] / ADH), MathHelper.floor(py[i] / ADH), MathHelper.floor(pz[i] / ADH));
            next[i] = head[b];
            head[b] = i;
        }

        // Pass 1: soft repulsion + adhesion (shared velocity). No attraction at a distance.
        for (int i = 0; i < count; i++) {
            if (rest[i] >= SLEEP_TICKS) continue;
            float spi = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            int cx = MathHelper.floor(px[i] / ADH), cy = MathHelper.floor(py[i] / ADH), cz = MathHelper.floor(pz[i] / ADH);
            int ns = 0, neigh = 0;
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
                    if (rest[j] >= SLEEP_TICKS && spi > 0.004f) rest[j] = 0;   // wake neighbour
                    float d = (float) Math.sqrt(d2);
                    if (d < H_REP) {
                        float q = 1f - d / H_REP;
                        float push = q * 0.04f / d;
                        vx[i] += ddx * push; vy[i] += ddy * push; vz[i] += ddz * push;
                    }
                    if (d < MIN_D) overlapped[i] = true;
                    float visc = 0.12f * (1f - d / ADH);
                    vx[i] += (vx[j] - vx[i]) * visc;
                    vy[i] += (vy[j] - vy[i]) * visc;
                    vz[i] += (vz[j] - vz[i]) * visc;
                }
            }
        }

        // Pass 2: hard separation so particles never overlap (hitbox).
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                if (rest[i] >= SLEEP_TICKS) continue;
                if (pass == 0 || overlapped[i]) overlapped[i] = separate(w, i);
            }
        }

        boolean anyDead = false;
        int n0 = count;
        for (int i = 0; i < n0; i++) {
            if (rest[i] >= SLEEP_TICKS) {
                if (collideEntities(i)) { rest[i] = 0; continue; }
                if ((i + tickCounter) % 10 == 0) {
                    if (onBlock[i] && !solid(w, px[i], py[i] - R - 0.04f, pz[i])) { rest[i] = 0; continue; }
                    pos.set(MathHelper.floor(px[i]), MathHelper.floor(py[i]), MathHelper.floor(pz[i]));
                    col[i] = color(0.35f + 0.65f * (w.getLightLevel(pos) / 15f), 0f);
                }
                continue;
            }

            vy[i] -= 0.045f;
            vx[i] *= 0.99f; vy[i] *= 0.99f; vz[i] *= 0.99f;
            float sp2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            float spd = (float) Math.sqrt(sp2);
            if (spd > MAX_SPEED) {
                float s = MAX_SPEED / spd;
                vx[i] *= s; vy[i] *= s; vz[i] *= s;
                spd = MAX_SPEED;
            }
            // Fast particles move in small steps so they can't skip through others.
            int sub = Math.min(3, 1 + (int) (spd / 0.12f));
            float f = 1f / sub;
            for (int s = 0; s < sub; s++) {
                moveStep(w, i, f);
                if (sub > 1) separate(w, i);
            }

            collideEntities(i);

            pos.set(MathHelper.floor(px[i]), MathHelper.floor(py[i]), MathHelper.floor(pz[i]));
            if (py[i] < w.getBottomY()) {
                age[i] = DEAD; anyDead = true; continue;
            }
            if (!w.getFluidState(pos).isEmpty()) {
                vx[i] *= 0.85f; vy[i] *= 0.85f; vz[i] *= 0.85f;
            }
            float sp = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (sp < SLEEP_SPEED2) rest[i]++; else rest[i] = 0;
            if (rest[i] >= SLEEP_TICKS) {
                vx[i] = 0; vy[i] = 0; vz[i] = 0;
                onBlock[i] = solid(w, px[i], py[i] - R - 0.04f, pz[i]);
            }
            if (age[i] < 1000) age[i]++;
            float foam = Math.min(1f, (float) Math.sqrt(sp) * 2f) * 0.6f;
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
