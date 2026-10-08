package com.example.enemyvehicleaddon.block;

import com.example.enemyvehicleaddon.entity.EnemyPilotEntity;
import com.example.enemyvehicleaddon.mixin.BlockEntityAccessor;
import com.example.enemyvehicleaddon.structure.EnemyUnitSpec;
import com.example.enemyvehicleaddon.registry.EnemyAddonBlockEntities;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.example.tudursvehiclemod.asset.VehicleRegistry;
import com.example.tudursvehiclemod.block.DroneCenterBlockEntity;
import com.example.tudursvehiclemod.block.DroneWaypoint;
import com.example.tudursvehiclemod.block.GroundWaypoint;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.item.DroneControlStickItem;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Everything a Drone Center stores and does (inherited unchanged), plus this block's own enemy settings (owner, leash radius, the player whitelist its EnemyPilotEntity must not attack) and the respawn of a lost vehicle (see the respawn section below). */
public class EnemyControlBlockEntity extends DroneCenterBlockEntity {

	private static final Logger LOGGER = LoggerFactory.getLogger("enemyvehicleaddon");

	public static final double DEFAULT_LEASH_RADIUS = 1000.0;
	public static final double MIN_LEASH_RADIUS = 16.0;
	public static final double MAX_LEASH_RADIUS = 100000.0;
	public static final int MAX_WHITELIST_SIZE = 256;
	/** Vanilla names are at most 16 characters; the extra headroom tolerates prefixed names some proxies (e.g. Geyser/Floodgate) produce. */
	public static final int MAX_NAME_LENGTH = 32;

	/** The player who placed this block, or null (placed by something other than a player, e.g. a future structure generator) - see enemyvehicleaddon$canManage(). */
	private UUID owner;
	private double leashRadius = DEFAULT_LEASH_RADIUS;
	/** As entered (original capitalisation kept for display). */
	private List<String> whitelist = List.of();
	/** Lower-cased copy of whitelist, rebuilt whenever it changes and shared (read-only) with the seated pilot. */
	private Set<String> whitelistLowerCase = Set.of();

	/** DroneCenterBlockEntity's only constructor hardcodes the prerequisite mod's DRONE_CENTER type, so the type is replaced right after super() returns (enemyvehicleaddon$prepareSuperConstructor() makes sure this block is among DRONE_CENTER's supported blocks, which is what lets super() accept this block's state in the first place). From here on getType() is ENEMY_CONTROL - which is what gets written as this block entity's saved id, and what the ticker lookup is matched against. */
	public EnemyControlBlockEntity(BlockPos pos, BlockState state) {
		super(pos, enemyvehicleaddon$prepareSuperConstructor(state));
		((BlockEntityAccessor) this).enemyvehicleaddon$setType(EnemyAddonBlockEntities.ENEMY_CONTROL);
		// An enemy vehicle that lands to resupply (see EnemySupplyBlock) should take off again on its own once full - the prerequisite mod's default (MANUAL_REENABLE) would leave it grounded for good. Only a default for a freshly created block: a saved block's own setting is restored by readData() afterwards, and can still be changed from the Drone Center screens.
		this.tudursvehiclemod$setAutoDisableResumeMode(DroneCenterBlockEntity.AutoDisableResumeMode.AUTO_RESUME);
	}

	/** An Enemy Control Block never force-loads chunks - not its own, not a 3x3 around its vehicle, not the vehicle's last chunk while searching. Enemy chunk loading follows the dormancy policy in link.EnemyLinkManager instead (temporary tickets while players are near). Overrides the prerequisite mod's hook for exactly this (DroneCenterBlockEntity#tudursvehiclemod$forceLoadsChunks()). */
	@Override
	protected boolean tudursvehiclemod$forceLoadsChunks() {
		return false;
	}

	/** Runs before DroneCenterBlockEntity's (and so BlockEntity's) constructor - see EnemyAddonBlockEntities#enemyvehicleaddon$ensureDroneCenterSupportsEnemyBlock(). Normally already done by then; this only guarantees it. */
	private static BlockState enemyvehicleaddon$prepareSuperConstructor(BlockState state) {
		EnemyAddonBlockEntities.enemyvehicleaddon$ensureDroneCenterSupportsEnemyBlock();
		return state;
	}

