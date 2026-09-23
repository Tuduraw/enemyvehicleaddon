package com.example.enemyvehicleaddon.mixin.client;

import com.example.tudursvehiclemod.client.screen.DroneCenterConfigScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the block position a DroneCenterConfigScreen is showing - see DroneCenterConfigScreenMixin's own doc. */
@Mixin(value = DroneCenterConfigScreen.class, remap = false)
public interface DroneCenterConfigScreenAccessor {

	@Accessor("blockX")
	int enemyvehicleaddon$getBlockX();

	@Accessor("blockY")
	int enemyvehicleaddon$getBlockY();

	@Accessor("blockZ")
	int enemyvehicleaddon$getBlockZ();
}
