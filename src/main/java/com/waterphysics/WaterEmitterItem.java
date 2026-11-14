package com.waterphysics;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Hold right-click: a stream of water particles. Vanilla repeats use() every 4 ticks while held. */
public class WaterEmitterItem extends Item {
	private static final float SPEED = 0.5f;

	public WaterEmitterItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world instanceof ServerWorld sw) {
			Vec3d look = user.getRotationVec(1.0f).normalize();
			Vec3d up = Math.abs(look.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
			Vec3d right = look.crossProduct(up).normalize();
			Vec3d realUp = right.crossProduct(look).normalize();
			Vec3d origin = user.getEyePos().add(look.multiply(0.9));
			FluidManager manager = FluidManager.get(sw);
			// 4 ticks worth of stream, 3x3 cross-section, offset along the look direction so it looks continuous
			for (int t = 0; t < 4; t++) {
				for (int a = -1; a <= 1; a++) {
					for (int b = -1; b <= 1; b++) {
						Vec3d p = origin.add(look.multiply(SPEED * t))
								.add(right.multiply(a * 0.22)).add(realUp.multiply(b * 0.22));
						float jx = (sw.random.nextFloat() - 0.5f) * 0.03f;
						float jy = (sw.random.nextFloat() - 0.5f) * 0.03f;
						float jz = (sw.random.nextFloat() - 0.5f) * 0.03f;
						manager.queueSpawn((float) p.x, (float) p.y, (float) p.z,
								(float) look.x * SPEED + jx, (float) look.y * SPEED + jy, (float) look.z * SPEED + jz);
					}
				}
			}
		}
		return TypedActionResult.success(stack, world.isClient);
	}
}
