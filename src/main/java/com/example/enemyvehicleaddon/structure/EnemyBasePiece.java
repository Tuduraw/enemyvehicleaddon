package com.example.enemyvehicleaddon.structure;

import com.example.enemyvehicleaddon.block.EnemyControlBlock;
import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import com.example.enemyvehicleaddon.registry.EnemyAddonBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.util.EnumSet;

/** The single piece of an enemy base (EnemyBaseStructure). Everything it draws is a deterministic function of what it stores - anchor (world x/z of local (0, 0)), ground level, rotation and the definition - so each chunk it overlaps draws its own part independently, in any order, and the parts line up.
 *
 * Per column (only inside the chunk currently being generated):
 * - inside the footprint: levelled to the ground level - filled up (fill block, deep fill block below 4 layers) or cut down, everything above cleared (water included), topped with the definition's surface at y = -1;
 * - in the skirt around it (terrain.skirt blocks): blended linearly from the ground level back to the natural height, cutting/filling solid ground only (water left alone), topped with the terrain surface block where it changed.
 * Then boxes, blocks and units are drawn, each clipped to the chunk, and finally the chunk's heightmaps are recomputed (see refreshHeightmaps()). Units place an Enemy Control Block holding its (rotated) EnemyUnitSpec as a pending setup - nothing else happens until that block first ticks, i.e. until a player comes near (see EnemyControlBlockEntity). */
public class EnemyBasePiece extends StructurePiece {

	private final int anchorX;
	private final int anchorZ;
	private final int groundY;
	private final int quarterTurns;
	private final EnemyBaseDefinition definition;

	public EnemyBasePiece(int anchorX, int anchorZ, int groundY, int quarterTurns, EnemyBaseDefinition definition) {
		super(EnemyStructures.ENEMY_BASE_PIECE, 0, computeBox(anchorX, anchorZ, groundY, quarterTurns, definition));
		this.anchorX = anchorX;
		this.anchorZ = anchorZ;
		this.groundY = groundY;
		this.quarterTurns = quarterTurns;
		this.definition = definition;
	}

	public EnemyBasePiece(NbtCompound nbt) {
		super(EnemyStructures.ENEMY_BASE_PIECE, nbt);
		this.anchorX = nbt.getInt("AnchorX", 0);
		this.anchorZ = nbt.getInt("AnchorZ", 0);
		this.groundY = nbt.getInt("GroundY", 64);
		this.quarterTurns = nbt.getInt("QuarterTurns", 0);
		this.definition = nbt.get("Definition", EnemyBaseDefinition.CODEC)
				.orElseThrow(() -> new IllegalStateException("Enemy base piece without a readable definition"));
	}

	@Override
	protected void writeNbt(StructureContext context, NbtCompound nbt) {
		nbt.putInt("AnchorX", this.anchorX);
		nbt.putInt("AnchorZ", this.anchorZ);
		nbt.putInt("GroundY", this.groundY);
		nbt.putInt("QuarterTurns", this.quarterTurns);
		nbt.put("Definition", EnemyBaseDefinition.CODEC, this.definition);
	}

	private static BlockBox computeBox(int anchorX, int anchorZ, int groundY, int quarterTurns, EnemyBaseDefinition definition) {
		int skirt = definition.terrain().skirt();
		int[] corner = EnemyRotation.rotate(definition.sizeX() - 1, definition.sizeZ() - 1, quarterTurns);
		int minX = Math.min(anchorX, anchorX + corner[0]) - skirt;
		int maxX = Math.max(anchorX, anchorX + corner[0]) + skirt;
		int minZ = Math.min(anchorZ, anchorZ + corner[1]) - skirt;
		int maxZ = Math.max(anchorZ, anchorZ + corner[1]) + skirt;
		return new BlockBox(minX, groundY - definition.terrain().maxFill() - 6, minZ,
				maxX, groundY + Math.max(definition.maxLocalY(), definition.terrain().maxCut()) + 2, maxZ);
	}

