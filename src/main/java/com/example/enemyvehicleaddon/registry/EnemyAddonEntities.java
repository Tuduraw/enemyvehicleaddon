package com.example.enemyvehicleaddon.registry;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import com.example.enemyvehicleaddon.entity.EnemyPilotEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class EnemyAddonEntities {

	/** A separate type from the prerequisite mod's DUMMY_PILOT (same size, same renderer) so that a pilot saved to disk reloads as an EnemyPilotEntity - which, with no leash center known yet, deliberately does nothing until its Enemy Control Block replaces it - rather than as a plain DummyPilotEntity that would start engaging hostile mobs on its own. */
	public static EntityType<EnemyPilotEntity> ENEMY_PILOT;

	private EnemyAddonEntities() {
	}

	public static void register() {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_pilot"));
		// Matches the prerequisite mod's own DUMMY_PILOT registration (0.6 x 1.8, tracking range 32).
		ENEMY_PILOT = Registry.register(Registries.ENTITY_TYPE, key,
				EntityType.Builder.<EnemyPilotEntity>create(EnemyPilotEntity::new, SpawnGroup.MISC)
						.dimensions(0.6f, 1.8f)
						.maxTrackingRange(32)
						.build(key));
		FabricDefaultAttributeRegistry.register(ENEMY_PILOT, com.example.tudursvehiclemod.entity.DummyPilotEntity.createAttributes());
	}
}
