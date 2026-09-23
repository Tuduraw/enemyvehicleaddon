package com.example.enemyvehicleaddon.mixin;

import com.example.tudursvehiclemod.entity.AircraftEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/** An aircraft's dummy-pilot attack runs on the prerequisite mod's Carrier-lock pursuit, which - once locked - keeps chasing until the target dies, disappears or shakes it off for 30 s, regardless of whether the pilot still wants it. Clearing the lock is the only way to hand the aircraft back to its normal route immediately when the target leaves the leash radius (see EnemyPilotEntity#enemyvehicleaddon$releaseInvalidLock()). */
@Mixin(value = AircraftEntity.class, remap = false)
public interface AircraftEntityAccessor {

	@Accessor("carrierLockedTargetUuid")
	UUID enemyvehicleaddon$getCarrierLockedTargetUuid();

	@Accessor("carrierLockedTargetUuid")
	void enemyvehicleaddon$setCarrierLockedTargetUuid(UUID targetUuid);
}
