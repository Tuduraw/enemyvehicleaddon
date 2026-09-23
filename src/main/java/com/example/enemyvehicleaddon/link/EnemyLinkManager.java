package com.example.enemyvehicleaddon.link;

import com.example.enemyvehicleaddon.EnemyVehicleAddon;
import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import com.example.enemyvehicleaddon.config.EnemyAddonConfig;
import com.example.enemyvehicleaddon.link.EnemyLinkState.Link;
import com.example.enemyvehicleaddon.link.EnemyLinkState.Mode;
import com.example.tudursvehiclemod.block.DroneCenterBlockEntity;
import com.example.tudursvehiclemod.block.DroneWaypoint;
import com.example.tudursvehiclemod.block.GroundWaypoint;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dormancy and cross-loading for Enemy Control Blocks and their vehicles.
 *
 * The prerequisite mod's Drone Center keeps its own chunk force-loaded permanently and force-loads a 3x3 area around its vehicle whenever active, so a drone flies forever regardless of where players are. For enemies that is replaced entirely (DroneCenterBlockEntityMixin suppresses every force-load call for Enemy Control Blocks) by this policy:
 *
 * - DORMANT (normal): nothing is kept loaded on the vehicle's behalf. The vehicle moves only while players have its chunk loaded, and simply stops where it is when they leave.
 * - PURSUIT: from the moment the pilot engages a target, the vehicle's surroundings and the block's chunk are kept loaded (chunk tickets that follow the vehicle every tick).
 * - RETURNING: after the pursuit ends (target lost, left the leash, shaken off, ...) loading continues until the vehicle is back on its normal route (within EnemyAddonConfig#returnTolerance of its waypoint loop, or of the orbit circle it was on when the pursuit began), then it goes DORMANT again. EnemyAddonConfig#returnTimeoutSeconds is the safety net.
 *
 * Cross-loading safety (block and vehicle load independently):
 * - vehicle loaded -> block: whenever the vehicle is actually ticking, the block's chunk is kept ticking too, so the block entity (route data, pilot management, link bookkeeping) is always live while its vehicle moves.
 * - block loaded -> vehicle: when a loaded, active block cannot find its vehicle, the vehicle's last known chunk is loaded WITHOUT simulation (entities load but do not tick, so this never wakes a dormant vehicle) for a short window to confirm it still exists - once per EnemyAddonConfig#vehicleVerifyCooldownSeconds at most. A vehicle that is not found starts the block's respawn countdown.
 * - stale vehicle: an enemy pilot whose vehicle is no longer the one registered for its block (stick swapped, block removed while the vehicle was unloaded) unlinks the vehicle and removes itself - see EnemyPilotEntity#tick().
 *
 * Tickets rather than setChunkForced(): tickets expire on their own (no permanently forced chunk is left behind by a crash or a removed block), are reference-counted per type (no conflict with chunks forced by the prerequisite mod or /forceload), and are not saved (after a restart the persisted Mode re-creates what is needed). */
public final class EnemyLinkManager {

	private static final Logger LOGGER = LoggerFactory.getLogger("enemyvehicleaddon");

	/** Keeps chunks loaded AND simulated (entities/block entities tick). Refreshed at least every NORMAL_UPDATE_INTERVAL ticks while needed; expires on its own otherwise. */
	public static final ChunkTicketType AWAKE_TICKET = Registry.register(Registries.TICKET_TYPE,
			Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_awake"),
			new ChunkTicketType(60L, ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION));
	/** Loads chunks (and so their entities) WITHOUT simulating them - used only to confirm a vehicle still exists without waking it. */
	public static final ChunkTicketType VERIFY_TICKET = Registry.register(Registries.TICKET_TYPE,
			Identifier.of(EnemyVehicleAddon.MOD_ID, "enemy_verify"),
			new ChunkTicketType(40L, ChunkTicketType.FOR_LOADING));

	/** Radius 3 around the vehicle: its own chunk plus one full ring are entity-ticking, the same 3x3 the prerequisite mod forces around a drone - a fast aircraft crossing a chunk border between two updates keeps ticking. */
	private static final int VEHICLE_TICKET_RADIUS = 3;
	/** Radius 2: the block's own chunk is block-ticking (its block entity ticks). */
	private static final int BLOCK_TICKET_RADIUS = 2;
	private static final int VERIFY_TICKET_RADIUS = 2;
	private static final int VERIFY_WINDOW_TICKS = 100;
	/** Pursuit ends once the pilot has not reported being engaged for this long - covers the pilot's own 20-tick reacquire cadence and a pilot that disappeared altogether. */
	private static final int ENGAGEMENT_GRACE_TICKS = 40;
	private static final int NORMAL_UPDATE_INTERVAL = 10;

	private EnemyLinkManager() {
	}

	public static void register() {
		// Touch the ticket types so they are registered during initialization (registries freeze afterwards).
		LOGGER.debug("Registered ticket types {} / {}", AWAKE_TICKET, VERIFY_TICKET);
		ServerTickEvents.END_WORLD_TICK.register(EnemyLinkManager::tickWorld);
	}

	// --- Block side ---

	/** Every tick of an Enemy Control Block entity, BEFORE the inherited Drone Center tick (so a freshly spawned pilot already finds its link). Keeps the registered vehicle current and runs the block -> vehicle verification. */
	public static void onBlockEntityTick(ServerWorld world, EnemyControlBlockEntity blockEntity) {
		EnemyLinkState state = EnemyLinkState.get(world);
		Link link = state.getOrCreate(blockEntity.getPos());
		UUID bound = blockEntity.tudursvehiclemod$getBoundVehicleId();
		if (!java.util.Objects.equals(bound, link.vehicleUuid)) {
			state.setVehicle(link, bound);
			link.lastVehicleChunk = null;
			link.verifyUntil = 0;
			link.nextVerifyAllowed = 0;
			setMode(state, link, Mode.DORMANT, world.getTime());
			state.markDirty();
		}
		if (bound == null || !blockEntity.tudursvehiclemod$isActive()) {
			if (link.mode != Mode.DORMANT) {
				setMode(state, link, Mode.DORMANT, world.getTime());
			}
			return;
		}
		AbstractVehicleEntity vehicle = DroneCenterBlockEntity.tudursvehiclemod$findBoundVehicle(world, bound);
		if (vehicle != null) {
			rememberVehicleChunk(state, link, vehicle);
			link.verifyUntil = 0;
			return;
		}
		// Block -> vehicle verification (see this class's doc).
		if (link.lastVehicleChunk == null || link.mode != Mode.DORMANT) {
			// Never seen yet (nothing to check), or an awake mode that already keeps that chunk loaded.
			return;
		}
		long now = world.getTime();
		if (link.verifyUntil > 0) {
			if (now < link.verifyUntil) {
				world.getChunkManager().addTicket(VERIFY_TICKET, new ChunkPos(link.lastVehicleChunk), VERIFY_TICKET_RADIUS);
				return;
			}
			link.verifyUntil = 0;
			link.nextVerifyAllowed = now + EnemyAddonConfig.get().vehicleVerifyCooldownSeconds * 20L;
			// Starts the respawn countdown (see EnemyControlBlockEntity's respawn section).
			blockEntity.enemyvehicleaddon$onVehicleConfirmedMissing(world);
			LOGGER.warn("Enemy Control Block at {} could not find its bound vehicle {} around its last known chunk {} - it may have been removed.",
					link.blockPos, bound, new ChunkPos(link.lastVehicleChunk));
			return;
		}
		if (now >= link.nextVerifyAllowed) {
			link.verifyUntil = now + VERIFY_WINDOW_TICKS;
			link.nextVerifyAllowed = now + EnemyAddonConfig.get().vehicleVerifyCooldownSeconds * 20L;
			world.getChunkManager().addTicket(VERIFY_TICKET, new ChunkPos(link.lastVehicleChunk), VERIFY_TICKET_RADIUS);
		}
	}

	public static void onBlockRemoved(ServerWorld world, BlockPos pos) {
		EnemyLinkState.get(world).remove(pos);
	}

	// --- Pilot side ---

	/** Called every server tick by an enemy pilot riding its vehicle. Returns false if this vehicle is not (any more) the one registered for that block, i.e. the pilot/vehicle is a stale leftover - see EnemyPilotEntity#tick(). */
	public static boolean reportPilot(ServerWorld world, BlockPos blockPos, AbstractVehicleEntity vehicle, boolean engaged) {
		EnemyLinkState state = EnemyLinkState.get(world);
		Link link = state.get(blockPos);
		if (link == null || !vehicle.getUuid().equals(link.vehicleUuid)) {
			return false;
		}
		rememberVehicleChunk(state, link, vehicle);
		if (engaged) {
			long now = world.getTime();
			link.lastEngagedTime = now;
			if (link.mode != Mode.PURSUIT) {
				if (link.mode == Mode.DORMANT) {
					// See returnOrbitRadius' own doc - only captured when leaving the route, not when re-engaging on the way back.
					link.returnOrbitRadius = horizontalDistance(vehicle, blockPos);
				}
				setMode(state, link, Mode.PURSUIT, now);
			}
			// Immediately, not at the next periodic update: the pursuit may start right at the edge of the loaded area.
			keepAwake(world, link, vehicle);
		}
		return true;
	}

	// --- Periodic update ---

	private static void tickWorld(ServerWorld world) {
		EnemyLinkState state = EnemyLinkState.get(world);
		if (state.all().isEmpty()) {
			return;
		}
		long now = world.getTime();
		boolean normalUpdate = now % NORMAL_UPDATE_INTERVAL == 0;
		List<Link> links = new ArrayList<>(state.all());
		for (Link link : links) {
			AbstractVehicleEntity vehicle = null;
			if (link.vehicleUuid != null && world.getEntity(link.vehicleUuid) instanceof AbstractVehicleEntity found) {
				vehicle = found;
			}
			if (vehicle != null && vehicle.tudursvehiclemod$isDestroyed()) {
				vehicle = null;
				if (link.mode != Mode.DORMANT) {
					setMode(state, link, Mode.DORMANT, now);
				}
			}
			if (vehicle != null) {
				rememberVehicleChunk(state, link, vehicle);
			}

			if (link.mode == Mode.PURSUIT && now - link.lastEngagedTime > ENGAGEMENT_GRACE_TICKS) {
				setMode(state, link, Mode.RETURNING, now);
			}
			if (link.mode == Mode.RETURNING && hasReturned(world, link, vehicle, now)) {
				setMode(state, link, Mode.DORMANT, now);
			}

			if (link.mode != Mode.DORMANT) {
				// Every tick while awake: the tickets follow a fast-moving vehicle.
				keepAwake(world, link, vehicle);
			} else if (normalUpdate && vehicle != null && world.shouldTickEntityAt(vehicle.getBlockPos())) {
				// Vehicle -> block (see this class's doc): the vehicle is moving because players have it loaded, so its block must be live too.
				world.getChunkManager().addTicket(AWAKE_TICKET, new ChunkPos(link.blockPos), BLOCK_TICKET_RADIUS);
			}
		}
	}

	private static void keepAwake(ServerWorld world, Link link, Entity vehicle) {
		ChunkPos vehicleChunk = vehicle != null ? vehicle.getChunkPos()
				: link.lastVehicleChunk != null ? new ChunkPos(link.lastVehicleChunk) : null;
		if (vehicleChunk != null) {
			world.getChunkManager().addTicket(AWAKE_TICKET, vehicleChunk, VEHICLE_TICKET_RADIUS);
		}
		world.getChunkManager().addTicket(AWAKE_TICKET, new ChunkPos(link.blockPos), BLOCK_TICKET_RADIUS);
	}

	/** "Back on its normal route" - see this class's doc. Needs the block entity for the route; while it (or the vehicle) is not loaded yet, only the timeout can end RETURNING. */
	private static boolean hasReturned(ServerWorld world, Link link, AbstractVehicleEntity vehicle, long now) {
		if (now - link.modeSince > EnemyAddonConfig.get().returnTimeoutSeconds * 20L) {
			return true;
		}
		if (vehicle == null || !world.isChunkLoaded(new ChunkPos(link.blockPos).toLong())
				|| !(world.getBlockEntity(link.blockPos) instanceof EnemyControlBlockEntity blockEntity)) {
			return false;
		}
		double tolerance = EnemyAddonConfig.get().returnTolerance;
		BlockPos base = link.blockPos;
		List<double[]> route = new ArrayList<>();
		if (DroneCenterBlockEntity.tudursvehiclemod$usesGroundRoute(vehicle)) {
			for (GroundWaypoint waypoint : blockEntity.tudursvehiclemod$getGroundWaypoints()) {
				route.add(new double[]{base.getX() + waypoint.relX() + 0.5, base.getZ() + waypoint.relZ() + 0.5});
			}
			// A ground vehicle never leaves its route to pursue (its pilot only aims/fires), so an empty route means it never left anywhere.
			if (route.isEmpty()) {
				return true;
			}
		} else {
			for (DroneWaypoint waypoint : blockEntity.tudursvehiclemod$getWaypoints()) {
				route.add(new double[]{base.getX() + waypoint.relX() + 0.5, base.getZ() + waypoint.relZ() + 0.5});
			}
			if (route.isEmpty()) {
				// Circular orbit around the block (the prerequisite mod's default when no waypoints are set).
				return Math.abs(horizontalDistance(vehicle, base) - link.returnOrbitRadius) <= tolerance;
			}
		}
		return distanceToClosedPolyline(vehicle.getX(), vehicle.getZ(), route) <= tolerance;
	}

	/** Waypoint routes loop (last -> first), so the closing segment counts as part of the route. */
	private static double distanceToClosedPolyline(double px, double pz, List<double[]> points) {
		if (points.size() == 1) {
			return Math.hypot(px - points.get(0)[0], pz - points.get(0)[1]);
		}
		double best = Double.MAX_VALUE;
		for (int i = 0; i < points.size(); i++) {
			double[] a = points.get(i);
			double[] b = points.get((i + 1) % points.size());
			double abx = b[0] - a[0];
			double abz = b[1] - a[1];
			double lengthSquared = abx * abx + abz * abz;
			double t = lengthSquared < 1.0e-9 ? 0.0 : ((px - a[0]) * abx + (pz - a[1]) * abz) / lengthSquared;
			t = Math.max(0.0, Math.min(1.0, t));
			best = Math.min(best, Math.hypot(px - (a[0] + abx * t), pz - (a[1] + abz * t)));
		}
		return best;
	}

	private static double horizontalDistance(Entity entity, BlockPos pos) {
		return Math.hypot(entity.getX() - (pos.getX() + 0.5), entity.getZ() - (pos.getZ() + 0.5));
	}

	private static void rememberVehicleChunk(EnemyLinkState state, Link link, Entity vehicle) {
		long chunk = vehicle.getChunkPos().toLong();
		if (link.lastVehicleChunk == null || link.lastVehicleChunk != chunk) {
			link.lastVehicleChunk = chunk;
			state.markDirty();
		}
	}

	private static void setMode(EnemyLinkState state, Link link, Mode mode, long now) {
		link.mode = mode;
		link.modeSince = now;
		state.markDirty();
	}

}
