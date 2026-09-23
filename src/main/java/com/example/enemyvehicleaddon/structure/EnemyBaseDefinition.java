package com.example.enemyvehicleaddon.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Everything that makes up one kind of enemy base, as written in its datapack structure JSON under "base" (see data/enemyvehicleaddon/worldgen/structure/ for the bundled samples).
 *
 * Local frame: x in [0, size_x), z in [0, size_z) across the base's rectangular footprint, y relative to the base's ground level (y = 0 is the first block ABOVE the graded ground surface; the surface itself is y = -1). The whole base is rotated as one piece in 90-degree steps when generated - see EnemyBaseStructure.
 *
 * Contents are drawn in this order, later entries overwriting earlier ones: grading (EnemyBaseStructure's terrain rules), `surfaces` (the ground surface block, y = -1), `boxes`, `blocks`, then `units` (the Enemy Control Blocks). */
public record EnemyBaseDefinition(int sizeX, int sizeZ, Terrain terrain, List<Surface> surfaces, List<Box> boxes,
		List<Block> blocks, List<EnemyUnitSpec> units) {

	/** The footprint must stay within the reach of one structure start (vanilla only places pieces within about 8 chunks of the start chunk): half the size plus the skirt must stay within 128 blocks. */
	public static final int MAX_SIZE = 208;

	/** Terrain rules - see EnemyBaseStructure for how they are applied. */
	public record Terrain(int maxCut, int maxFill, float maxWaterFraction, int skirt, BlockState surfaceBlock, BlockState fillBlock, BlockState deepFillBlock) {
		public static final Terrain DEFAULT = new Terrain(16, 12, 0.05f, 12, Blocks.GRASS_BLOCK.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState());
		public static final Codec<Terrain> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.intRange(0, 64).optionalFieldOf("max_cut", 16).forGetter(Terrain::maxCut),
				Codec.intRange(0, 64).optionalFieldOf("max_fill", 12).forGetter(Terrain::maxFill),
				Codec.floatRange(0f, 1f).optionalFieldOf("max_water_fraction", 0.05f).forGetter(Terrain::maxWaterFraction),
				Codec.intRange(0, 16).optionalFieldOf("skirt", 12).forGetter(Terrain::skirt),
				BlockState.CODEC.optionalFieldOf("surface_block", Blocks.GRASS_BLOCK.getDefaultState()).forGetter(Terrain::surfaceBlock),
				BlockState.CODEC.optionalFieldOf("fill_block", Blocks.DIRT.getDefaultState()).forGetter(Terrain::fillBlock),
				BlockState.CODEC.optionalFieldOf("deep_fill_block", Blocks.STONE.getDefaultState()).forGetter(Terrain::deepFillBlock)
		).apply(i, Terrain::new));
	}

	/** Ground surface (y = -1) over the rectangle from..to (x/z, inclusive). */
	public record Surface(int fromX, int fromZ, int toX, int toZ, BlockState block) {
		public static final Codec<Surface> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("from_x").forGetter(Surface::fromX),
				Codec.INT.fieldOf("from_z").forGetter(Surface::fromZ),
				Codec.INT.fieldOf("to_x").forGetter(Surface::toX),
				Codec.INT.fieldOf("to_z").forGetter(Surface::toZ),
				BlockState.CODEC.fieldOf("block").forGetter(Surface::block)
		).apply(i, Surface::new));

		public boolean contains(int x, int z) {
			return x >= Math.min(this.fromX, this.toX) && x <= Math.max(this.fromX, this.toX)
					&& z >= Math.min(this.fromZ, this.toZ) && z <= Math.max(this.fromZ, this.toZ);
		}
	}

	/** A cuboid from..to (inclusive). mode: "solid" fills it, "hollow" draws its whole shell (walls, floor and roof), "walls" draws only its four sides. Air boxes ("solid" with minecraft:air) cut openings - doors, hangar fronts. */
	public record Box(BlockPos from, BlockPos to, BlockState block, String mode) {
		public static final Codec<Box> CODEC = RecordCodecBuilder.<Box>create(i -> i.group(
				BlockPos.CODEC.fieldOf("from").forGetter(Box::from),
				BlockPos.CODEC.fieldOf("to").forGetter(Box::to),
				BlockState.CODEC.fieldOf("block").forGetter(Box::block),
				Codec.STRING.optionalFieldOf("mode", "solid").forGetter(Box::mode)
		).apply(i, Box::new)).validate(box -> switch (box.mode()) {
			case "solid", "hollow", "walls" -> DataResult.success(box);
			default -> DataResult.error(() -> "Unknown box mode '" + box.mode() + "' (solid, hollow, walls)");
		});
	}

	/** A single block - e.g. an Enemy Supply Block, a light, a door. Block states are rotated with the base (stairs, doors, ... keep facing the right way). */
	public record Block(BlockPos pos, BlockState state) {
		public static final Codec<Block> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Block::pos),
				BlockState.CODEC.fieldOf("state").forGetter(Block::state)
		).apply(i, Block::new));
	}

	public static final Codec<EnemyBaseDefinition> CODEC = RecordCodecBuilder.<EnemyBaseDefinition>create(i -> i.group(
			Codec.intRange(8, MAX_SIZE).fieldOf("size_x").forGetter(EnemyBaseDefinition::sizeX),
			Codec.intRange(8, MAX_SIZE).fieldOf("size_z").forGetter(EnemyBaseDefinition::sizeZ),
			Terrain.CODEC.optionalFieldOf("terrain", Terrain.DEFAULT).forGetter(EnemyBaseDefinition::terrain),
			Surface.CODEC.listOf().optionalFieldOf("surfaces", List.of()).forGetter(EnemyBaseDefinition::surfaces),
			Box.CODEC.listOf().optionalFieldOf("boxes", List.of()).forGetter(EnemyBaseDefinition::boxes),
			Block.CODEC.listOf().optionalFieldOf("blocks", List.of()).forGetter(EnemyBaseDefinition::blocks),
			EnemyUnitSpec.CODEC.listOf().optionalFieldOf("units", List.of()).forGetter(EnemyBaseDefinition::units)
	).apply(i, EnemyBaseDefinition::new));

	/** The surface block at (x, z) in the footprint: the last matching `surfaces` entry, else the terrain's own surface block. */
	public BlockState surfaceAt(int x, int z) {
		for (int index = this.surfaces.size() - 1; index >= 0; index--) {
			Surface surface = this.surfaces.get(index);
			if (surface.contains(x, z)) {
				return surface.block();
			}
		}
		return this.terrain.surfaceBlock();
	}

	/** Highest local y anything is drawn at - for the piece's bounding box. */
	public int maxLocalY() {
		int max = 8;
		for (Box box : this.boxes) {
			max = Math.max(max, Math.max(box.from().getY(), box.to().getY()));
		}
		for (Block block : this.blocks) {
			max = Math.max(max, block.pos().getY());
		}
		for (EnemyUnitSpec unit : this.units) {
			max = Math.max(max, unit.block().getY());
		}
		return max;
	}
}
