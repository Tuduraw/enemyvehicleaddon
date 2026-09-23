package com.example.enemyvehicleaddon.mixin;

import com.example.enemyvehicleaddon.config.EnemyAddonConfig;
import com.example.enemyvehicleaddon.link.EnemyVehicles;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The explosion of a DESTROYED enemy vehicle: the prerequisite mod creates it with the vehicle itself as the source and ExplosionSourceType.MOB (which destroys blocks, subject to mobGriefing). Unless EnemyAddonConfig#enemyBlockDamage is on, it is re-issued with NONE and no fire - same damage and effects, no crater. Weapon explosions never reach this case (their source is the projectile, and they already use NONE - see VehicleProjectileEntityMixin). */
@Mixin(World.class)
public abstract class WorldExplosionMixin {

	@Inject(method = "createExplosion(Lnet/minecraft/entity/Entity;DDDFZLnet/minecraft/world/World$ExplosionSourceType;)V",
			at = @At("HEAD"), cancellable = true)
	private void enemyvehicleaddon$noEnemyWreckBlockDamage(Entity entity, double x, double y, double z, float power, boolean createFire,
			World.ExplosionSourceType sourceType, CallbackInfo ci) {
		if (sourceType == World.ExplosionSourceType.NONE || EnemyAddonConfig.get().enemyBlockDamage || !EnemyVehicles.isEnemy(entity)) {
			return;
		}
		ci.cancel();
		((World) (Object) this).createExplosion(entity, x, y, z, power, false, World.ExplosionSourceType.NONE);
	}
}