	/** Template placement first (a freshly placed template copy must set itself up before anything else looks at it - see the template section), then link bookkeeping (see EnemyLinkManager#onBlockEntityTick()), then respawn handling, then the inherited Drone Center tick, then the settings push. Respawn runs BEFORE the inherited tick so that a vehicle respawned (and re-activated) this tick is linked by that same inherited tick right away. */
	public static void tick(World world, BlockPos pos, BlockState state, EnemyControlBlockEntity blockEntity) {
		if (world instanceof ServerWorld serverWorld) {
			blockEntity.enemyvehicleaddon$applyPendingSetup(serverWorld);
			blockEntity.enemyvehicleaddon$updateTemplatePlacement(serverWorld, state);
			com.example.enemyvehicleaddon.link.EnemyLinkManager.onBlockEntityTick(serverWorld, blockEntity);
			blockEntity.enemyvehicleaddon$updateRespawn(serverWorld);
		}
		DroneCenterBlockEntity.tick(world, pos, state, blockEntity);
		if (world instanceof ServerWorld serverWorld) {
			blockEntity.enemyvehicleaddon$pushSettingsToPilot(serverWorld);
		}
	}

	/** Keeps an already-seated pilot current, so a whitelist/radius change applies without a deactivate/reactivate cycle (the pilot's creation-time settings come from enemyvehicleaddon$applySettingsTo() instead). Two map lookups per tick - the same lookups the inherited tick already does. */
	private void enemyvehicleaddon$pushSettingsToPilot(ServerWorld world) {
		AbstractVehicleEntity vehicle = DroneCenterBlockEntity.tudursvehiclemod$findBoundVehicle(world, this.tudursvehiclemod$getBoundVehicleId());
		if (vehicle != null && vehicle.tudursvehiclemod$getSeatOccupant(0) instanceof EnemyPilotEntity pilot) {
			this.enemyvehicleaddon$applySettingsTo(pilot);
		}
	}

	/** Called from DroneCenterBlockEntityMixin when the pilot is created, and every tick afterwards via enemyvehicleaddon$pushSettingsToPilot(). */
	public void enemyvehicleaddon$applySettingsTo(EnemyPilotEntity pilot) {
		pilot.enemyvehicleaddon$setEnemySettings(this.getPos(), this.leashRadius, this.whitelistLowerCase);
	}

	// --- Respawn ---
	//
	// While this block exists and still holds its Drone Control Stick, a bound vehicle that is destroyed (or confirmed gone - see EnemyLinkManager's vehicle verification) is replaced after EnemyAddonConfig#respawnDelaySeconds of WORLD time (default 5 minutes). World time keeps running while this block is unloaded, so the replacement simply appears the next time the block is loaded after the deadline - "re-deployed while you were away".
	//
	// What to respawn is remembered whenever the vehicle is seen: its definition id always, and its pose (position relative to this block + yaw) at the moment it is first seen active AND stationary - i.e. parked, right after activation. Without a recorded pose (activated while already airborne and never parked since), the prerequisite mod's own Home Point is used instead (which it captures from the vehicle's position when the stick is inserted), with yaw 0.
	//
	// Removing the stick cancels any pending respawn; that is the way to retire an enemy for good without breaking the block.

	/** Definition id of the vehicle to respawn, or null if no vehicle has been seen yet. */
	private Identifier respawnVehicleId;
	/** Parked pose relative to this block (x, y, z offset from the block's minimum corner; yaw), or null - see the section doc above. */
	private double[] respawnPose;
	/** Which vehicle respawnPose was recorded from - a newly bound or respawned vehicle is recorded afresh. */
	private UUID respawnPoseRecordedFor;
	/** World time at which the lost vehicle is replaced, or -1 while nothing is pending. */
	private long respawnAt = -1L;

	/** Horizontal speed below which a vehicle counts as parked - the same threshold the prerequisite mod uses for "stationary" resupply. */
	private static final double PARKED_VELOCITY_SQUARED = 0.02 * 0.02;

