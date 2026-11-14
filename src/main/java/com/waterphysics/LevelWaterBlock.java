package com.waterphysics;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Settled water: a block that holds 1..8 "eighths" of water (one particle = 1/8 block).
 * It never flows and never ticks, so it costs nothing. If the floor under it disappears
 * it turns back into particles ("wakes up").
 */
public class LevelWaterBlock extends Block {
	public static final MapCodec<LevelWaterBlock> CODEC = createCodec(LevelWaterBlock::new);
	public static final IntProperty LEVEL = IntProperty.of("level", 1, 8);

	public LevelWaterBlock(Settings settings) {
		super(settings);
		setDefaultState(getStateManager().getDefaultState().with(LEVEL, 8));
	}

	@Override
	protected MapCodec<? extends Block> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(LEVEL);
	}

	/** Like vanilla water: you cannot target it, you look/place straight through it. */
	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return VoxelShapes.empty();
	}

	@Override
	protected boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
		if (!stateFrom.isOf(this)) return false;
		int a = state.get(LEVEL), b = stateFrom.get(LEVEL);
		if (direction == Direction.UP) return a == 8;
		if (direction == Direction.DOWN) return b == 8;
		return b >= a;
	}

	@Override
	protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
		if (world.isClient || !(world instanceof ServerWorld sw)) return;
		if (!supports(world, pos.down())) {
			wake(sw, pos, state);
		}
	}

	/** True if a water block placed directly above {@code below} would be held up. */
	public static boolean supports(World world, BlockPos below) {
		BlockState s = world.getBlockState(below);
		if (s.isOf(WaterMod.LEVEL_WATER)) return s.get(LEVEL) == 8;
		return !s.getCollisionShape(world, below).isEmpty();
	}

	/** Turns this block back into {@code level} particles on a 2x2x2 lattice and removes it. */
	public static void wake(ServerWorld world, BlockPos pos, BlockState state) {
		int level = state.get(LEVEL);
		FluidManager manager = FluidManager.get(world);
		for (int k = 0; k < level; k++) {
			float x = pos.getX() + 0.25f + (k & 1) * 0.5f;
			float z = pos.getZ() + 0.25f + ((k >> 1) & 1) * 0.5f;
			float y = pos.getY() + 0.25f + ((k >> 2) & 1) * 0.5f;
			manager.queueSpawn(x, y, z, 0f, 0f, 0f);
		}
		world.removeBlock(pos, false);
	}
}
