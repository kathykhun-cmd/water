import com.waterphysics.sim.FluidSim;

/** Headless sanity test: drops a ball of water into a 24x24 stone tank. */
public class SimTest {
    static final int HALFW = 12;
    public static void main(String[] a) {
        int radius = a.length > 0 ? Integer.parseInt(a[0]) : 4;
        FluidSim.Grid grid = (x, y, z) -> (y < 0 || Math.abs(x) > HALFW || Math.abs(z) > HALFW) ? 1 : 0;
        FluidSim sim = new FluidSim(40000);
        // ball: lattice spacing 0.5
        float cy = radius + 4f;
        for (float x = -radius; x <= radius; x += 0.5f)
            for (float y = -radius; y <= radius; y += 0.5f)
                for (float z = -radius; z <= radius; z += 0.5f)
                    if (x*x + y*y + z*z <= radius*radius)
                        sim.add(x + 0.01f * (float) Math.random(), cy + y, z + 0.01f * (float) Math.random(), 0, 0, 0);
        System.out.println("particles: " + sim.count + "  (~" + (sim.count / 8f) + " blocks of water)");
        long worst = 0, total = 0;
        for (int t = 0; t <= 400; t++) {
            long t0 = System.nanoTime();
            sim.step(grid);
            long dt = System.nanoTime() - t0;
            if (t > 20) { worst = Math.max(worst, dt); total += dt; }
            if (t % 50 == 0) report(sim, t);
        }
        System.out.printf("avg step %.2f ms, worst %.2f ms%n", total / 380.0 / 1e6, worst / 1e6);
        int sleepy = 0; for (int i = 0; i < sim.count; i++) if (sim.sleep[i] >= FluidSim.SLEEP_TICKS) sleepy++;
        System.out.println("sleepy (ready to settle): " + sleepy + " / " + sim.count);
        boolean bad = false;
        for (int i = 0; i < sim.count; i++) {
            if (Float.isNaN(sim.px[i]) || sim.py[i] < -0.01f || Math.abs(sim.px[i]) > HALFW + 1 || Math.abs(sim.pz[i]) > HALFW + 1) bad = true;
        }
        System.out.println("escaped/NaN: " + bad);
    }
    static void report(FluidSim s, int t) {
        float minY = 1e9f, maxY = -1e9f, sumY = 0, minX = 1e9f, maxX = -1e9f, sp = 0;
        for (int i = 0; i < s.count; i++) {
            minY = Math.min(minY, s.py[i]); maxY = Math.max(maxY, s.py[i]); sumY += s.py[i];
            minX = Math.min(minX, s.px[i]); maxX = Math.max(maxX, s.px[i]);
            sp += (float) Math.sqrt(s.vx[i]*s.vx[i] + s.vy[i]*s.vy[i] + s.vz[i]*s.vz[i]);
        }
        System.out.printf("t=%3d  y[min %.2f avg %.2f max %.2f]  x[%.1f..%.1f]  avgSpeed %.3f%n", t, minY, sumY / s.count, maxY, minX, maxX, sp / s.count);
    }
}
