package com.example.enemyvehicleaddon.mixin;

import com.example.tudursvehiclemod.entity.DummyPilotEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** DummyPilotEntity only re-picks its target every 20 ticks and keeps using the cached one in between - EnemyPilotEntity drops a cached target the moment it leaves the leash radius (or becomes whitelisted, creative, ...) rather than chasing it for up to another second. */
@Mixin(value = DummyPilotEntity.class, remap = false)
public interface DummyPilotEntityAccessor {

	@Accessor("currentTarget")
	LivingEntity enemyvehicleaddon$getCurrentTarget();

	@Accessor("currentTarget")
	void enemyvehicleaddon$setCurrentTarget(LivingEntity target);
}
