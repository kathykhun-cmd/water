package com.waterphysics;

import com.waterphysics.net.ParticlesPayload;
import com.waterphysics.sim.FluidSim;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One manager per dimension. Tick flow (all on the server thread except sim.step):
 *   1. if the previous sim step is still running, skip this tick (sim just runs "slower", server TPS is untouched)
 *   2. apply queued spawns, run conversion jobs
 *   3. settle sleeping particles into LevelWaterBlocks
 *   4. snapshot the voxels around particles into a cache (the worker never touches the world)
 *   5. send particle positions to nearby players
 *   6. submit the next sim step to the worker thread
 */
public final class FluidManager {
	public static final int SETTLE_PER_TICK = 400;
	public static final int SEND_MAX = 8000;
	public static final int SEND_RANGE = 160;
	public static final int CONVERT_PER_TICK = 2500;

	private static final Map<RegistryKey<World>, FluidManager> MANAGERS = new HashMap<>();
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "waterphysics-sim");
		t.setDaemon(true);
		return t;
	});

	public static void init() {
		ServerTickEvents.END_WORLD_TICK.register(world -> get(world).tick(world));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (FluidManager m : MANAGERS.values()) m.shutdown();
			MANAGERS.clear();
		});
	}

	public static FluidManager get(ServerWorld world) {
		return MANAGERS.computeIfAbsent(world.getRegistryKey(), k -> new FluidManager());
	}

	// ---------------------------------------------------------------- state

	public final FluidSim sim = new FluidSim(WaterMod.MAX_ACTIVE);
	private final ConcurrentLinkedQueue<float[]> spawnQueue = new ConcurrentLinkedQueue<>();
	private final List<ConvertJob> convertJobs = new ArrayList<>();
	private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
	private final LongOpenHashSet occupied = new LongOpenHashSet();
	private final Long2BooleanOpenHashMap loadedChunks = new Long2BooleanOpenHashMap();
	private final long[] sleepyKeys = new long[WaterMod.MAX_ACTIVE];
	private final FluidSim.Grid grid;
	private Future<?> running;
	private boolean announcedEmpty = true;
	public int skippedTicks = 0;

	private FluidManager() {
		cache.defaultReturnValue((byte) 1); // unknown cell = solid, so water can never leak into unloaded space
		grid = (x, y, z) -> cache.get(BlockPos.asLong(x, y, z));
	}

	public void queueSpawn(float x, float y, float z, float vx, float vy, float vz) {
		spawnQueue.add(new float[]{x, y, z, vx, vy, vz});
	}

	public void startConvert(BlockPos center, int radius) {
		convertJobs.add(new ConvertJob(center, radius));
	}

	public void clearActive() {
		spawnQueue.clear();
		// the worker may be mid-step; clearing happens on the next idle tick
		pendingClear = true;
	}

	private boolean pendingClear = false;

	public boolean busy() { return running != null && !running.isDone(); }

	private void shutdown() {
		if (running != null) {
			try { running.get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
		}
	}

	// ---------------------------------------------------------------- tick

	private void tick(ServerWorld world) {
		if (running != null) {
			if (!running.isDone()) { skippedTicks++; return; }
			try {
				running.get();
			} catch (Exception e) {
				WaterMod.LOGGER.error("Water simulation step failed, clearing particles", e);
				sim.clear();
			}
			running = null;
		}

		if (pendingClear) { sim.clear(); pendingClear = false; }
		drainSpawns(world);
		runConvertJobs(world);

		if (sim.count == 0) {
			if (!announcedEmpty) { broadcast(world, true); announcedEmpty = true; }
			return;
		}
		announcedEmpty = false;

		settle(world);
		if (sim.count == 0) { broadcast(world, true); announcedEmpty = true; return; }

		buildCache(world);
		broadcast(world, false);
		running = POOL.submit(() -> sim.step(grid));
	}

	// ---------------------------------------------------------------- spawning

	private void drainSpawns(ServerWorld world) {
		float[] s;
		BlockPos.Mutable p = new BlockPos.Mutable();
		while ((s = spawnQueue.poll()) != null) {
			if (sim.count >= sim.capacity) { spawnQueue.clear(); break; }
			int x = floor(s[0]), y = floor(s[1]), z = floor(s[2]);
			if (!chunkLoaded(world, x >> 4, z >> 4)) continue;
			if (y < world.getBottomY() || y >= world.getTopY()) continue;
			BlockState st = world.getBlockState(p.set(x, y, z));
			if (!st.getCollisionShape(world, p).isEmpty()) continue; // never spawn inside walls
			sim.add(s[0], s[1], s[2], s[3], s[4], s[5]);
		}
	}

	// ---------------------------------------------------------------- settling

	private void settle(ServerWorld world) {
		int n = sim.collectSleepy(sleepyKeys);
		if (n == 0) return;
		int done = 0;
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int k = 0; k < n && done < SETTLE_PER_TICK; k++) {
			int i = (int) (sleepyKeys[k] & 0xFFFFFFFFL);
			if (trySettle(world, i, p)) { sim.kill(i); done++; } else { sim.sleep[i] = 0; }
		}
		if (done > 0) sim.compact();
	}

	/** -1 = obstacle, 0 = free (air / replaceable), 1..8 = our water. */
	private int levelAt(ServerWorld w, int x, int y, int z, BlockPos.Mutable p) {
		if (y < w.getBottomY() || y >= w.getTopY()) return -1;
		if (!chunkLoaded(w, x >> 4, z >> 4)) return -1;
		BlockState s = w.getBlockState(p.set(x, y, z));
		if (s.isOf(WaterMod.LEVEL_WATER)) return s.get(LevelWaterBlock.LEVEL);
		if (s.isAir() || s.isReplaceable()) return 0;
		return -1;
	}

	/** Can a water block sit on top of this cell? */
	private boolean supportAt(ServerWorld w, int x, int y, int z, BlockPos.Mutable p) {
		if (y < w.getBottomY()) return true;
		if (!chunkLoaded(w, x >> 4, z >> 4)) return true;
		BlockState s = w.getBlockState(p.set(x, y, z));
		if (s.isOf(WaterMod.LEVEL_WATER)) return s.get(LevelWaterBlock.LEVEL) == 8;
		return !s.getCollisionShape(w, p).isEmpty();
	}

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private boolean trySettle(ServerWorld w, int i, BlockPos.Mutable p) {
		int x = floor(sim.px[i]), cy = floor(sim.py[i]), z = floor(sim.pz[i]);

		// resting on a partly filled block below: the particle belongs to that block
		if (levelAt(w, x, cy, z, p) == 0) {
			int below = levelAt(w, x, cy - 1, z, p);
			if (below >= 1 && below < 8) cy--;
		}
		int lvl = levelAt(w, x, cy, z, p);
		if (lvl == 8) { cy++; lvl = levelAt(w, x, cy, z, p); }
		if (lvl < 0) return false;
		if (lvl == 0 && !supportAt(w, x, cy - 1, z, p)) return false;

		// cheap leveling: slide to a lower neighbour (up to 4 hops) so pools flatten instead of forming cones
		for (int hop = 0; hop < 4 && lvl >= 2; hop++) {
			int bestDir = -1, bestLvl = Integer.MAX_VALUE;
			int start = w.random.nextInt(4);
			for (int d0 = 0; d0 < 4; d0++) {
				int d = (start + d0) & 3;
				int nx = x + DIRS[d][0], nz = z + DIRS[d][1];
				int nl = levelAt(w, nx, cy, nz, p);
				if (nl < 0 || nl > lvl - 2) continue;
				if (nl == 0 && !supportAt(w, nx, cy - 1, nz, p)) {
					// ledge: let the particle spill over the edge instead of settling
					sim.vx[i] += DIRS[d][0] * 0.12f;
					sim.vz[i] += DIRS[d][1] * 0.12f;
					return false;
				}
				if (nl < bestLvl) { bestLvl = nl; bestDir = d; }
			}
			if (bestDir < 0) break;
			x += DIRS[bestDir][0];
			z += DIRS[bestDir][1];
			lvl = bestLvl;
		}

		BlockPos target = new BlockPos(x, cy, z);
		BlockState state = WaterMod.LEVEL_WATER.getDefaultState().with(LevelWaterBlock.LEVEL, Math.min(8, lvl + 1));
		w.setBlockState(target, state, Block.NOTIFY_LISTENERS); // no neighbour notifications: settling never cascades
		return true;
	}

	// ---------------------------------------------------------------- voxel snapshot for the worker

	private boolean chunkLoaded(ServerWorld w, int cx, int cz) {
		long key = ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
		if (loadedChunks.containsKey(key)) return loadedChunks.get(key);
		boolean loaded = w.getChunk(cx, cz, ChunkStatus.FULL, false) != null;
		loadedChunks.put(key, loaded);
		return loaded;
	}

	private void buildCache(ServerWorld w) {
		cache.clear();
		occupied.clear();
		loadedChunks.clear();
		for (int i = 0; i < sim.count; i++) {
			occupied.add(BlockPos.asLong(floor(sim.px[i]), floor(sim.py[i]), floor(sim.pz[i])));
		}
		BlockPos.Mutable p = new BlockPos.Mutable();
		LongIterator it = occupied.iterator();
		while (it.hasNext()) {
			long k = it.nextLong();
			int cx = BlockPos.unpackLongX(k), cy = BlockPos.unpackLongY(k), cz = BlockPos.unpackLongZ(k);
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					for (int dz = -1; dz <= 1; dz++) {
						long nk = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
						if (cache.containsKey(nk)) continue;
						cache.put(nk, classify(w, cx + dx, cy + dy, cz + dz, p));
					}
		}
	}

	private byte classify(ServerWorld w, int x, int y, int z, BlockPos.Mutable p) {
		if (y < w.getBottomY()) return FluidSim.CELL_SOLID;
		if (y >= w.getTopY()) return FluidSim.CELL_AIR;
		if (!chunkLoaded(w, x >> 4, z >> 4)) return FluidSim.CELL_SOLID;
		BlockState s = w.getBlockState(p.set(x, y, z));
		if (s.isOf(WaterMod.LEVEL_WATER)) return (byte) (1 + s.get(LevelWaterBlock.LEVEL)); // 2..9
		if (s.getCollisionShape(w, p).isEmpty()) return FluidSim.CELL_AIR;
		return FluidSim.CELL_SOLID;
	}

	// ---------------------------------------------------------------- networking

	private void broadcast(ServerWorld world, boolean empty) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			BlockPos o = player.getBlockPos();
			short[] data = new short[0];
			if (!empty) {
				short[] buf = new short[SEND_MAX * 3];
				int n = 0;
				for (int i = 0; i < sim.count && n < SEND_MAX; i++) {
					float rx = sim.px[i] - o.getX(), ry = sim.py[i] - o.getY(), rz = sim.pz[i] - o.getZ();
					if (Math.abs(rx) > SEND_RANGE || Math.abs(ry) > SEND_RANGE || Math.abs(rz) > SEND_RANGE) continue;
					buf[n * 3] = (short) (rx * 64f);
					buf[n * 3 + 1] = (short) (ry * 64f);
					buf[n * 3 + 2] = (short) (rz * 64f);
					n++;
				}
				data = java.util.Arrays.copyOf(buf, n * 3);
			}
			ServerPlayNetworking.send(player, new ParticlesPayload(o.getX(), o.getY(), o.getZ(), data));
		}
	}

	// ---------------------------------------------------------------- vanilla water -> LevelWater

	private static final class ConvertJob {
		final int cx, cy, cz, r;
		int ix, iy, iz;
		boolean done;

		ConvertJob(BlockPos c, int r) {
			this.cx = c.getX(); this.cy = c.getY(); this.cz = c.getZ(); this.r = r;
			ix = -r; iy = -r; iz = -r;
		}

		boolean advance() {
			if (++iz > r) { iz = -r; if (++iy > r) { iy = -r; if (++ix > r) { done = true; return false; } } }
			return true;
		}
	}

	private void runConvertJobs(ServerWorld w) {
		if (convertJobs.isEmpty()) return;
		int budget = CONVERT_PER_TICK;
		BlockPos.Mutable p = new BlockPos.Mutable();
		BlockPos.Mutable below = new BlockPos.Mutable();
		for (ConvertJob job : convertJobs) {
			while (budget > 0 && !job.done) {
				budget--;
				int x = job.cx + job.ix, y = job.cy + job.iy, z = job.cz + job.iz;
				if (y >= w.getBottomY() && y < w.getTopY() && chunkLoaded(w, x >> 4, z >> 4)) {
					BlockState s = w.getBlockState(p.set(x, y, z));
					if (s.isOf(Blocks.WATER)) {
						boolean source = s.getFluidState().isStill();
						if (!source) {
							w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
						} else if (supportAt(w, x, y - 1, z, below)) {
							w.setBlockState(p, WaterMod.LEVEL_WATER.getDefaultState(), Block.NOTIFY_LISTENERS);
						} else {
							// nothing under it: let it fall as particles instead of floating
							w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
							for (int k = 0; k < 8; k++) {
								queueSpawn(x + 0.25f + (k & 1) * 0.5f, y + 0.25f + ((k >> 2) & 1) * 0.5f,
										z + 0.25f + ((k >> 1) & 1) * 0.5f, 0, 0, 0);
							}
						}
					}
				}
				job.advance();
			}
			if (budget <= 0) break;
		}
		convertJobs.removeIf(j -> j.done);
	}

	private static int floor(float v) { return (int) Math.floor(v); }
}
