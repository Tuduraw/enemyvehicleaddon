package com.example.enemyvehicleaddon.network;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/** Client -> server: the full, edited enemy settings for the Enemy Control Block at (x, y, z). Not trusted - see EnemyAddonNetworking's handler and EnemyControlBlockEntity#enemyvehicleaddon$setEnemySettings(). */
public record EnemyControlSettingsUpdatePayload(int x, int y, int z, double leashRadius, List<String> whitelist, boolean templateArmed) implements CustomPayload {

	public static final CustomPayload.Id<EnemyControlSettingsUpdatePayload> ID =
			new CustomPayload.Id<>(Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_control_settings_update"));

	public static final PacketCodec<RegistryByteBuf, EnemyControlSettingsUpdatePayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, EnemyControlSettingsUpdatePayload::x,
			PacketCodecs.VAR_INT, EnemyControlSettingsUpdatePayload::y,
			PacketCodecs.VAR_INT, EnemyControlSettingsUpdatePayload::z,
			PacketCodecs.DOUBLE, EnemyControlSettingsUpdatePayload::leashRadius,
			PacketCodecs.STRING.collect(PacketCodecs.toList()), EnemyControlSettingsUpdatePayload::whitelist,
			PacketCodecs.BOOLEAN, EnemyControlSettingsUpdatePayload::templateArmed,
			EnemyControlSettingsUpdatePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
