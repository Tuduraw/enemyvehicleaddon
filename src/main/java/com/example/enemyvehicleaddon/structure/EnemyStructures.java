package com.example.enemyvehicleaddon.structure;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.structure.StructurePieceType;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.structure.StructureType;

public final class EnemyStructures {

	public static StructureType<EnemyBaseStructure> ENEMY_BASE;
	public static StructurePieceType ENEMY_BASE_PIECE;

	private EnemyStructures() {
	}

	public static void register() {
		ENEMY_BASE = Registry.register(Registries.STRUCTURE_TYPE, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_base"),
				(StructureType<EnemyBaseStructure>) () -> EnemyBaseStructure.CODEC);
		ENEMY_BASE_PIECE = Registry.register(Registries.STRUCTURE_PIECE, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_base"),
				(StructurePieceType.Simple) EnemyBasePiece::new);
	}
}
