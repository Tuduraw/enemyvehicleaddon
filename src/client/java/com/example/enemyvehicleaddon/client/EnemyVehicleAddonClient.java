package com.example.enemyvehicleaddon.client;

import com.example.enemyvehicleaddon.client.screen.EnemyControlSettingsScreen;
import com.example.enemyvehicleaddon.network.EnemyControlSettingsOpenPayload;
import com.example.enemyvehicleaddon.registry.EnemyAddonEntities;
import com.example.tudursvehiclemod.client.render.DummyPilotEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class EnemyVehicleAddonClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// The prerequisite mod's own dummy pilot renderer - EnemyPilotEntity is a DummyPilotEntity, so skins/nametag behave identically. (Its model layer is registered by the prerequisite mod's own client initializer; renderers are only constructed later, at resource load.)
		EntityRendererRegistry.register(EnemyAddonEntities.ENEMY_PILOT, DummyPilotEntityRenderer::new);

		// A client connected to a remote server never fires SERVER_STARTING, yet builds Enemy Control Block entities from chunk data - see EnemyAddonBlockEntities#enemyvehicleaddon$ensureDroneCenterSupportsEnemyBlock().
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				com.example.enemyvehicleaddon.registry.EnemyAddonBlockEntities.enemyvehicleaddon$ensureDroneCenterSupportsEnemyBlock());

		ClientPlayNetworking.registerGlobalReceiver(EnemyControlSettingsOpenPayload.ID, (payload, context) ->
				context.client().execute(() -> context.client().setScreen(new EnemyControlSettingsScreen(
						payload.x(), payload.y(), payload.z(), payload.leashRadius(), payload.whitelist(), payload.templateArmed()))));
	}
}
