package com.example.enemyvehicleaddon.link;

import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;

/** "Is this an enemy vehicle?" - the vehicle currently registered for some Enemy Control Block in its dimension (EnemyLinkState), which also covers a destroyed one until it is respawned. */
public final class EnemyVehicles {

	private EnemyVehicles() {
	}

	public static boolean isEnemy(Entity entity) {
		return entity instanceof AbstractVehicleEntity vehicle
				&& vehicle.getEntityWorld() instanceof ServerWorld world
				&& EnemyLinkState.get(world).isEnemyVehicle(vehicle.getUuid());
	}
}
