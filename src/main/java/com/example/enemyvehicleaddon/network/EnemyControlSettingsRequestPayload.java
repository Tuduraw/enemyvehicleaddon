package com.example.enemyvehicleaddon.network;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: "open the enemy settings screen for the Enemy Control Block at (x, y, z)", sent when the player presses the button EnemyDroneCenterConfigScreenMixin adds to the prerequisite mod's own Drone Center config screen (see that mixin's own doc for why this replaced the earlier sneak+right-click shortcut). Mirrors the request/response shape of the prerequisite mod's own DummyPilotConfigRequestPayload/DummyPilotConfigOpenPayload pair. The server re-checks EnemyControlBlockEntity#enemyvehicleaddon$canManage() before replying (this payload only ever means "I clicked the button" - not "I am allowed"), and stays silent (see EnemyAddonNetworking's handler) rather than opening anything for a player who isn't. */
public record EnemyControlSettingsRequestPayload(int x, int y, int z) implements CustomPayload {

	public static final CustomPayload.Id<EnemyControlSettingsRequestPayload> ID =
			new CustomPayload.Id<>(Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_control_settings_request"));

	public static final PacketCodec<RegistryByteBuf, EnemyControlSettingsRequestPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, EnemyControlSettingsRequestPayload::x,
			PacketCodecs.VAR_INT, EnemyControlSettingsRequestPayload::y,
			PacketCodecs.VAR_INT, EnemyControlSettingsRequestPayload::z,
			EnemyControlSettingsRequestPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