	private BlockPos toWorld(int localX, int localY, int localZ) {
		int[] xz = EnemyRotation.rotate(localX, localZ, this.quarterTurns);
		return new BlockPos(this.anchorX + xz[0], this.groundY + localY, this.anchorZ + xz[1]);
	}

	@Override
	public void generate(StructureWorldAccess world, StructureAccessor structureAccessor, ChunkGenerator chunkGenerator,
			Random random, BlockBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
		long start = System.nanoTime();
		this.draw(world, chunkBox);
		if (com.example.enemyvehicleaddon.config.EnemyAddonConfig.get().logGenerationTimings) {
			org.slf4j.LoggerFactory.getLogger("enemyvehicleaddon").info("[timings] enemy base chunk {}: {} ms", chunkPos,
					String.format("%.2f", (System.nanoTime() - start) / 1.0e6));
		}
	}

	private void draw(StructureWorldAccess world, BlockBox chunkBox) {
		this.gradeTerrain(world, chunkBox);
		BlockRotation rotation = EnemyRotation.toBlockRotation(this.quarterTurns);
		for (EnemyBaseDefinition.Box box : this.definition.boxes()) {
			this.drawBox(world, chunkBox, box, rotation);
		}
		for (EnemyBaseDefinition.Block block : this.definition.blocks()) {
			this.place(world, chunkBox, block.pos().getX(), block.pos().getY(), block.pos().getZ(), block.state().rotate(rotation));
		}
		for (EnemyUnitSpec unit : this.definition.units()) {
			BlockPos pos = this.toWorld(unit.block().getX(), unit.block().getY(), unit.block().getZ());
			if (!chunkBox.contains(pos)) {
				continue;
			}
			BlockState state = EnemyAddonBlocks.ENEMY_CONTROL.getDefaultState()
					.with(EnemyControlBlock.FACING, rotation.rotate(Direction.NORTH));
			world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
			if (world.getBlockEntity(pos) instanceof EnemyControlBlockEntity blockEntity) {
				blockEntity.enemyvehicleaddon$setPendingSetup(unit.rotated(this.quarterTurns));
			}
		}
		// Last, after everything is drawn - see refreshHeightmaps()'s doc.
		refreshHeightmaps(world, chunkBox);
	}

	private void place(StructureWorldAccess world, BlockBox chunkBox, int localX, int localY, int localZ, BlockState state) {
		BlockPos pos = this.toWorld(localX, localY, localZ);
		if (chunkBox.contains(pos)) {
			world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
		}
	}

