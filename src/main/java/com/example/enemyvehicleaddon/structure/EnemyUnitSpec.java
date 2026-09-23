package com.example.enemyvehicleaddon.structure;

import com.example.tudursvehiclemod.block.DroneWaypoint;
import com.example.tudursvehiclemod.block.GroundWaypoint;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One enemy unit of a generated base: an Enemy Control Block and everything it needs to set itself up and spawn its vehicle on its first tick (see EnemyControlBlockEntity's pending-setup section). Written in a base definition's own datapack JSON (EnemyBaseDefinition#units()), and stored in the placed block entity (as JSON text) until applied.
 *
 * Coordinates: `block` is in the base's local frame (see EnemyBaseDefinition); everything else is relative to that unit's own block, exactly the prerequisite mod's own convention for Drone Center routes (x/z offsets measured from the block's centre, y from its bottom): the parking position, route waypoints, ground route, Home Point and return via point. `parking_yaw` uses Minecraft yaw (0 = south/+z, 90 = west/-x, 180 = north, -90 = east).
 *
 * Runway guidance (the prerequisite mod's own intended landing behaviour): the aircraft flies from the via point to the Home Point in a straight line, so aligning both with the runway centreline (same x or same z) makes a straight-in approach; give the Home Point some height (around +5) rather than 0 to avoid ground contact - the aircraft then comes to rest some way PAST the Home Point, which is where Enemy Supply Blocks should be. */
public record EnemyUnitSpec(Identifier vehicle, BlockPos block, List<Double> parking, float parkingYaw,
		List<DroneWaypoint> waypoints, List<GroundWaypoint> groundWaypoints,
		Optional<DroneWaypoint> home, Optional<DroneWaypoint> via,
		Combat combat, Orbit orbit, double leashRadius) {

	/** Prerequisite mod Drone Center defaults except where noted. weapon_index -1 = no dummy pilot (the vehicle just patrols). */
	public record Combat(int weaponIndex, double searchRange, double attackStartAltitude, double attackStopAltitude, double diveTargetYOffset) {
		public static final Combat DEFAULT = new Combat(0, 64.0, 200.0, 40.0, 0.0);
		public static final Codec<Combat> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.optionalFieldOf("weapon_index", 0).forGetter(Combat::weaponIndex),
				Codec.DOUBLE.optionalFieldOf("search_range", 64.0).forGetter(Combat::searchRange),
				Codec.DOUBLE.optionalFieldOf("attack_start_altitude", 200.0).forGetter(Combat::attackStartAltitude),
				Codec.DOUBLE.optionalFieldOf("attack_stop_altitude", 40.0).forGetter(Combat::attackStopAltitude),
				Codec.DOUBLE.optionalFieldOf("dive_target_y_offset", 0.0).forGetter(Combat::diveTargetYOffset)
		).apply(i, Combat::new));
	}

	/** Circular-orbit settings, used by an aircraft without route waypoints. */
	public record Orbit(float speedFraction, double orbitAltitude, float radiusMultiplier) {
		public static final Orbit DEFAULT = new Orbit(0.5f, 15.0, 1.0f);
		public static final Codec<Orbit> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("speed_fraction", 0.5f).forGetter(Orbit::speedFraction),
				Codec.DOUBLE.optionalFieldOf("altitude", 15.0).forGetter(Orbit::orbitAltitude),
				Codec.FLOAT.optionalFieldOf("radius_multiplier", 1.0f).forGetter(Orbit::radiusMultiplier)
		).apply(i, Orbit::new));
	}

	public static final Codec<DroneWaypoint> WAYPOINT_CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("x").forGetter(DroneWaypoint::relX),
			Codec.INT.fieldOf("y").forGetter(DroneWaypoint::relY),
			Codec.INT.fieldOf("z").forGetter(DroneWaypoint::relZ),
			Codec.FLOAT.optionalFieldOf("speed", 0.5f).forGetter(DroneWaypoint::speedFraction),
			Codec.FLOAT.optionalFieldOf("roll", 0f).forGetter(DroneWaypoint::rollAngle),
			Codec.FLOAT.optionalFieldOf("roll_maneuverability", 1.0f).forGetter(DroneWaypoint::rollManeuverabilityMultiplier),
			Codec.FLOAT.optionalFieldOf("turn_maneuverability", 1.0f).forGetter(DroneWaypoint::turnManeuverabilityMultiplier)
	).apply(i, DroneWaypoint::new));

	public static final Codec<GroundWaypoint> GROUND_WAYPOINT_CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("x").forGetter(GroundWaypoint::relX),
			Codec.INT.fieldOf("z").forGetter(GroundWaypoint::relZ),
			Codec.FLOAT.optionalFieldOf("speed", 0.5f).forGetter(GroundWaypoint::speedFraction),
			Codec.INT.optionalFieldOf("wait_ticks", 0).forGetter(GroundWaypoint::waitTicks)
	).apply(i, GroundWaypoint::new));

	public static final Codec<EnemyUnitSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("vehicle").forGetter(EnemyUnitSpec::vehicle),
			BlockPos.CODEC.fieldOf("block").forGetter(EnemyUnitSpec::block),
			Codec.DOUBLE.listOf(3, 3).optionalFieldOf("parking", List.of(0.0, 0.0, 0.0)).forGetter(EnemyUnitSpec::parking),
			Codec.FLOAT.optionalFieldOf("parking_yaw", 0f).forGetter(EnemyUnitSpec::parkingYaw),
			WAYPOINT_CODEC.listOf().optionalFieldOf("waypoints", List.of()).forGetter(EnemyUnitSpec::waypoints),
			GROUND_WAYPOINT_CODEC.listOf().optionalFieldOf("ground_waypoints", List.of()).forGetter(EnemyUnitSpec::groundWaypoints),
			WAYPOINT_CODEC.optionalFieldOf("home").forGetter(EnemyUnitSpec::home),
			WAYPOINT_CODEC.optionalFieldOf("via").forGetter(EnemyUnitSpec::via),
			Combat.CODEC.optionalFieldOf("combat", Combat.DEFAULT).forGetter(EnemyUnitSpec::combat),
			Orbit.CODEC.optionalFieldOf("orbit", Orbit.DEFAULT).forGetter(EnemyUnitSpec::orbit),
			Codec.DOUBLE.optionalFieldOf("leash_radius", 1000.0).forGetter(EnemyUnitSpec::leashRadius)
	).apply(i, EnemyUnitSpec::new));

	/** This spec with everything relative to its block turned by quarterTurns clockwise (seen from above) about the block's centre - used when a base is generated rotated. `block` itself is left alone (the piece places the block in world space). */
	public EnemyUnitSpec rotated(int quarterTurns) {
		int q = Math.floorMod(quarterTurns, 4);
		if (q == 0) {
			return this;
		}
		List<DroneWaypoint> rotatedWaypoints = new ArrayList<>();
		for (DroneWaypoint waypoint : this.waypoints) {
			rotatedWaypoints.add(rotate(waypoint, q));
		}
		List<GroundWaypoint> rotatedGround = new ArrayList<>();
		for (GroundWaypoint waypoint : this.groundWaypoints) {
			int[] xz = EnemyRotation.rotate(waypoint.relX(), waypoint.relZ(), q);
			rotatedGround.add(new GroundWaypoint(xz[0], xz[1], waypoint.speedFraction(), waypoint.waitTicks()));
		}
		double[] parkingXz = EnemyRotation.rotate(this.parking.get(0), this.parking.get(2), q);
		return new EnemyUnitSpec(this.vehicle, this.block, List.of(parkingXz[0], this.parking.get(1), parkingXz[1]),
				MathHelper.wrapDegrees(this.parkingYaw + 90f * q), rotatedWaypoints, rotatedGround,
				this.home.map(w -> rotate(w, q)), this.via.map(w -> rotate(w, q)), this.combat, this.orbit, this.leashRadius);
	}

	private static DroneWaypoint rotate(DroneWaypoint waypoint, int q) {
		int[] xz = EnemyRotation.rotate(waypoint.relX(), waypoint.relZ(), q);
		return new DroneWaypoint(xz[0], waypoint.relY(), xz[1], waypoint.speedFraction(), waypoint.rollAngle(),
				waypoint.rollManeuverabilityMultiplier(), waypoint.turnManeuverabilityMultiplier());
	}

	/** Compact JSON text for block entity storage; null on failure. */
	public String toJsonString() {
		return CODEC.encodeStart(JsonOps.INSTANCE, this).result().map(JsonElement::toString).orElse(null);
	}

	public static Optional<EnemyUnitSpec> fromJsonString(String json) {
		if (json == null || json.isEmpty()) {
			return Optional.empty();
		}
		try {
			return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result();
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
