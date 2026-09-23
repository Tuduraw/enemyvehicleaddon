package com.example.enemyvehicleaddon;

import com.example.enemyvehicleaddon.network.EnemyAddonNetworking;
import com.example.enemyvehicleaddon.registry.EnemyAddonBlockEntities;
import com.example.enemyvehicleaddon.registry.EnemyAddonBlocks;
import com.example.enemyvehicleaddon.registry.EnemyAddonEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

/** Enemy Addon for Tudur's Vehicle Mod - see README.md / IMPLEMENTATION_NOTES.md for the full picture.
 *
 * Adds the Enemy Control Block (a Drone Center variant, with respawn of lost vehicles) plus the enemy pilot entity it seats and the settings screen/payloads for its own whitelist/leash options, and the Enemy Supply Block. Everything else (routes, formation, dummy pilot look/weapon settings, auto-disable, redstone control) is the prerequisite mod's own Drone Center implementation, reused unchanged - except chunk loading, which follows the dormancy policy in link.EnemyLinkManager. */
public class EnemyVehicleAddon implements ModInitializer {

	public static final String MOD_ID = "enemyvehicleaddon";

	@Override
	public void onInitialize() {
		// NOTE: this initializer can run BEFORE the prerequisite mod's own (Fabric does not order entrypoints by dependency) - nothing here may read a registry object the prerequisite mod assigns in its onInitialize() (ModBlockEntities.*, ModItems.*, ...). Static final constants such as ModItemGroups.VEHICLES are fine.
		// Block before block entity type (the type's builder references the block), block entity type before anything can instantiate it.
		com.example.enemyvehicleaddon.config.EnemyAddonConfig.load();
		EnemyAddonBlocks.register();
		EnemyAddonBlockEntities.register();
		EnemyAddonEntities.register();
		com.example.enemyvehicleaddon.structure.EnemyStructures.register();
		EnemyAddonNetworking.register();
		com.example.enemyvehicleaddon.link.EnemyLinkManager.register();

		// Deferred until every mod has initialized - see that method's doc (this initializer may run before the prerequisite mod's).
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server ->
				EnemyAddonBlockEntities.enemyvehicleaddon$ensureDroneCenterSupportsEnemyBlock());

		// Listed in the prerequisite mod's own creative tab, right beside the Drone Center it is a variant of.
		ItemGroupEvents.modifyEntriesEvent(com.example.tudursvehiclemod.item.ModItemGroups.VEHICLES)
				.register(entries -> {
					entries.add(EnemyAddonBlocks.ENEMY_CONTROL);
					entries.add(EnemyAddonBlocks.ENEMY_SUPPLY);
				});
	}
}
