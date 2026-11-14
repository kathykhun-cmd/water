package com.waterphysics.sim;

import java.util.Arrays;

/**
 * Pure-Java particle fluid core. No Minecraft classes, so it can be unit-tested and
 * run on a worker thread. Model: position-based particles with a pairwise
 * repulsion constraint (rest spacing 0.5 block = 1/8 block of water per particle),
 * XSPH-style viscosity, per-axis swept AABB collision against a voxel grid.
 *
 * Threading contract: step() runs on the worker thread. Every other method must only be
 * called while no step is running (the manager guarantees this).
 */
public final class FluidSim {

    /** Voxel lookup. 0 = passable, 1 = solid, 2..9 = settled water with level (v-1) of 8. */
    public interface Grid {
        int cell(int x, int y, int z);
    }

    public static final int CELL_AIR = 0;
    public static final int CELL_SOLID = 1;

    // ---- tuning (blocks, ticks) ----
    public static final float HALF = 0.2f;        // collision half-extent
    public static final float RADIUS = 0.5f;      // pair interaction radius = rest spacing
    public static final float GRAVITY = 0.035f;
    public static final float MAX_SPEED = 0.55f;
    public static final float DAMPING = 0.998f;
    public static final float STIFFNESS = 0.30f;
    public static final float VISCOSITY = 0.12f;
    public static final float FLOOR_FRICTION = 0.80f;
    public static final float MAX_PUSH = 0.12f;
    public static final int ITERATIONS = 3;
    public static final float SLEEP_SPEED = 0.03f;
    public static final int SLEEP_TICKS = 12;

    public final int capacity;
    public int count;

    public final float[] px, py, pz, vx, vy, vz;
    private final float[] ox, oy, oz, dx, dy, dz;
    public final int[] sleep;

    // spatial hash
    private final int tableSize;
    private final int[] bucketStart, sortedIdx, bucketOf, stamp, cursor;
    private int stampCounter = 0;

    public FluidSim(int capacity) {
        this.capacity = capacity;
        px = new float[capacity]; py = new float[capacity]; pz = new float[capacity];
        vx = new float[capacity]; vy = new float[capacity]; vz = new float[capacity];
        ox = new float[capacity]; oy = new float[capacity]; oz = new float[capacity];
        dx = new float[capacity]; dy = new float[capacity]; dz = new float[capacity];
        sleep = new int[capacity];
        int t = 1;
        while (t < capacity * 2) t <<= 1;
        tableSize = t;
        bucketStart = new int[t + 1];
        sortedIdx = new int[capacity];
        bucketOf = new int[capacity];
        stamp = new int[t];
        cursor = new int[t];
    }

    public boolean add(float x, float y, float z, float velX, float velY, float velZ) {
        if (count >= capacity) return false;
        int i = count++;
        px[i] = x; py[i] = y; pz[i] = z;
        vx[i] = velX; vy[i] = velY; vz[i] = velZ;
        sleep[i] = 0;
        return true;
    }

    public void clear() { count = 0; }

    /** Marks particle dead; call compact() afterwards. */
    public void kill(int i) { sleep[i] = Integer.MIN_VALUE; }

    public void compact() {
        int w = 0;
        for (int r = 0; r < count; r++) {
            if (sleep[r] == Integer.MIN_VALUE) continue;
            if (w != r) {
                px[w] = px[r]; py[w] = py[r]; pz[w] = pz[r];
                vx[w] = vx[r]; vy[w] = vy[r]; vz[w] = vz[r];
                sleep[w] = sleep[r];
            }
            w++;
        }
        count = w;
    }

    /** Fills out with (quantised y, index) keys of sleepy particles, lowest first. Returns how many. */
    public int collectSleepy(long[] out) {
        int n = 0;
        for (int i = 0; i < count && n < out.length; i++) {
            if (sleep[i] >= SLEEP_TICKS) {
                long yk = (long) ((py[i] + 4096f) * 256f);
                out[n++] = (yk << 32) | (long) i;
            }
        }
        Arrays.sort(out, 0, n);
        return n;
    }

    // ------------------------------------------------------------------ step

    public void step(Grid g) {
        final int n = count;
        if (n == 0) return;

        for (int i = 0; i < n; i++) {
            ox[i] = px[i]; oy[i] = py[i]; oz[i] = pz[i];
            float ax = vx[i] * DAMPING, ay = (vy[i] - GRAVITY) * DAMPING, az = vz[i] * DAMPING;
            float sp = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (sp > MAX_SPEED) { float k = MAX_SPEED / sp; ax *= k; ay *= k; az *= k; }
            moveWithCollision(i, ax, ay, az, g);
        }

        buildGrid(n);

        for (int it = 0; it < ITERATIONS; it++) {
            for (int i = 0; i < n; i++) { dx[i] = 0; dy[i] = 0; dz[i] = 0; }
            solvePairs(n);
            for (int i = 0; i < n; i++) {
                float ax = dx[i], ay = dy[i], az = dz[i];
                float m = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (m > MAX_PUSH) { float k = MAX_PUSH / m; ax *= k; ay *= k; az *= k; }
                if (m > 1e-6f) moveWithCollision(i, ax, ay, az, g);
            }
        }

        for (int i = 0; i < n; i++) {
            float ax = px[i] - ox[i], ay = py[i] - oy[i], az = pz[i] - oz[i];
            vx[i] = ax; vy[i] = ay; vz[i] = az;
            float sp = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (sp < SLEEP_SPEED) sleep[i]++; else sleep[i] = 0;
        }
    }

