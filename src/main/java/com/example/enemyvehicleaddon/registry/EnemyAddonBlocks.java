package com.example.enemyvehicleaddon.registry;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import com.example.enemyvehicleaddon.block.EnemyControlBlock;
import com.example.enemyvehicleaddon.block.EnemySupplyBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class EnemyAddonBlocks {

	public static Block ENEMY_CONTROL;
	public static Block ENEMY_SUPPLY;

	private EnemyAddonBlocks() {
	}

	/** Same block settings as the prerequisite mod's own Drone Center, apart from the map colour. */
	public static void register() {
		RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_control"));
		ENEMY_CONTROL = Registry.register(Registries.BLOCK, key,
				new EnemyControlBlock(AbstractBlock.Settings.create()
						.registryKey(key)
						.mapColor(MapColor.RED)
						.strength(4.0f)
						.requiresTool()));

		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_control"));
		Registry.register(Registries.ITEM, itemKey,
				new BlockItem(ENEMY_CONTROL, new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey()));

		// Breakable like any sturdy block - destroying it is meant to be a way to ground an enemy base's aircraft.
		RegistryKey<Block> supplyKey = RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_supply"));
		ENEMY_SUPPLY = Registry.register(Registries.BLOCK, supplyKey,
				new EnemySupplyBlock(AbstractBlock.Settings.create()
						.registryKey(supplyKey)
						.mapColor(MapColor.YELLOW)
						.strength(4.0f)
						.requiresTool()));
		RegistryKey<Item> supplyItemKey = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_supply"));
		Registry.register(Registries.ITEM, supplyItemKey,
				new BlockItem(ENEMY_SUPPLY, new Item.Settings().registryKey(supplyItemKey).useBlockPrefixedTranslationKey()));
	}
}