	private void enemyvehicleaddon$updateRespawn(ServerWorld world) {
		UUID bound = this.tudursvehiclemod$getBoundVehicleId();
		if (bound == null) {
			// Stick removed: see the section doc above.
			if (this.respawnAt >= 0) {
				this.respawnAt = -1L;
				this.markDirty();
			}
			return;
		}
		long now = world.getTime();
		AbstractVehicleEntity vehicle = DroneCenterBlockEntity.tudursvehiclemod$findBoundVehicle(world, bound);
		if (vehicle != null && !vehicle.tudursvehiclemod$isDestroyed()) {
			this.enemyvehicleaddon$remember(vehicle, bound);
			if (this.respawnAt >= 0) {
				// It turned up after all (e.g. a verification that ran while it was out of reach).
				this.respawnAt = -1L;
				this.markDirty();
			}
			return;
		}
		if (vehicle != null && this.respawnAt < 0) {
			// Destroyed. The inherited tick deactivates this block for it right after this.
			this.enemyvehicleaddon$scheduleRespawn(now);
		}
		if (this.respawnAt >= 0 && now >= this.respawnAt) {
			this.respawnAt = -1L;
			this.markDirty();
			if (!this.enemyvehicleaddon$respawn(world, vehicle)) {
				LOGGER.warn("Enemy Control Block at {} could not respawn its vehicle ({}).", this.getPos(), this.respawnVehicleId);
			}
		}
	}

	/** Called by EnemyLinkManager when a verification confirms the bound vehicle no longer exists. */
	public void enemyvehicleaddon$onVehicleConfirmedMissing(ServerWorld world) {
		if (this.respawnAt < 0) {
			this.enemyvehicleaddon$scheduleRespawn(world.getTime());
		}
	}

	private void enemyvehicleaddon$scheduleRespawn(long now) {
		int delaySeconds = com.example.enemyvehicleaddon.config.EnemyAddonConfig.get().respawnDelaySeconds;
		if (delaySeconds <= 0 || this.respawnVehicleId == null) {
			return;
		}
		this.respawnAt = now + delaySeconds * 20L;
		this.markDirty();
	}

	private void enemyvehicleaddon$remember(AbstractVehicleEntity vehicle, UUID bound) {
		Identifier definitionId = vehicle.getVehicleDefinitionId();
		if (definitionId != null && !definitionId.equals(this.respawnVehicleId)) {
			this.respawnVehicleId = definitionId;
			this.markDirty();
		}
		if (!bound.equals(this.respawnPoseRecordedFor) && this.tudursvehiclemod$isActive()
				&& vehicle.getVelocity().horizontalLengthSquared() <= PARKED_VELOCITY_SQUARED) {
			BlockPos pos = this.getPos();
			this.respawnPose = new double[]{vehicle.getX() - pos.getX(), vehicle.getY() - pos.getY(), vehicle.getZ() - pos.getZ(), vehicle.getYaw()};
			this.respawnPoseRecordedFor = bound;
			this.markDirty();
		}
	}

