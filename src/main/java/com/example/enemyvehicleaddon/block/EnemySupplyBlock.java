package com.example.enemyvehicleaddon.block;

import com.example.enemyvehicleaddon.registry.EnemyAddonBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Enemy Supply Block - refuels, rearms and repairs stationary enemy vehicles around it (see EnemySupplyBlockEntity). Placed near an Enemy Control Block's Home Point, it lets an enemy aircraft that landed to resupply (the prerequisite mod's auto-disable) take off again on its own (AUTO_RESUME, this addon's default for Enemy Control Blocks). Destroying it grounds such aircraft - a deliberate strategic target. No GUI: range and behaviour come from config/enemyvehicleaddon.json. */
public class EnemySupplyBlock extends BlockWithEntity {

	public static final MapCodec<EnemySupplyBlock> CODEC = createCodec(EnemySupplyBlock::new);

	public EnemySupplyBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new EnemySupplyBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() ? null : validateTicker(type, EnemyAddonBlockEntities.ENEMY_SUPPLY, EnemySupplyBlockEntity::tick);
	}
}
