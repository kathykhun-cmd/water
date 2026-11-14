package com.waterphysics.client;

import com.waterphysics.WaterMod;
import com.waterphysics.net.ParticlesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.util.math.Vec3d;

/** Draws the moving particles as small translucent cubes. Settled water is rendered as normal blocks. */
@Environment(EnvType.CLIENT)
public class WaterClient implements ClientModInitializer {
	private static float[] particles = new float[0]; // x,y,z triples in world space
	private static final double HALF = 0.21;

	@Override
	public void onInitializeClient() {
		BlockRenderLayerMap.INSTANCE.putBlock(WaterMod.LEVEL_WATER, RenderLayer.getTranslucent());
		ColorProviderRegistry.BLOCK.register(
				(state, world, pos, tintIndex) -> (world != null && pos != null) ? BiomeColors.getWaterColor(world, pos) : 0x3F76E4,
				WaterMod.LEVEL_WATER);

		ClientPlayNetworking.registerGlobalReceiver(ParticlesPayload.ID, (payload, context) -> {
			short[] d = payload.data();
			float[] out = new float[d.length];
			for (int i = 0; i + 2 < d.length; i += 3) {
				out[i] = payload.ox() + d[i] / 64f;
				out[i + 1] = payload.oy() + d[i + 1] / 64f;
				out[i + 2] = payload.oz() + d[i + 2] / 64f;
			}
			particles = out;
		});

		WorldRenderEvents.AFTER_TRANSLUCENT.register(WaterClient::render);
	}

	private static void render(WorldRenderContext context) {
		float[] p = particles;
		VertexConsumerProvider consumers = context.consumers();
		if (p.length == 0 || consumers == null) return;
		MatrixStack ms = context.matrixStack();
		Vec3d cam = context.camera().getPos();
		ms.push();
		ms.translate(-cam.x, -cam.y, -cam.z);
		for (int i = 0; i + 2 < p.length; i += 3) {
			double x = p[i], y = p[i + 1], z = p[i + 2];
			DebugRenderer.drawBox(ms, consumers, x - HALF, y - HALF, z - HALF, x + HALF, y + HALF, z + HALF,
					0.22f, 0.46f, 0.95f, 0.85f);
		}
		ms.pop();
	}
}