	private void drawBox(StructureWorldAccess world, BlockBox chunkBox, EnemyBaseDefinition.Box box, BlockRotation rotation) {
		int minX = Math.min(box.from().getX(), box.to().getX());
		int maxX = Math.max(box.from().getX(), box.to().getX());
		int minY = Math.min(box.from().getY(), box.to().getY());
		int maxY = Math.max(box.from().getY(), box.to().getY());
		int minZ = Math.min(box.from().getZ(), box.to().getZ());
		int maxZ = Math.max(box.from().getZ(), box.to().getZ());
		// Every chunk of the base calls this for every box - skip the whole volume at once when it does not reach this chunk.
		if (!BlockBox.create(this.toWorld(minX, minY, minZ), this.toWorld(maxX, maxY, maxZ)).intersects(chunkBox)) {
			return;
		}
		BlockState state = box.block().rotate(rotation);
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				boolean side = x == minX || x == maxX || z == minZ || z == maxZ;
				for (int y = minY; y <= maxY; y++) {
					boolean draw = switch (box.mode()) {
						case "hollow" -> side || y == minY || y == maxY;
						case "walls" -> side;
						default -> true;
					};
					if (draw) {
						this.place(world, chunkBox, x, y, z, state);
					}
				}
			}
		}
	}

	private void gradeTerrain(StructureWorldAccess world, BlockBox chunkBox) {
		EnemyBaseDefinition.Terrain terrain = this.definition.terrain();
		int skirt = terrain.skirt();
		int inverse = 4 - this.quarterTurns;
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		BlockBox box = this.getBoundingBox();
		for (int x = Math.max(box.getMinX(), chunkBox.getMinX()); x <= Math.min(box.getMaxX(), chunkBox.getMaxX()); x++) {
			for (int z = Math.max(box.getMinZ(), chunkBox.getMinZ()); z <= Math.min(box.getMaxZ(), chunkBox.getMaxZ()); z++) {
				int[] local = EnemyRotation.rotate(x - this.anchorX, z - this.anchorZ, inverse);
				int outsideX = local[0] < 0 ? -local[0] : Math.max(0, local[0] - (this.definition.sizeX() - 1));
				int outsideZ = local[1] < 0 ? -local[1] : Math.max(0, local[1] - (this.definition.sizeZ() - 1));
				int outside = Math.max(outsideX, outsideZ);
				if (outside > skirt) {
					continue;
				}
				// "First free block above the ground" - ground ignoring water, and the top including water.
				int ground = world.getTopY(Heightmap.Type.OCEAN_FLOOR_WG, x, z);
				int top = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z);
				boolean inside = outside == 0;
				int target = inside ? this.groundY
						: (int) Math.round(this.groundY + (ground - this.groundY) * (outside / (double) (skirt + 1)));
				if (!inside && ground == target) {
					continue;
				}
				BlockState surface = inside ? this.definition.surfaceAt(local[0], local[1]) : terrain.surfaceBlock();
				// Fill up to the new surface.
				for (int y = ground; y < target - 1; y++) {
					world.setBlockState(mutable.set(x, y, z), y < target - 4 ? terrain.deepFillBlock() : terrain.fillBlock(), Block.NOTIFY_LISTENERS);
				}
				world.setBlockState(mutable.set(x, target - 1, z), surface, Block.NOTIFY_LISTENERS);
				// Cut down to it: inside the footprint everything above goes (water too); in the skirt only solid ground.
				int clearTo = inside ? Math.max(ground, top) : ground;
				for (int y = target; y < clearTo; y++) {
					world.setBlockState(mutable.set(x, y, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
				// Whatever stood ON the old surface - see CLEAR_ABOVE_HEIGHT's doc. Only where the ground was actually lowered (or inside the footprint, which must be clear anyway).
				if (inside || ground > target) {
					int clearTop = Math.min(Math.max(ground, top) + CLEAR_ABOVE_HEIGHT, world.getTopYInclusive());
					for (int y = Math.max(target, clearTo); y <= clearTop; y++) {
						if (!world.getBlockState(mutable.set(x, y, z)).isAir()) {
							world.setBlockState(mutable, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
						}
					}
				}
			}
		}
	}

	/** How far above the old ground level cleared columns are also emptied: vegetation placed from a NEIGHBOURING chunk (grass patches, flowers, trees reaching across the chunk border) can land on the original terrain before this chunk's own base part is drawn - after grading lowered the ground under it, it would float. Tall enough for ordinary trees. */
	private static final int CLEAR_ABOVE_HEIGHT = 32;

	/** Recomputes this chunk's heightmaps after grading. Vegetation is placed after structures, at positions taken from the chunk's heightmaps - but a chunk still being generated only keeps some heightmaps up to date on block changes, so the ones vegetation placement reads could still describe the ORIGINAL terrain: plants were placed at the old surface height, hanging in the air above the lowered ground (or buried in the raised one). Recomputing every heightmap the chunk has makes later placement see the graded surface. */
	private static void refreshHeightmaps(StructureWorldAccess world, BlockBox chunkBox) {
		Chunk chunk = world.getChunk(chunkBox.getMinX() >> 4, chunkBox.getMinZ() >> 4);
		EnumSet<Heightmap.Type> types = EnumSet.noneOf(Heightmap.Type.class);
		for (Heightmap.Type type : Heightmap.Type.values()) {
			if (chunk.hasHeightmap(type)) {
				types.add(type);
			}
		}
		if (!types.isEmpty()) {
			Heightmap.populateHeightmaps(chunk, types);
		}
	}
}