	/** Spawns a fresh vehicle at the remembered pose (full fuel/ammo), re-points the stick in slot 0 at it, removes the old wreck if it is still around, and re-activates this block (or, under redstone control, re-syncs with the current redstone signal instead). */
	private boolean enemyvehicleaddon$respawn(ServerWorld world, AbstractVehicleEntity wreck) {
		ItemStack stick = this.getStack(0);
		if (this.respawnVehicleId == null || !(stick.getItem() instanceof DroneControlStickItem)) {
			return false;
		}
		Optional<VehicleDefinition> definition = VehicleRegistry.get(this.respawnVehicleId);
		if (definition.isEmpty()) {
			return false;
		}
		BlockPos pos = this.getPos();
		double x;
		double y;
		double z;
		float yaw;
		if (this.respawnPose != null) {
			x = pos.getX() + this.respawnPose[0];
			y = pos.getY() + this.respawnPose[1];
			z = pos.getZ() + this.respawnPose[2];
			yaw = (float) this.respawnPose[3];
		} else {
			DroneWaypoint home = this.tudursvehiclemod$getHomePoint();
			x = pos.getX() + 0.5 + (home != null ? home.relX() : 0);
			y = pos.getY() + (home != null ? home.relY() : 1);
			z = pos.getZ() + 0.5 + (home != null ? home.relZ() : 0);
			yaw = 0f;
		}
		Identifier definitionId = this.respawnVehicleId;
		EntityType<?> entityType = Registries.ENTITY_TYPE.get(definition.get().entityType());
		// Same creation path as the prerequisite mod's own vehicle spawner (ModNetworking#handleSelectVehicle()).
		Entity created = entityType.create(world, spawned -> {
			if (spawned instanceof AbstractVehicleEntity spawnedVehicle) {
				spawnedVehicle.setVehicleDefinitionId(definitionId);
			}
		}, BlockPos.ofFloored(x, y, z), SpawnReason.SPAWN_ITEM_USE, false, false);
		if (!(created instanceof AbstractVehicleEntity newVehicle)) {
			if (created != null) {
				created.discard();
			}
			return false;
		}
		newVehicle.refreshPositionAndAngles(x, y, z, yaw, 0f);
		newVehicle.tudursvehiclemod$fillAllWeaponAmmoAndFuel();
		newVehicle.tudursvehiclemod$syncWeaponAmmo();
		if (wreck != null) {
			wreck.discard();
		}
		world.spawnEntity(newVehicle);

		// Edited in place rather than through setStack(): setStack() would re-capture the Home Point from the new vehicle's position, overwriting a custom Home Point.
		DroneControlStickItem.setRegisteredVehicleId(stick, newVehicle.getUuid());
		this.respawnPoseRecordedFor = newVehicle.getUuid();
		this.markDirty();

		if (this.tudursvehiclemod$isRedstoneControlEnabled()) {
			this.tudursvehiclemod$onRedstonePowerChanged(world, world.isReceivingRedstonePower(pos));
		} else if (!this.tudursvehiclemod$isActive()) {
			this.tudursvehiclemod$toggleActive(world);
		}
		return true;
	}

	// --- Pending setup (generated bases) ---
	//
	// A block placed by an enemy base structure (structure.EnemyBasePiece) carries an EnemyUnitSpec instead of a configured Drone Center: world generation runs without a live world, so nothing that needs one (spawning, the prerequisite mod's own setters that touch the world) can happen there. The spec is applied on this block's first tick - i.e. once a player comes near, in line with the dormancy policy - and the vehicle spawned exactly like a respawn.

	private EnemyUnitSpec pendingSetup;

	/** Called during world generation - stores only; see the section doc above. */
	public void enemyvehicleaddon$setPendingSetup(EnemyUnitSpec spec) {
		this.pendingSetup = spec;
		this.markDirty();
	}

	private void enemyvehicleaddon$applyPendingSetup(ServerWorld world) {
		EnemyUnitSpec spec = this.pendingSetup;
		if (spec == null) {
			return;
		}
		this.pendingSetup = null;
		if (!(this.getStack(0).getItem() instanceof DroneControlStickItem)) {
			this.setStack(0, new ItemStack(com.example.tudursvehiclemod.item.ModItems.DRONE_CONTROL_STICK));
		}
		this.tudursvehiclemod$setWaypoints(spec.waypoints());
		this.tudursvehiclemod$setGroundWaypoints(spec.groundWaypoints());
		spec.home().ifPresent(this::tudursvehiclemod$setHomePoint);
		this.tudursvehiclemod$setReturnViaPoint(spec.via().orElse(null));
		EnemyUnitSpec.Orbit orbit = spec.orbit();
		this.tudursvehiclemod$setConfig(world, orbit.speedFraction(), orbit.orbitAltitude(), orbit.radiusMultiplier());
		EnemyUnitSpec.Combat combat = spec.combat();
		this.tudursvehiclemod$setDummyPilotCombatConfig(combat.weaponIndex(), combat.attackStartAltitude(), combat.attackStopAltitude(),
				combat.searchRange(), combat.diveTargetYOffset());
		this.tudursvehiclemod$setDummyPilotConfig(world, combat.weaponIndex() >= 0, this.tudursvehiclemod$getDummyPilotSkinId(), false, "");
		this.enemyvehicleaddon$setEnemySettings(spec.leashRadius(), List.of());
		this.owner = null;
		this.respawnVehicleId = spec.vehicle();
		// Spec parking offsets are from the block's centre; the respawn pose is stored from its corner.
		this.respawnPose = new double[]{0.5 + spec.parking().get(0), spec.parking().get(1), 0.5 + spec.parking().get(2), spec.parkingYaw()};
		this.respawnPoseRecordedFor = null;
		this.respawnAt = -1L;
		this.markDirty();
		if (!this.enemyvehicleaddon$respawn(world, null)) {
			LOGGER.warn("Generated Enemy Control Block at {} could not spawn its vehicle ({}) - is the vehicle pack installed?", this.getPos(), spec.vehicle());
		}
	}

