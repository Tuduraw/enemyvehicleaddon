package com.example.enemyvehicleaddon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/enemyvehicleaddon.json - server-side behaviour settings (a dedicated server's own copy is the one that matters; nothing here affects the client). Read once at startup; a missing file is created with the defaults, and a missing/invalid individual value falls back to its default. Changes need a restart. */
public final class EnemyAddonConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger("enemyvehicleaddon");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "enemyvehicleaddon.json";

	private static EnemyAddonConfig instance = new EnemyAddonConfig();

	/** Whether enemy pilots also target players in creative mode (spectators are never targeted). Off by default. Note creative players are immune to almost all damage, so this is mainly for observing/testing enemy behaviour from creative. */
	public boolean targetCreativePlayers = false;

	/** How close (blocks, horizontal) a vehicle that has stopped pursuing must get back to its normal route before it is considered "returned" and allowed to go dormant again - see EnemyLinkManager. */
	public double returnTolerance = 48.0;

	/** Safety limit: a vehicle still not back on its route this long (seconds) after its pursuit ended goes dormant anyway, so a vehicle that can never get back (stuck, route edited away, ...) cannot keep chunks loaded forever. */
	public int returnTimeoutSeconds = 300;

	/** How long (seconds) an Enemy Control Block waits before re-checking the last known position of a bound vehicle it could not find there - see EnemyLinkManager's vehicle verification. */
	public int vehicleVerifyCooldownSeconds = 300;

	/** Seconds (of world time) after an enemy vehicle is destroyed or confirmed gone before its Enemy Control Block spawns a replacement - see EnemyControlBlockEntity's respawn section. 0 disables respawning. */
	public int respawnDelaySeconds = 300;

	/** Radius (blocks) within which an Enemy Supply Block refuels, rearms and repairs stationary vehicles. */
	public double supplyRadius = 12.0;

	/** Whether Enemy Supply Blocks also supply vehicles that are not enemy vehicles (players' own, plain drones, ...). Off by default: a captured enemy base should not double as a free service station. */
	public boolean supplyNonEnemyVehicles = false;

	/** Upper limit (seconds) on the supply an Enemy Supply Block catches up on when it is loaded again after being unloaded - see EnemySupplyBlockEntity. */
	public int supplyCatchUpMaxSeconds = 600;

	/** Whether enemy bases (the bundled samples and any datapack-defined ones) generate in new chunks. Already generated bases are unaffected. */
	public boolean generateEnemyBases = true;

	/** Whether explosions caused by enemy vehicles destroy blocks (and start fires): their weapons' explosions and piercing, and the explosion of a destroyed enemy vehicle. Off by default - enemies fire indiscriminately and would otherwise wreck their own bases. Players' own weapons are unaffected either way. */
	public boolean enemyBlockDamage = false;

	/** Measurement aid: logs how long enemy base site selection (per candidate) and drawing (per chunk) take. Off by default; meant for load verification and can be removed once that is done. */
	public boolean logGenerationTimings = false;

	private EnemyAddonConfig() {
	}

	public static EnemyAddonConfig get() {
		return instance;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		EnemyAddonConfig loaded = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				loaded = GSON.fromJson(reader, EnemyAddonConfig.class);
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("Could not read {}, using defaults: {}", path, e.toString());
			}
		}
		instance = loaded != null ? loaded : new EnemyAddonConfig();
		instance.sanitize();
		// Rewritten every start so newly added options show up in an existing file with their defaults.
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			LOGGER.warn("Could not write {}: {}", path, e.toString());
		}
	}

	private void sanitize() {
		if (!Double.isFinite(this.returnTolerance) || this.returnTolerance < 4.0) {
			this.returnTolerance = 48.0;
		}
		if (this.returnTimeoutSeconds < 10) {
			this.returnTimeoutSeconds = 300;
		}
		if (this.vehicleVerifyCooldownSeconds < 10) {
			this.vehicleVerifyCooldownSeconds = 300;
		}
		if (this.respawnDelaySeconds < 0) {
			this.respawnDelaySeconds = 300;
		}
		if (!Double.isFinite(this.supplyRadius) || this.supplyRadius < 1.0 || this.supplyRadius > 64.0) {
			this.supplyRadius = 12.0;
		}
		if (this.supplyCatchUpMaxSeconds < 0) {
			this.supplyCatchUpMaxSeconds = 600;
		}
	}
}
