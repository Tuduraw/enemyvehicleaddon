package com.example.enemyvehicleaddon.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets EnemyControlBlockEntity replace the block entity type its superclass constructor hardcodes - see that class's own constructor doc. */
@Mixin(BlockEntity.class)
public interface BlockEntityAccessor {

	@Mutable
	@Accessor("type")
	void enemyvehicleaddon$setType(BlockEntityType<?> type);
}