	// --- Structure template ---
	//
	// "Arming" a configured enemy block (from the enemy settings screen) marks its current setup - vehicle type and parked pose (from the respawn record), routes, Home Point, return via point, and every inherited Drone Center / dummy pilot / enemy setting - as a structure template. Save the surroundings with a structure block (vehicles excluded - only blocks are needed); every copy placed from it later sets itself up on its first tick: its routes are rotated to match the placement, the vehicle is spawned exactly as a respawn would (full fuel/ammo, stick re-pointed at it, activated), and the copy becomes owner-less (creative-only management) and disarmed.
	//
	// A copy is told apart from the original by position (and dimension): the armed block itself never spawns anything, and stays an ordinary working enemy block.
	//
	// Rotation: structure placement rotates block STATES but never block entity data, so the FACING recorded when arming, compared with the copy's own FACING, gives the number of quarter turns to apply to everything stored relative to the block. Mirroring cannot be told apart from a rotation this way (only one direction is recorded) and is not supported - placing a template mirrored reproduces it rotated instead.

	private boolean templateArmed;
	private BlockPos templateArmedPos;
	private String templateArmedDimension;
	private Direction templateArmedFacing;

	public boolean enemyvehicleaddon$isTemplateArmed() {
		return this.templateArmed;
	}

	/** Returns null on success, or the translation key of the reason arming is refused. */
	public String enemyvehicleaddon$armTemplate(ServerWorld world) {
		if (!(this.getStack(0).getItem() instanceof DroneControlStickItem)) {
			return "message.enemyvehicleaddon.template.no_stick";
		}
		if (this.respawnVehicleId == null) {
			// Recorded the first time the bound vehicle is seen - see the respawn section.
			return "message.enemyvehicleaddon.template.no_vehicle";
		}
		if (!this.getStack(1).isEmpty()) {
			// A route book stores ABSOLUTE coordinates, which cannot follow a template to another place. Taking it out copies its route into this block (the prerequisite mod's own behavior), where it is stored relative.
			return "message.enemyvehicleaddon.template.route_book";
		}
		this.templateArmed = true;
		this.templateArmedPos = this.getPos().toImmutable();
		this.templateArmedDimension = world.getRegistryKey().getValue().toString();
		this.templateArmedFacing = this.getCachedState().get(EnemyControlBlock.FACING);
		this.markDirty();
		return null;
	}

	public void enemyvehicleaddon$disarmTemplate() {
		this.templateArmed = false;
		this.templateArmedPos = null;
		this.templateArmedDimension = null;
		this.templateArmedFacing = null;
		this.markDirty();
	}

