package com.example.enemyvehicleaddon.entity;

import com.example.enemyvehicleaddon.mixin.AircraftEntityAccessor;
import com.example.enemyvehicleaddon.mixin.DummyPilotEntityAccessor;
import com.example.enemyvehicleaddon.config.EnemyAddonConfig;
import com.example.enemyvehicleaddon.link.EnemyLinkManager;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.entity.AircraftEntity;
import com.example.tudursvehiclemod.entity.DummyPilotEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** The Enemy Control Block's own dummy pilot: the prerequisite mod's DummyPilotEntity (seat occupation, skin, turret aiming, firing, aircraft attack via the Carrier-lock pursuit - all inherited unchanged), retargeted from hostile mobs to players through the two hooks that class exposes for exactly this purpose (tudursvehiclemod$targetClass()/tudursvehiclemod$isValidTarget()).
 *
 * A player is a valid target when ALL of: alive, not spectator, not creative (unless EnemyAddonConfig#targetCreativePlayers), NOT on the owning block's whitelist (an empty whitelist therefore means every player, the block's own owner included), and horizontally within the leash radius of the owning block. The leash is a vertical cylinder around the block rather than a sphere - for aircraft, cruising altitude should not eat into how far out a target on the ground counts as "inside the base".
 *
 * An aircraft does not engage while on the ground or landing - see enemyvehicleaddon$isGroundedOrLanding().
 *
 * Leaving the leash (or any other condition becoming false) drops the target immediately rather than at the next 20-tick reacquire, and for an aircraft also releases its pursuit lock so it falls straight back to its normal Drone Center route - see tick().
 *
 * Settings are pushed in from EnemyControlBlockEntity (at creation, then every tick while seated) rather than read back from the block, so an unloaded block chunk never has to be loaded just to evaluate a target. They are also saved with the pilot, so a vehicle that loads before its block (see link.EnemyLinkManager) is already fully functional; with no leash center known at all nothing counts as a valid target - fail-safe rather than attacking with defaults.
 *
 * Every tick the pilot also reports to link.EnemyLinkManager whether it is engaged (drives dormancy/force-loading), and learns from it whether its vehicle is still the one registered for its block - if not (the block's stick was swapped, or the block was removed, while this vehicle was unloaded), this is a stale leftover: the vehicle is unlinked from that block and the pilot removes itself. */
public class EnemyPilotEntity extends DummyPilotEntity {

	private BlockPos leashCenter;
	private double leashRadius = 1000.0;
	/** Lower-cased player names. Replaced wholesale (never mutated) by enemyvehicleaddon$setEnemySettings(). */
	private Set<String> whitelist = Set.of();

	public EnemyPilotEntity(EntityType<? extends LivingEntity> type, World world) {
		super(type, world);
	}

	/** Called by EnemyControlBlockEntity - see this class's own doc. whitelistLowerCase must already be lower-cased (Locale.ROOT) and must not be mutated afterwards by the caller. */
	public void enemyvehicleaddon$setEnemySettings(BlockPos center, double radius, Set<String> whitelistLowerCase) {
		this.leashCenter = center == null ? null : center.toImmutable();
		this.leashRadius = radius;
		this.whitelist = whitelistLowerCase;
	}

	@Override
	protected Class<? extends LivingEntity> tudursvehiclemod$targetClass() {
		return PlayerEntity.class;
	}

	@Override
	protected boolean tudursvehiclemod$isValidTarget(LivingEntity candidate) {
		if (!super.tudursvehiclemod$isValidTarget(candidate)) {
			return false;
		}
		if (!(candidate instanceof PlayerEntity player) || player.isSpectator()) {
			return false;
		}
		if (player.isCreative() && !EnemyAddonConfig.get().targetCreativePlayers) {
			return false;
		}
		if (player.getEntityWorld() != this.getEntityWorld()) {
			return false;
		}
		if (this.whitelist.contains(player.getName().getString().toLowerCase(Locale.ROOT))) {
			return false;
		}
		return this.enemyvehicleaddon$isWithinLeash(player);
	}

	/** Horizontal (XZ) distance from the owning block's centre - see this class's own doc for why a cylinder. */
	public boolean enemyvehicleaddon$isWithinLeash(Entity entity) {
		if (this.leashCenter == null) {
			return false;
		}
		double dx = entity.getX() - (this.leashCenter.getX() + 0.5);
		double dz = entity.getZ() - (this.leashCenter.getZ() + 0.5);
		return dx * dx + dz * dz <= this.leashRadius * this.leashRadius;
	}

	@Override
	public void tick() {
		boolean server = !this.getEntityWorld().isClient();
		if (server) {
			// Before the inherited combat AI runs: otherwise a target that just became invalid keeps being aimed at/fired on (and re-locked by an aircraft every tick) until the next 20-tick reacquire.
			DummyPilotEntityAccessor accessor = (DummyPilotEntityAccessor) this;
			LivingEntity cached = accessor.enemyvehicleaddon$getCurrentTarget();
			if (cached != null && !this.tudursvehiclemod$isValidTarget(cached)) {
				accessor.enemyvehicleaddon$setCurrentTarget(null);
			}
		}
		super.tick();
		if (!server || this.isRemoved() || !(this.getVehicle() instanceof AbstractVehicleEntity vehicle)
				|| !(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		if (vehicle instanceof AircraftEntity aircraft && this.enemyvehicleaddon$isGroundedOrLanding(aircraft, serverWorld)) {
			// See enemyvehicleaddon$isGroundedOrLanding(): whatever the inherited AI just picked up is dropped again before the aircraft's next tick can act on it.
			((DummyPilotEntityAccessor) this).enemyvehicleaddon$setCurrentTarget(null);
			((AircraftEntityAccessor) aircraft).enemyvehicleaddon$setCarrierLockedTargetUuid(null);
		}
		boolean engaged = ((DummyPilotEntityAccessor) this).enemyvehicleaddon$getCurrentTarget() != null;
		if (vehicle instanceof AircraftEntity aircraft) {
			this.enemyvehicleaddon$releaseInvalidLock(aircraft);
			engaged |= ((AircraftEntityAccessor) aircraft).enemyvehicleaddon$getCarrierLockedTargetUuid() != null;
			this.enemyvehicleaddon$retractGearIfAirborne(aircraft);
		}
		if (this.leashCenter != null && !EnemyLinkManager.reportPilot(serverWorld, this.leashCenter, vehicle, engaged)) {
			if (this.leashCenter.equals(vehicle.tudursvehiclemod$getDroneCenterPos())) {
				vehicle.tudursvehiclemod$setDroneLink(null);
			}
			if (vehicle instanceof AircraftEntity aircraft) {
				((AircraftEntityAccessor) aircraft).enemyvehicleaddon$setCarrierLockedTargetUuid(null);
			}
			this.stopRiding();
			this.discard();
		}
	}

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		if (this.leashCenter != null) {
			view.putInt("EnemyCenterX", this.leashCenter.getX());
			view.putInt("EnemyCenterY", this.leashCenter.getY());
			view.putInt("EnemyCenterZ", this.leashCenter.getZ());
		}
		view.putDouble("EnemyLeashRadius", this.leashRadius);
		view.putString("EnemyWhitelist", String.join(";", this.whitelist));
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		int missing = Integer.MIN_VALUE;
		int x = view.getInt("EnemyCenterX", missing);
		int y = view.getInt("EnemyCenterY", missing);
		int z = view.getInt("EnemyCenterZ", missing);
		this.leashCenter = x == missing || y == missing || z == missing ? null : new BlockPos(x, y, z);
		this.leashRadius = view.getDouble("EnemyLeashRadius", 1000.0);
		String encoded = view.getString("EnemyWhitelist", "");
		this.whitelist = encoded.isEmpty() ? Set.of() : Set.copyOf(java.util.List.of(encoded.split(";")));
	}

	/** An enemy pilot's own aircraft spends most of its time locked onto a target (see this class's own doc) rather than in the prerequisite mod's ordinary circular-orbit autopilot - the two paths each retract gear on their own once climbed clearly above their own respective reference altitude (the Drone Center for the orbit path, the current target for the pursuit path; see AircraftEntity#tudursvehiclemod$updateDroneAutopilot()/#tudursvehiclemod$updateCarrierLockPursuit() for both), but an aircraft that locks onto its very first target before ever taking the orbit path even once relies entirely on the pursuit path's own check - and a target standing at meaningfully different height than this block (uphill, in a valley, on a rooftop) can leave that check comparing altitude against the wrong reference for a while. This is a supplementary, addon-owned check against a reference this pilot always has regardless of which path is currently driving the aircraft - the leash center - so gear reliably retracts shortly after leaving the ground no matter what the aircraft is currently doing. Retract-only, exactly like both of the prerequisite mod's own checks - nothing here ever (re-)deploys gear; an enemy aircraft has no landing sequence to reach the deploy-side of that same logic in the first place (see class doc: dormancy leaves it floating in place, never landing). */
	private void enemyvehicleaddon$retractGearIfAirborne(AircraftEntity aircraft) {
		if (this.leashCenter != null && aircraft.isGearDeployed() && aircraft.getY() > this.leashCenter.getY() + 2.0) {
			aircraft.toggleLandingGear();
		}
	}

	/** Height above the ground below which an aircraft counts as not yet airborne. */
	private static final double AIRBORNE_MIN_HEIGHT = 4.0;

	/** An enemy aircraft does not engage while it is still on (or just above) the ground, or while landing. The prerequisite mod's attack mode (the Carrier-lock pursuit) takes over the aircraft's flight completely and is built for an aircraft already in the air - engaging a player who is already near a parked enemy aircraft (typically the case for a generated base, which only comes alive when a player approaches) kept it from ever taking off; engaging during a resupply landing would abort the landing. Its normal route (takeoff included) or landing runs instead, and it engages once airborne. Checked against the actual ground below it (not its block's height), so it also works on uneven terrain and over buildings. */
	private boolean enemyvehicleaddon$isGroundedOrLanding(AircraftEntity aircraft, ServerWorld world) {
		if (aircraft.tudursvehiclemod$isDroneLandingInProgress()) {
			return true;
		}
		int groundTop = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
				net.minecraft.util.math.MathHelper.floor(aircraft.getX()), net.minecraft.util.math.MathHelper.floor(aircraft.getZ()));
		return aircraft.getY() - groundTop < AIRBORNE_MIN_HEIGHT;
	}

	/** Dropping the pilot's own cached target stops the pilot from re-locking, but does not end a pursuit lock the aircraft already holds (see AircraftEntityAccessor's own doc). Anything this aircraft is locked onto that this pilot would not itself pick right now - out of the leash, whitelisted, gone - is released here, handing the aircraft back to its normal route on its very next tick. */
	private void enemyvehicleaddon$releaseInvalidLock(AircraftEntity aircraft) {
		AircraftEntityAccessor lock = (AircraftEntityAccessor) aircraft;
		UUID lockedUuid = lock.enemyvehicleaddon$getCarrierLockedTargetUuid();
		if (lockedUuid == null || !(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		Entity locked = serverWorld.getEntity(lockedUuid);
		if (!(locked instanceof LivingEntity lockedLiving) || !this.tudursvehiclemod$isValidTarget(lockedLiving)) {
			lock.enemyvehicleaddon$setCarrierLockedTargetUuid(null);
		}
	}
}
