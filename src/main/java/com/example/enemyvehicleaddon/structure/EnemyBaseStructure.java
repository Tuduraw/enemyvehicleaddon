package com.example.enemyvehicleaddon.structure;

import com.example.enemyvehicleaddon.config.EnemyAddonConfig;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** "enemyvehicleaddon:enemy_base" structure type - an enemy base generated from an EnemyBaseDefinition (datapack JSON), as one EnemyBasePiece.
 *
 * Site selection happens here - after a cheap biome check (see getStructurePosition()) - from noise-only terrain heights (the same values no matter which chunk asks first, and no chunks need loading): the footprint is sampled on a grid of at most 17 x 17 points, the base's ground level is the median land height (at least one above sea level), and the site is rejected if levelling it would need cutting more than terrain.max_cut or filling more than terrain.max_fill anywhere, or if more than terrain.max_water_fraction of it is water. A footprint rotated by 180 degrees covers the same ground, so only two orientations need checking; among the orientations that pass, one of the four rotations is chosen at random. The actual levelling (and the blending skirt around the footprint) is EnemyBasePiece's job.
 *
 * Can be switched off entirely with EnemyAddonConfig#generateEnemyBases (existing bases are unaffected). */
public class EnemyBaseStructure extends Structure {

	public static final MapCodec<EnemyBaseStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			configCodecBuilder(instance),
			EnemyBaseDefinition.CODEC.fieldOf("base").forGetter(structure -> structure.definition)
	).apply(instance, EnemyBaseStructure::new));

	/** Terrain samples per footprint side, at most (plus one) - the spacing grows with the footprint so a large base costs no more to evaluate than a small one; never finer than MIN_SAMPLE_STEP blocks. Each sample is two noise-only column height evaluations (the dominant cost of site selection). */
	private static final int SAMPLES_PER_SIDE = 16;
	private static final int MIN_SAMPLE_STEP = 8;

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("enemyvehicleaddon");

	private final EnemyBaseDefinition definition;

	public EnemyBaseStructure(Config config, EnemyBaseDefinition definition) {
		super(config);
		this.definition = definition;
	}

	@Override
	protected Optional<StructurePosition> getStructurePosition(Context context) {
		if (!EnemyAddonConfig.get().generateEnemyBases) {
			return Optional.empty();
		}
		if (!EnemyAddonConfig.get().logGenerationTimings) {
			return this.findPosition(context);
		}
		long start = System.nanoTime();
		Optional<StructurePosition> result = this.findPosition(context);
		LOGGER.info("[timings] enemy base site selection at chunk {}: {} in {} ms", context.chunkPos(),
				result.isPresent() ? "accepted" : "rejected", String.format("%.2f", (System.nanoTime() - start) / 1.0e6));
		return result;
	}

	private Optional<StructurePosition> findPosition(Context context) {
		ChunkPos chunkPos = context.chunkPos();
		int centerX = chunkPos.getCenterX();
		int centerZ = chunkPos.getCenterZ();
		EnemyBaseDefinition.Terrain terrain = this.definition.terrain();
		Map<Long, int[]> sampleCache = new HashMap<>();

		// Cheap biome check first: vanilla only checks the biome AFTER getStructurePosition() has returned (Structure#getValidStructurePosition()), which would run the full terrain sampling below for every candidate - oceans, mountains, anywhere - including every cell /locate walks through. One height sample and one biome lookup reject most of them.
		int centerHeight = context.chunkGenerator().getHeight(centerX, centerZ, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig());
		if (!context.biomePredicate().test(context.biomeSource().getBiome(BiomeCoords.fromBlock(centerX), BiomeCoords.fromBlock(centerHeight),
				BiomeCoords.fromBlock(centerZ), context.noiseConfig().getMultiNoiseSampler()))) {
			return Optional.empty();
		}

		// Orientation 0: size_x along world x (rotations 0 and 2); orientation 1: along world z (rotations 1 and 3).
		int[] groundY = new int[2];
		boolean[] valid = new boolean[2];
		for (int orientation = 0; orientation < 2; orientation++) {
			int extentX = orientation == 0 ? this.definition.sizeX() : this.definition.sizeZ();
			int extentZ = orientation == 0 ? this.definition.sizeZ() : this.definition.sizeX();
			OptionalGround ground = evaluate(context, centerX, centerZ, extentX, extentZ, terrain, sampleCache);
			valid[orientation] = ground.valid;
			groundY[orientation] = ground.groundY;
		}
		List<Integer> candidates = new ArrayList<>();
		for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
			if (valid[quarterTurns % 2]) {
				candidates.add(quarterTurns);
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		int quarterTurns = candidates.get(context.random().nextInt(candidates.size()));
		int baseY = groundY[quarterTurns % 2];

		// World position of local (0, 0) such that the footprint's centre lands on the chunk centre.
		int[] halfOffset = EnemyRotation.rotate(this.definition.sizeX() / 2, this.definition.sizeZ() / 2, quarterTurns);
		int anchorX = centerX - halfOffset[0];
		int anchorZ = centerZ - halfOffset[1];
		EnemyBaseDefinition definition = this.definition;
		return Optional.of(new StructurePosition(new BlockPos(centerX, baseY, centerZ),
				collector -> collector.addPiece(new EnemyBasePiece(anchorX, anchorZ, baseY, quarterTurns, definition))));
	}

	private record OptionalGround(boolean valid, int groundY) {
	}

	private static OptionalGround evaluate(Context context, int centerX, int centerZ, int extentX, int extentZ,
			EnemyBaseDefinition.Terrain terrain, Map<Long, int[]> sampleCache) {
		ChunkGenerator generator = context.chunkGenerator();
		List<Integer> land = new ArrayList<>();
		int water = 0;
		int total = 0;
		int minX = centerX - extentX / 2;
		int minZ = centerZ - extentZ / 2;
		int stepX = Math.max(MIN_SAMPLE_STEP, extentX / SAMPLES_PER_SIDE);
		int stepZ = Math.max(MIN_SAMPLE_STEP, extentZ / SAMPLES_PER_SIDE);
		for (int dx = 0; dx <= extentX; dx += stepX) {
			for (int dz = 0; dz <= extentZ; dz += stepZ) {
				int x = minX + Math.min(dx, extentX - 1);
				int z = minZ + Math.min(dz, extentZ - 1);
				int[] sample = sampleCache.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> new int[]{
						generator.getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, context.world(), context.noiseConfig()),
						generator.getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig())});
				total++;
				if (sample[1] > sample[0]) {
					water++;
				}
				land.add(sample[0]);
			}
		}
		if (total == 0 || water > terrain.maxWaterFraction() * total) {
			return new OptionalGround(false, 0);
		}
		int[] heights = land.stream().mapToInt(Integer::intValue).toArray();
		Arrays.sort(heights);
		// Heights are "first free block above the ground", i.e. exactly the base's local y = 0.
		int groundY = Math.max(heights[heights.length / 2], generator.getSeaLevel() + 1);
		boolean valid = heights[heights.length - 1] - groundY <= terrain.maxCut() && groundY - heights[0] <= terrain.maxFill();
		return new OptionalGround(valid, groundY);
	}

	@Override
	public StructureType<?> getType() {
		return EnemyStructures.ENEMY_BASE;
	}
}