	private void enemyvehicleaddon$updateTemplatePlacement(ServerWorld world, BlockState state) {
		if (!this.templateArmed || this.templateArmedPos == null) {
			return;
		}
		String dimension = world.getRegistryKey().getValue().toString();
		if (this.templateArmedPos.equals(this.getPos()) && dimension.equals(this.templateArmedDimension)) {
			return; // The armed original itself - see the template section.
		}
		int quarterTurns = Math.floorMod(enemyvehicleaddon$quarterTurnIndex(state.get(EnemyControlBlock.FACING))
				- enemyvehicleaddon$quarterTurnIndex(this.templateArmedFacing), 4);
		this.enemyvehicleaddon$rotateStoredData(quarterTurns);
		this.enemyvehicleaddon$disarmTemplate();
		this.owner = null;
		this.respawnAt = -1L;
		this.respawnPoseRecordedFor = null;
		if (!(this.getStack(0).getItem() instanceof DroneControlStickItem)) {
			// Normally saved along with the block; recreated in case the template was saved without it.
			this.setStack(0, new ItemStack(com.example.tudursvehiclemod.item.ModItems.DRONE_CONTROL_STICK));
		}
		// Whatever the stick still points at is the ORIGINAL's vehicle - never touch it (no wreck to remove here).
		if (!this.enemyvehicleaddon$respawn(world, null)) {
			LOGGER.warn("Enemy Control Block template copy at {} could not spawn its vehicle ({}).", this.getPos(), this.respawnVehicleId);
		}
	}

	/** NORTH=0, EAST=1, SOUTH=2, WEST=3 - clockwise quarter turns seen from above; null (never armed with a facing) counts as NORTH. */
	private static int enemyvehicleaddon$quarterTurnIndex(Direction direction) {
		if (direction == null) {
			return 0;
		}
		return switch (direction) {
			case EAST -> 1;
			case SOUTH -> 2;
			case WEST -> 3;
			default -> 0;
		};
	}

	/** Rotates everything stored relative to this block by quarterTurns clockwise (seen from above) about the block's centre - the routes, Home Point, return via point and parked pose. The routes' own offsets are measured from the block centre (the prerequisite mod adds 0.5 to x/z when using them), so integer offsets stay integers; the parked pose is stored from the block's corner and is converted to centre-relative for the rotation. */
	private void enemyvehicleaddon$rotateStoredData(int quarterTurns) {
		if (quarterTurns == 0) {
			return;
		}
		List<DroneWaypoint> waypoints = new ArrayList<>();
		for (DroneWaypoint waypoint : this.tudursvehiclemod$getWaypoints()) {
			waypoints.add(enemyvehicleaddon$rotate(waypoint, quarterTurns));
		}
		this.tudursvehiclemod$setWaypoints(waypoints);

		List<GroundWaypoint> groundWaypoints = new ArrayList<>();
		for (GroundWaypoint waypoint : this.tudursvehiclemod$getGroundWaypoints()) {
			int[] rotated = enemyvehicleaddon$rotate(waypoint.relX(), waypoint.relZ(), quarterTurns);
			groundWaypoints.add(new GroundWaypoint(rotated[0], rotated[1], waypoint.speedFraction(), waypoint.waitTicks()));
		}
		this.tudursvehiclemod$setGroundWaypoints(groundWaypoints);

		this.tudursvehiclemod$setHomePoint(enemyvehicleaddon$rotate(this.tudursvehiclemod$getHomePoint(), quarterTurns));
		DroneWaypoint via = this.tudursvehiclemod$getReturnViaPoint();
		if (via != null) {
			this.tudursvehiclemod$setReturnViaPoint(enemyvehicleaddon$rotate(via, quarterTurns));
		}

		if (this.respawnPose != null) {
			double x = this.respawnPose[0] - 0.5;
			double z = this.respawnPose[2] - 0.5;
			for (int i = 0; i < quarterTurns; i++) {
				double previousX = x;
				x = -z;
				z = previousX;
			}
			this.respawnPose = new double[]{x + 0.5, this.respawnPose[1], z + 0.5,
					net.minecraft.util.math.MathHelper.wrapDegrees(this.respawnPose[3] + 90.0 * quarterTurns)};
		}
		this.markDirty();
	}

	private static DroneWaypoint enemyvehicleaddon$rotate(DroneWaypoint waypoint, int quarterTurns) {
		int[] rotated = enemyvehicleaddon$rotate(waypoint.relX(), waypoint.relZ(), quarterTurns);
		return new DroneWaypoint(rotated[0], waypoint.relY(), rotated[1], waypoint.speedFraction(), waypoint.rollAngle(),
				waypoint.rollManeuverabilityMultiplier(), waypoint.turnManeuverabilityMultiplier());
	}

