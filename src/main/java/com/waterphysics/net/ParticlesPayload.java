package com.waterphysics.net;

import com.waterphysics.WaterMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Particle positions relative to (ox,oy,oz), quantised to 1/64 block (6 bytes per particle). */
public record ParticlesPayload(int ox, int oy, int oz, short[] data) implements CustomPayload {
	public static final CustomPayload.Id<ParticlesPayload> ID = new CustomPayload.Id<>(WaterMod.id("particles"));
	public static final PacketCodec<RegistryByteBuf, ParticlesPayload> CODEC =
			PacketCodec.of(ParticlesPayload::write, ParticlesPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeInt(ox);
		buf.writeInt(oy);
		buf.writeInt(oz);
		buf.writeVarInt(data.length);
		for (short s : data) buf.writeShort(s);
	}

	private static ParticlesPayload read(RegistryByteBuf buf) {
		int ox = buf.readInt(), oy = buf.readInt(), oz = buf.readInt();
		int n = buf.readVarInt();
		short[] d = new short[n];
		for (int i = 0; i < n; i++) d[i] = buf.readShort();
		return new ParticlesPayload(ox, oy, oz, d);
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