    // ------------------------------------------------------------------ neighbour grid

    private int hash(int cx, int cy, int cz) {
        int h = (cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791);
        h ^= (h >>> 15);
        return h & (tableSize - 1);
    }

    private static int cellCoord(float v) { return (int) Math.floor(v / RADIUS); }

    private void buildGrid(int n) {
        Arrays.fill(bucketStart, 0);
        for (int i = 0; i < n; i++) {
            int b = hash(cellCoord(px[i]), cellCoord(py[i]), cellCoord(pz[i]));
            bucketOf[i] = b;
            bucketStart[b + 1]++;
        }
        for (int b = 0; b < tableSize; b++) bucketStart[b + 1] += bucketStart[b];
        System.arraycopy(bucketStart, 0, cursor, 0, tableSize);
        for (int i = 0; i < n; i++) sortedIdx[cursor[bucketOf[i]]++] = i;
    }

    private void solvePairs(int n) {
        final float H = RADIUS, H2 = H * H;
        for (int i = 0; i < n; i++) {
            float xi = px[i], yi = py[i], zi = pz[i];
            int cx = cellCoord(xi), cy = cellCoord(yi), cz = cellCoord(zi);
            stampCounter++;
            if (stampCounter == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); stampCounter = 1; }
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++) {
                int bk = hash(cx + a, cy + b, cz + c);
                if (stamp[bk] == stampCounter) continue;
                stamp[bk] = stampCounter;
                for (int k = bucketStart[bk], e = bucketStart[bk + 1]; k < e; k++) {
                    int j = sortedIdx[k];
                    if (j <= i) continue;
                    float rx = xi - px[j], ry = yi - py[j], rz = zi - pz[j];
                    float d2 = rx * rx + ry * ry + rz * rz;
                    if (d2 >= H2) continue;
                    float d = (float) Math.sqrt(d2);
                    if (d < 1e-5f) { // identical positions: separate deterministically
                        rx = ((i * 31 + j) & 3) - 1.5f; ry = ((i + j * 17) & 3) - 1.5f; rz = ((i * 7 + j * 13) & 3) - 1.5f;
                        d = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                        if (d < 1e-5f) { rx = 1; ry = 0; rz = 0; d = 1; }
                    }
                    float inv = 1f / d;
                    float nx = rx * inv, ny = ry * inv, nz = rz * inv;
                    float real = (float) Math.sqrt(d2);
                    float push = (H - real) * 0.5f * STIFFNESS;
                    dx[i] += nx * push; dy[i] += ny * push; dz[i] += nz * push;
                    dx[j] -= nx * push; dy[j] -= ny * push; dz[j] -= nz * push;
                    // viscosity on relative displacement this step
                    float w = (1f - real / H) * VISCOSITY * 0.5f;
                    float ux = (px[i] - ox[i]) - (px[j] - ox[j]);
                    float uy = (py[i] - oy[i]) - (py[j] - oy[j]);
                    float uz = (pz[i] - oz[i]) - (pz[j] - oz[j]);
                    dx[i] -= ux * w; dy[i] -= uy * w; dz[i] -= uz * w;
                    dx[j] += ux * w; dy[j] += uy * w; dz[j] += uz * w;
                }
            }
        }
    }

    // ------------------------------------------------------------------ collision

    private boolean blocked(Grid g, float x, float y, float z) {
        int x0 = (int) Math.floor(x - HALF), x1 = (int) Math.floor(x + HALF);
        int y0 = (int) Math.floor(y - HALF), y1 = (int) Math.floor(y + HALF);
        int z0 = (int) Math.floor(z - HALF), z1 = (int) Math.floor(z + HALF);
        float bottom = y - HALF;
        for (int ix = x0; ix <= x1; ix++)
            for (int iy = y0; iy <= y1; iy++)
                for (int iz = z0; iz <= z1; iz++) {
                    int c = g.cell(ix, iy, iz);
                    if (c == CELL_AIR) continue;
                    if (c == CELL_SOLID) return true;
                    if (bottom < iy + (c - 1) / 8f) return true; // settled water acts as a low floor
                }
        return false;
    }

    /** Moves particle i by (mx,my,mz), one axis at a time, halving a blocked step up to 4 times. Y first. */
    private void moveWithCollision(int i, float mx, float my, float mz, Grid g) {
        boolean hitY = false;
        if (my != 0f) {
            float m = my;
            for (int k = 0; k < 5; k++) {
                if (!blocked(g, px[i], py[i] + m, pz[i])) { py[i] += m; break; }
                if (k == 0) hitY = true;
                m *= 0.5f;
            }
        }
        if (hitY && my < 0f) { mx *= FLOOR_FRICTION; mz *= FLOOR_FRICTION; }
        if (mx != 0f) {
            float m = mx;
            for (int k = 0; k < 5; k++) {
                if (!blocked(g, px[i] + m, py[i], pz[i])) { px[i] += m; break; }
                m *= 0.5f;
            }
        }
        if (mz != 0f) {
            float m = mz;
            for (int k = 0; k < 5; k++) {
                if (!blocked(g, px[i], py[i], pz[i] + m)) { pz[i] += m; break; }
                m *= 0.5f;
            }
        }
    }
}
