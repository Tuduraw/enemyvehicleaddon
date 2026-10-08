package com.example.enemyvehicleaddon.mixin;

import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import com.example.enemyvehicleaddon.entity.EnemyPilotEntity;
import com.example.enemyvehicleaddon.registry.EnemyAddonEntities;
import com.example.tudursvehiclemod.block.DroneCenterBlockEntity;
import com.example.tudursvehiclemod.entity.DummyPilotEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pilot creation - the one place the Drone Center creates its dummy pilot (a private method, with the DummyPilotEntity type hardcoded) is wrapped so an Enemy Control Block seats an EnemyPilotEntity instead, while every other step around it (evicting seat 0, skin/name/combat settings, spawning, mounting, re-spawning after a reload) stays the prerequisite mod's own code. Applied ONLY when the block entity is an EnemyControlBlockEntity (a plain Drone Center is untouched).
 *
 * Chunk loading is no longer changed here: EnemyControlBlockEntity overrides the prerequisite mod's DroneCenterBlockEntity#tudursvehiclemod$forceLoadsChunks() hook instead (see there). This mixin used to wrap every setChunkForced() call the Drone Center made, which broke - and crashed the game at startup - once the prerequisite mod routed those calls through its ChunkForceTracker.
 *
 * remap = false at class level: the target class/method are the prerequisite mod's own (not in the Minecraft mappings), and the NEW target is a bare class name (nothing to remap). */
@Mixin(value = DroneCenterBlockEntity.class, remap = false)
public abstract class DroneCenterBlockEntityMixin {

	@WrapOperation(method = "tudursvehiclemod$updateDummyPilot",
			at = @At(value = "NEW", target = "com/example/tudursvehiclemod/entity/DummyPilotEntity"))
	private DummyPilotEntity enemyvehicleaddon$createEnemyPilot(EntityType<? extends LivingEntity> type, World world,
			Operation<DummyPilotEntity> original) {
		if ((Object) this instanceof EnemyControlBlockEntity enemyControl) {
			EnemyPilotEntity pilot = new EnemyPilotEntity(EnemyAddonEntities.ENEMY_PILOT, world);
			enemyControl.enemyvehicleaddon$applySettingsTo(pilot);
			return pilot;
		}
		return original.call(type, world);
	}
}
