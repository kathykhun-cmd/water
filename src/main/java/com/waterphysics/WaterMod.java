package com.waterphysics;

import com.waterphysics.net.ParticlesPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WaterMod implements ModInitializer {
	public static final String MOD_ID = "waterphysics";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Hard caps. These are what keeps "infinite" water from ever lagging the server. */
	public static final int MAX_ACTIVE = 20000;   // moving particles per dimension (settled water costs nothing)
	public static final int MAX_BALL = 6;         // /water ball <1..MAX_BALL>
	public static final int MAX_CONVERT = 16;     // /water convert <radius>

	public static LevelWaterBlock LEVEL_WATER;
	public static WaterEmitterItem EMITTER;

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LEVEL_WATER = Registry.register(Registries.BLOCK, id("level_water"), new LevelWaterBlock(
				AbstractBlock.Settings.create()
						.mapColor(MapColor.WATER_BLUE)
						.replaceable()
						.noCollision()
						.nonOpaque()
						.strength(100.0f)
						.dropsNothing()
						.pistonBehavior(PistonBehavior.DESTROY)));

		EMITTER = Registry.register(Registries.ITEM, id("water_emitter"),
				new WaterEmitterItem(new Item.Settings().maxCount(1)));

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(EMITTER));

		PayloadTypeRegistry.playS2C().register(ParticlesPayload.ID, ParticlesPayload.CODEC);

		FluidManager.init();
		CommandRegistrationCallback.EVENT.register(WaterCommands::register);
	}
}