	/** One clockwise quarter turn (seen from above, +x east, +z south) maps (x, z) to (-z, x) - e.g. north (0, -1) to east (1, 0). Yaw follows the same turn: +90 degrees each. */
	private static int[] enemyvehicleaddon$rotate(int x, int z, int quarterTurns) {
		for (int i = 0; i < quarterTurns; i++) {
			int previousX = x;
			x = -z;
			z = previousX;
		}
		return new int[]{x, z};
	}

	// --- Owner / access ---

	public UUID enemyvehicleaddon$getOwner() {
		return this.owner;
	}

	public void enemyvehicleaddon$setOwner(UUID owner) {
		this.owner = owner;
		this.markDirty();
	}

	/** Who may open any of this block's screens (the inherited Drone Center ones included): its owner, or any player currently in creative mode (i.e. someone who could equally just replace the block). An owner-less block (no placing player) is therefore creative-only. */
	public boolean enemyvehicleaddon$canManage(PlayerEntity player) {
		return player.isCreative() || (this.owner != null && this.owner.equals(player.getUuid()));
	}

	// --- Enemy settings ---

	public double enemyvehicleaddon$getLeashRadius() {
		return this.leashRadius;
	}

	public List<String> enemyvehicleaddon$getWhitelist() {
		return this.whitelist;
	}

	/** Sanitises everything (a client-sent payload is not trusted): radius clamped, names stripped, blank/over-long/whitespace-containing names dropped, case-insensitive duplicates removed (first spelling kept), list capped. */
	public void enemyvehicleaddon$setEnemySettings(double radius, List<String> names) {
		this.leashRadius = Double.isFinite(radius)
				? Math.max(MIN_LEASH_RADIUS, Math.min(MAX_LEASH_RADIUS, radius)) : DEFAULT_LEASH_RADIUS;
		this.enemyvehicleaddon$setWhitelistInternal(names);
		this.markDirty();
	}

