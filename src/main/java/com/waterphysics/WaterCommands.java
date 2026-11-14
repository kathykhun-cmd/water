package com.waterphysics;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class WaterCommands {
	private WaterCommands() { }

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess access,
								CommandManager.RegistrationEnvironment environment) {
		dispatcher.register(CommandManager.literal("water")
				.requires(src -> src.hasPermissionLevel(2))
				.then(CommandManager.literal("ball")
						.then(CommandManager.argument("size", IntegerArgumentType.integer(1, WaterMod.MAX_BALL))
								.executes(ctx -> ball(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size")))))
				.then(CommandManager.literal("convert")
						.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, WaterMod.MAX_CONVERT))
								.executes(ctx -> convert(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
				.then(CommandManager.literal("clear").executes(ctx -> clear(ctx.getSource())))
				.then(CommandManager.literal("info").executes(ctx -> info(ctx.getSource()))));
	}

	/** Ball of water, radius = size blocks, in front of the player. 1 particle = 1/8 block of water. */
	private static int ball(ServerCommandSource src, int size) throws CommandSyntaxException {
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();
		FluidManager manager = FluidManager.get(world);
		Vec3d look = player.getRotationVec(1.0f).normalize();
		Vec3d c = player.getEyePos().add(look.multiply(size + 2.0));
		int spawned = 0;
		for (float x = -size; x <= size; x += 0.5f)
			for (float y = -size; y <= size; y += 0.5f)
				for (float z = -size; z <= size; z += 0.5f) {
					if (x * x + y * y + z * z > size * size) continue;
					float jx = (world.random.nextFloat() - 0.5f) * 0.02f;
					float jz = (world.random.nextFloat() - 0.5f) * 0.02f;
					manager.queueSpawn((float) c.x + x + jx, (float) c.y + y, (float) c.z + z + jz, 0f, 0f, 0f);
					spawned++;
				}
		final int n = spawned;
		src.sendFeedback(() -> Text.literal("Water ball: " + n + " partikel (~" + (n / 8) + " blok air)"), false);
		return n;
	}

	private static int convert(ServerCommandSource src, int radius) throws CommandSyntaxException {
		ServerPlayerEntity player = src.getPlayerOrThrow();
		FluidManager.get(src.getWorld()).startConvert(player.getBlockPos(), radius);
		src.sendFeedback(() -> Text.literal("Mengganti air vanilla dalam radius " + radius + " (dikerjakan bertahap)"), false);
		return 1;
	}

	private static int clear(ServerCommandSource src) {
		FluidManager.get(src.getWorld()).clearActive();
		src.sendFeedback(() -> Text.literal("Partikel air aktif dihapus (blok air yang sudah mengendap tetap ada)"), false);
		return 1;
	}

	private static int info(ServerCommandSource src) {
		FluidManager m = FluidManager.get(src.getWorld());
		src.sendFeedback(() -> Text.literal("Partikel aktif: " + m.sim.count + "/" + m.sim.capacity
				+ ", tick simulasi yang dilewati karena berat: " + m.skippedTicks), false);
		return 1;
	}
}
