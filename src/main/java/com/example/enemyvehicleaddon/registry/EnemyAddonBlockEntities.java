package com.example.enemyvehicleaddon.registry;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import com.example.enemyvehicleaddon.block.EnemySupplyBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class EnemyAddonBlockEntities {

	/** A block entity type of its own (rather than reusing the prerequisite mod's DRONE_CENTER) so a saved Enemy Control Block reloads as an EnemyControlBlockEntity: chunk loading instantiates block entities from the type id stored in their NBT, so sharing DRONE_CENTER would silently turn every enemy block back into a plain Drone Center on reload. See EnemyControlBlockEntity's own constructor doc for how the subclass ends up carrying this type. */
	public static BlockEntityType<EnemyControlBlockEntity> ENEMY_CONTROL;
	public static BlockEntityType<EnemySupplyBlockEntity> ENEMY_SUPPLY;

	private EnemyAddonBlockEntities() {
	}

	private static volatile boolean droneCenterSupportAdded;

	public static void register() {
		ENEMY_CONTROL = Registry.register(
				Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_control"),
				FabricBlockEntityTypeBuilder.create(EnemyControlBlockEntity::new, EnemyAddonBlocks.ENEMY_CONTROL).build());
		ENEMY_SUPPLY = Registry.register(
				Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_supply"),
				FabricBlockEntityTypeBuilder.create(EnemySupplyBlockEntity::new, EnemyAddonBlocks.ENEMY_SUPPLY).build());
	}

	/** DroneCenterBlockEntity's constructor always passes DRONE_CENTER to BlockEntity's constructor, which validates (BlockEntity#validateSupports) that the type supports the block being constructed for. Declaring the Enemy Control Block as supported by DRONE_CENTER (Fabric API's FabricBlockEntityType#addSupportedBlock) is what lets EnemyControlBlockEntity get through that superclass constructor; it switches itself to ENEMY_CONTROL immediately afterwards (see its constructor doc), so no Enemy Control Block ever actually carries DRONE_CENTER.
	 *
	 * NOT done from this addon's onInitialize(): Fabric does not order entrypoints by dependency, so this addon's initializer can run before the prerequisite mod's, while ModBlockEntities.DRONE_CENTER is still null. Instead it is called once every mod has initialized (SERVER_STARTING / CLIENT_STARTED, both before any world - and so any block entity - exists), and again from EnemyControlBlockEntity's constructor as a last-resort guarantee. Idempotent; synchronized so the one-time mutation of DRONE_CENTER's block set can never race. */
	public static void enemyvehicleaddon$ensureDroneCenterSupportsEnemyBlock() {
		if (droneCenterSupportAdded) {
			return;
		}
		synchronized (EnemyAddonBlockEntities.class) {
			if (droneCenterSupportAdded) {
				return;
			}
			BlockEntityType<?> droneCenter = com.example.tudursvehiclemod.registry.ModBlockEntities.DRONE_CENTER;
			if (droneCenter == null) {
				throw new IllegalStateException("tudursvehiclemod has not registered its Drone Center block entity type yet");
			}
			droneCenter.addSupportedBlock(EnemyAddonBlocks.ENEMY_CONTROL);
			droneCenterSupportAdded = true;
		}
	}
}
