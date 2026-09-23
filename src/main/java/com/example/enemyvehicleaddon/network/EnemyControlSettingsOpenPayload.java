package com.example.enemyvehicleaddon.network;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/** Server -> client: open the enemy settings screen for the Enemy Control Block at (x, y, z), pre-filled with its current values. */
public record EnemyControlSettingsOpenPayload(int x, int y, int z, double leashRadius, List<String> whitelist, boolean templateArmed) implements CustomPayload {

	public static final CustomPayload.Id<EnemyControlSettingsOpenPayload> ID =
			new CustomPayload.Id<>(Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_control_settings_open"));

	public static final PacketCodec<RegistryByteBuf, EnemyControlSettingsOpenPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, EnemyControlSettingsOpenPayload::x,
			PacketCodecs.VAR_INT, EnemyControlSettingsOpenPayload::y,
			PacketCodecs.VAR_INT, EnemyControlSettingsOpenPayload::z,
			PacketCodecs.DOUBLE, EnemyControlSettingsOpenPayload::leashRadius,
			PacketCodecs.STRING.collect(PacketCodecs.toList()), EnemyControlSettingsOpenPayload::whitelist,
			PacketCodecs.BOOLEAN, EnemyControlSettingsOpenPayload::templateArmed,
			EnemyControlSettingsOpenPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
