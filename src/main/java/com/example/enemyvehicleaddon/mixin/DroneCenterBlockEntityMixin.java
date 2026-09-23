package com.example.enemyvehicleaddon.mixin;

import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import com.example.enemyvehicleaddon.entity.EnemyPilotEntity;
import com.example.enemyvehicleaddon.registry.EnemyAddonEntities;
import com.example.tudursvehiclemod.block.DroneCenterBlockEntity;
import com.example.tudursvehiclemod.entity.DummyPilotEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Two changes to the prerequisite mod's Drone Center block entity, both applied ONLY when that block entity is an EnemyControlBlockEntity (a plain Drone Center is untouched):
 *
 * 1. Pilot creation - the one place the Drone Center creates its dummy pilot (a private method, with the DummyPilotEntity type hardcoded) is wrapped so an Enemy Control Block seats an EnemyPilotEntity instead, while every other step around it (evicting seat 0, skin/name/combat settings, spawning, mounting, re-spawning after a reload) stays the prerequisite mod's own code.
 *
 * 2. Chunk loading - every setChunkForced() the Drone Center makes (its own chunk permanently, a 3x3 around the vehicle while active, the vehicle's last chunk while searching) is skipped (its accompanying synchronous getChunk() pre-load is left alone - it only touches chunks around a vehicle that is already ticking, and they are not kept loaded afterwards). Enemy chunk loading follows the dormancy policy in link.EnemyLinkManager instead.
 *
 * remap = false at class level: the target class/methods are the prerequisite mod's own (not in the Minecraft mappings). The NEW target is a bare class name (nothing to remap); the INVOKE targets are Minecraft methods and so opt back into remapping individually (remap = true on their @At). */
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

	/** Instance methods that force/unforce chunks. */
	@WrapOperation(method = {
			"tudursvehiclemod$updateChunkForceLoading",
			"tudursvehiclemod$releaseForcedChunk",
			"tudursvehiclemod$releaseCurrentlyForcedChunk",
			"tudursvehiclemod$rememberVehicleChunk"
	}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;setChunkForced(IIZ)Z", remap = true))
	private boolean enemyvehicleaddon$skipForcedChunk(ServerWorld world, int x, int z, boolean forced, Operation<Boolean> original) {
		if ((Object) this instanceof EnemyControlBlockEntity) {
			return false;
		}
		return original.call(world, x, z, forced);
	}

	/** The static tick() - searches for an unloaded vehicle by forcing its last chunk. */
	@WrapOperation(method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;setChunkForced(IIZ)Z", remap = true))
	private static boolean enemyvehicleaddon$skipForcedChunkInTick(ServerWorld world, int x, int z, boolean forced, Operation<Boolean> original,
			@Local(argsOnly = true) DroneCenterBlockEntity blockEntity) {
		if (blockEntity instanceof EnemyControlBlockEntity) {
			return false;
		}
		return original.call(world, x, z, forced);
	}
}
