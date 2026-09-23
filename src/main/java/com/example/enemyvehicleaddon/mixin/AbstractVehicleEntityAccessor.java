package com.example.enemyvehicleaddon.mixin;

import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The prerequisite mod's own per-second supply steps (the ones its supply vehicles apply to a recipient) are private - EnemySupplyBlockEntity reuses them rather than reimplementing them, so an Enemy Supply Block supplies by exactly the same rules as a supply vehicle. */
@Mixin(value = AbstractVehicleEntity.class, remap = false)
public interface AbstractVehicleEntityAccessor {

	/** One supply step: tops up the magazine by 10% of its size, or - once the magazine is full - the reserve by 10% of MaxAmmo, never beyond the weapon's total capacity (Carrier aircraft in flight included). Requires the prerequisite mod with its reserve-refill fix; older versions only refill magazines. */
	@Invoker("tudursvehiclemod$receiveAmmoSupply")
	void enemyvehicleaddon$receiveAmmoSupply();

	/** One supply step: restores 2% of max health (capped at max). */
	@Invoker("tudursvehiclemod$receiveHealthSupply")
	void enemyvehicleaddon$receiveHealthSupply();
}
