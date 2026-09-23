package com.example.enemyvehicleaddon.mixin;

import com.example.enemyvehicleaddon.config.EnemyAddonConfig;
import com.example.enemyvehicleaddon.link.EnemyVehicles;
import com.example.tudursvehiclemod.entity.projectile.VehicleProjectileEntity;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps enemy vehicles' weapons from damaging blocks unless EnemyAddonConfig#enemyBlockDamage is on - see that option's doc. Only projectiles whose firing vehicle is (still) known and is an enemy vehicle are affected; players' own weapons never are.
 *
 * - Explosions: the prerequisite mod always creates the explosion itself without vanilla block destruction, and destroys blocks separately (only if the weapon is configured to) - so switching the projectile's own explosionDestroysBlocks/flaming off just before it explodes removes the craters and fire while keeping damage, knockback and effects.
 * - Piercing: a piercing projectile breaks the blocks it passes through (glass always); for an enemy it simply stops at the block instead, as if it did not pierce.
 *
 * remap = false: the targets are the prerequisite mod's own members, selected by name only (unique), so no Minecraft descriptor needs remapping. */
@Mixin(value = VehicleProjectileEntity.class, remap = false)
public abstract class VehicleProjectileEntityMixin {

	@Shadow
	protected boolean explosionDestroysBlocks;

	@Shadow
	protected boolean flaming;

	private boolean enemyvehicleaddon$isEnemyProjectileWithoutBlockDamage() {
		return !EnemyAddonConfig.get().enemyBlockDamage
				&& EnemyVehicles.isEnemy(((VehicleProjectileEntity) (Object) this).tudursvehiclemod$getFiringVehicle());
	}

	@Inject(method = "tudursvehiclemod$explodeIfConfigured", at = @At("HEAD"))
	private void enemyvehicleaddon$noEnemyBlockDamage(double x, double y, double z, CallbackInfo ci) {
		if (this.enemyvehicleaddon$isEnemyProjectileWithoutBlockDamage()) {
			this.explosionDestroysBlocks = false;
			this.flaming = false;
		}
	}

	@Inject(method = "tudursvehiclemod$tryPierceBlock", at = @At("HEAD"), cancellable = true)
	private void enemyvehicleaddon$noEnemyPiercing(BlockHitResult blockHitResult, CallbackInfoReturnable<Boolean> cir) {
		if (this.enemyvehicleaddon$isEnemyProjectileWithoutBlockDamage()) {
			cir.setReturnValue(false);
		}
	}
}