	/** Also used by EnemyControlSettingsScreen (client side) to validate a name before adding it, so both sides agree on what is accepted. */
	public static boolean enemyvehicleaddon$isAcceptableName(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			if (Character.isWhitespace(name.charAt(i)) || name.charAt(i) == ';') {
				return false;
			}
		}
		return true;
	}

	private void enemyvehicleaddon$setWhitelistInternal(List<String> names) {
		List<String> cleaned = new ArrayList<>();
		Set<String> lower = new LinkedHashSet<>();
		if (names != null) {
			for (String raw : names) {
				String name = raw == null ? "" : raw.strip();
				if (!enemyvehicleaddon$isAcceptableName(name)) {
					continue;
				}
				if (lower.add(name.toLowerCase(Locale.ROOT))) {
					cleaned.add(name);
				}
				if (cleaned.size() >= MAX_WHITELIST_SIZE) {
					break;
				}
			}
		}
		this.whitelist = List.copyOf(cleaned);
		this.whitelistLowerCase = Set.copyOf(lower);
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("block.enemyvehicleaddon.enemy_control");
	}

	// --- Persistence ---

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		// See the constructor: data without this key (e.g. a block entity written by a structure template) keeps this block's AUTO_RESUME default rather than the prerequisite mod's MANUAL_REENABLE fallback.
		if (view.getOptionalInt("AutoDisableResumeMode").isEmpty()) {
			this.tudursvehiclemod$setAutoDisableResumeMode(DroneCenterBlockEntity.AutoDisableResumeMode.AUTO_RESUME);
		}
		String ownerString = view.getString("EnemyOwner", "");
		UUID parsedOwner = null;
		if (!ownerString.isEmpty()) {
			try {
				parsedOwner = UUID.fromString(ownerString);
			} catch (IllegalArgumentException ignored) {
				// Corrupted value - treated as owner-less (creative-only management) rather than failing the whole load.
			}
		}
		this.owner = parsedOwner;
		this.leashRadius = view.getDouble("EnemyLeashRadius", DEFAULT_LEASH_RADIUS);
		String encoded = view.getString("EnemyWhitelist", "");
		this.enemyvehicleaddon$setWhitelistInternal(encoded.isEmpty() ? List.of() : List.of(encoded.split(";")));

		String vehicleId = view.getString("EnemyRespawnVehicle", "");
		this.respawnVehicleId = vehicleId.isEmpty() ? null : Identifier.tryParse(vehicleId);
		this.respawnPose = null;
		String[] pose = view.getString("EnemyRespawnPose", "").split(",");
		if (pose.length == 4) {
			try {
				this.respawnPose = new double[]{Double.parseDouble(pose[0]), Double.parseDouble(pose[1]), Double.parseDouble(pose[2]), Double.parseDouble(pose[3])};
			} catch (NumberFormatException ignored) {
				// Falls back to the Home Point - see the respawn section doc.
			}
		}
		String poseFor = view.getString("EnemyRespawnPoseFor", "");
		UUID parsedPoseFor = null;
		if (!poseFor.isEmpty()) {
			try {
				parsedPoseFor = UUID.fromString(poseFor);
			} catch (IllegalArgumentException ignored) {
				// Treated as "not recorded yet" - the pose is simply recorded again.
			}
		}
		this.respawnPoseRecordedFor = parsedPoseFor;
		this.respawnAt = view.getLong("EnemyRespawnAt", -1L);
		this.pendingSetup = EnemyUnitSpec.fromJsonString(view.getString("EnemyPendingSetup", "")).orElse(null);

		this.templateArmed = view.getBoolean("EnemyTemplateArmed", false);
		this.templateArmedPos = null;
		this.templateArmedDimension = view.getString("EnemyTemplateDimension", "");
		this.templateArmedFacing = Direction.byId(view.getString("EnemyTemplateFacing", "north"));
		String[] armedPos = view.getString("EnemyTemplatePos", "").split(",");
		if (armedPos.length == 3) {
			try {
				this.templateArmedPos = new BlockPos(Integer.parseInt(armedPos[0]), Integer.parseInt(armedPos[1]), Integer.parseInt(armedPos[2]));
			} catch (NumberFormatException ignored) {
				// Malformed: disarmed below.
			}
		}
		if (this.templateArmedPos == null || this.templateArmedFacing == null) {
			this.templateArmed = false;
		}
	}

	/** Plain strings, the same style the prerequisite mod uses for its own Drone Center data (';' can never appear in an accepted name - see enemyvehicleaddon$isAcceptableName()). */
	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		if (this.owner != null) {
			view.putString("EnemyOwner", this.owner.toString());
		}
		view.putDouble("EnemyLeashRadius", this.leashRadius);
		if (!this.whitelist.isEmpty()) {
			view.putString("EnemyWhitelist", String.join(";", this.whitelist));
		}
		if (this.respawnVehicleId != null) {
			view.putString("EnemyRespawnVehicle", this.respawnVehicleId.toString());
		}
		if (this.respawnPose != null) {
			view.putString("EnemyRespawnPose", this.respawnPose[0] + "," + this.respawnPose[1] + "," + this.respawnPose[2] + "," + this.respawnPose[3]);
		}
		if (this.respawnPoseRecordedFor != null) {
			view.putString("EnemyRespawnPoseFor", this.respawnPoseRecordedFor.toString());
		}
		view.putLong("EnemyRespawnAt", this.respawnAt);
		if (this.pendingSetup != null) {
			String json = this.pendingSetup.toJsonString();
			if (json != null) {
				view.putString("EnemyPendingSetup", json);
			}
		}
		if (this.templateArmed && this.templateArmedPos != null && this.templateArmedFacing != null) {
			view.putBoolean("EnemyTemplateArmed", true);
			view.putString("EnemyTemplatePos", this.templateArmedPos.getX() + "," + this.templateArmedPos.getY() + "," + this.templateArmedPos.getZ());
			view.putString("EnemyTemplateDimension", this.templateArmedDimension == null ? "" : this.templateArmedDimension);
			view.putString("EnemyTemplateFacing", this.templateArmedFacing.asString());
		}
	}
}
