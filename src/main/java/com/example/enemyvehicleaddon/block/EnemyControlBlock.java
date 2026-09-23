package com.example.enemyvehicleaddon.block;

import com.example.enemyvehicleaddon.link.EnemyLinkManager;
import com.example.enemyvehicleaddon.registry.EnemyAddonBlockEntities;
import com.example.tudursvehiclemod.block.DroneCenterBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Enemy Control Block - the prerequisite mod's Drone Center, reused as-is (config screen, slot UI, redstone control, chunk force-loading, cleanup on removal all come from DroneCenterBlock, and the prerequisite mod's own UseBlockCallback already dispatches to any DroneCenterBlock subclass), with only these differences:
 *
 * - its block entity is an EnemyControlBlockEntity (whose dummy pilot is an EnemyPilotEntity - see that class's doc),
 * - the placing player is recorded as its owner,
 * - it does not force-load chunks the way a Drone Center does; its vehicle is dormant unless players are near it or it is pursuing a target (see link.EnemyLinkManager),
 * - sneak + right-click opens this block's own enemy settings (whitelist, leash radius), and only the owner or a creative-mode player may operate the block at all - see EnemyControlInteraction. */
public class EnemyControlBlock extends DroneCenterBlock {

	public static final MapCodec<EnemyControlBlock> CODEC = createCodec(EnemyControlBlock::new);

	/** Which way the block was facing when placed - exists for structure templates (see EnemyControlBlockEntity's template section): every way of placing a saved structure (structure block, /place, jigsaw, WorldEdit, ...) rotates block states but never block entity data, so comparing this property with the facing recorded when the template was armed is how the relative routes stored in the block entity learn how far the structure was rotated. */
	public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

	public EnemyControlBlock(Settings settings) {
		super(settings);
		this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(FACING);
	}

	/** Faces the placing player, like a furnace. */
	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, BlockRotation rotation) {
		return state.with(FACING, rotation.rotate(state.get(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, BlockMirror mirror) {
		return state.rotate(mirror.getRotation(state.get(FACING)));
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new EnemyControlBlockEntity(pos, state);
	}

	/** DroneCenterBlock's own getTicker() only matches the DRONE_CENTER type, which this block's entity does not carry (see EnemyControlBlockEntity's constructor doc). */
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return validateTicker(type, EnemyAddonBlockEntities.ENEMY_CONTROL, EnemyControlBlockEntity::tick);
	}

	/** Drops this block's link record (EnemyLinkManager) on top of the inherited cleanup (which unlinks the vehicle if it is currently loaded - an unloaded one is caught later by its own pilot, see EnemyPilotEntity#tick()). */
	@Override
	public void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
		super.onStateReplaced(state, world, pos, moved);
		if (!world.getBlockState(pos).isOf(this)) {
			EnemyLinkManager.onBlockRemoved(world, pos);
		}
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (!world.isClient() && placer instanceof PlayerEntity player
				&& world.getBlockEntity(pos) instanceof EnemyControlBlockEntity blockEntity) {
			blockEntity.enemyvehicleaddon$setOwner(player.getUuid());
		}
	}
}
