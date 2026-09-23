package com.example.enemyvehicleaddon.block;

import com.example.enemyvehicleaddon.config.EnemyAddonConfig;
import com.example.enemyvehicleaddon.link.EnemyLinkState;
import com.example.enemyvehicleaddon.mixin.AbstractVehicleEntityAccessor;
import com.example.enemyvehicleaddon.registry.EnemyAddonBlockEntities;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/** Once per second, tops up every stationary vehicle within EnemyAddonConfig#supplyRadius - by default only enemy vehicles (those registered to an Enemy Control Block, see EnemyLinkState#isEnemyVehicle()).
 *
 * Supplies by exactly the prerequisite mod's own supply-vehicle rules (MCHeli-style FuelSupplyRange/AmmoSupplyRange/HealthSupplyRange), reusing its own per-second steps for ammo and health: 5% of max fuel, magazine then reserve (up to MaxAmmo) 10% at a time, 2% of max health. The reserve refill matters: the prerequisite mod's AUTO_RESUME only resumes once magazine + reserve are full.
 *
 * Catch-up: an unloaded block does not tick, and an enemy base is normally unloaded (dormant - see link.EnemyLinkManager) while no player is near. When loaded again, the supply missed since it was last active (capped by EnemyAddonConfig#supplyCatchUpMaxSeconds) is applied in one go to the first vehicles it finds within CATCH_UP_WINDOW_TICKS - a vehicle parked here cannot have moved in the meantime (dormant vehicles do not move), and entities may load a few ticks after the block itself. */
public class EnemySupplyBlockEntity extends BlockEntity {

	private static final int SUPPLY_INTERVAL_TICKS = 20;
	private static final int CATCH_UP_WINDOW_TICKS = 100;
	private static final float FUEL_FRACTION_PER_INTERVAL = 0.05f;
	/** receiveAmmoSupply() refills 10% of the magazine, then 10% of MaxAmmo into the reserve, per call - 20 calls always reach full. */
	private static final int MAX_AMMO_STEPS = 20;
	/** receiveHealthSupply() restores 2% per call - 50 calls always reach full health. */
	private static final int MAX_HEALTH_STEPS = 50;
	/** Same "stationary" threshold as the prerequisite mod's own supply (horizontal velocity only). */
	private static final double STATIONARY_VELOCITY_SQUARED = 0.02 * 0.02;

	/** World time of the last supply cycle, or -1 if never active. Saved - the basis of the catch-up. */
	private long lastSupplyTime = -1L;

	private boolean startedThisLoad;
	private int pendingCatchUpIntervals;
	private int catchUpWindowLeft;

	public EnemySupplyBlockEntity(BlockPos pos, BlockState state) {
		super(EnemyAddonBlockEntities.ENEMY_SUPPLY, pos, state);
	}

	public static void tick(World world, BlockPos pos, BlockState state, EnemySupplyBlockEntity blockEntity) {
		if (world instanceof ServerWorld serverWorld) {
			blockEntity.serverTick(serverWorld);
		}
	}

	private void serverTick(ServerWorld world) {
		long now = world.getTime();
		if (!this.startedThisLoad) {
			this.startedThisLoad = true;
			if (this.lastSupplyTime >= 0 && now > this.lastSupplyTime) {
				long missedSeconds = Math.min((now - this.lastSupplyTime) / SUPPLY_INTERVAL_TICKS,
						EnemyAddonConfig.get().supplyCatchUpMaxSeconds);
				this.pendingCatchUpIntervals = (int) missedSeconds;
				this.catchUpWindowLeft = CATCH_UP_WINDOW_TICKS;
			}
		}
		if (this.catchUpWindowLeft > 0 && --this.catchUpWindowLeft == 0) {
			this.pendingCatchUpIntervals = 0;
		}
		if ((now + this.getPos().hashCode()) % SUPPLY_INTERVAL_TICKS != 0) {
			return;
		}
		this.lastSupplyTime = now;
		this.markDirty();

		List<AbstractVehicleEntity> targets = this.findTargets(world);
		if (targets.isEmpty()) {
			return;
		}
		int intervals = 1 + this.pendingCatchUpIntervals;
		this.pendingCatchUpIntervals = 0;
		for (AbstractVehicleEntity vehicle : targets) {
			supply(vehicle, intervals);
		}
	}

	private List<AbstractVehicleEntity> findTargets(ServerWorld world) {
		EnemyAddonConfig config = EnemyAddonConfig.get();
		double radius = config.supplyRadius;
		Vec3d center = Vec3d.ofCenter(this.getPos());
		EnemyLinkState links = EnemyLinkState.get(world);
		return world.getEntitiesByClass(AbstractVehicleEntity.class, new Box(this.getPos()).expand(radius),
				vehicle -> vehicle.isAlive()
						&& !vehicle.tudursvehiclemod$isDestroyed()
						&& vehicle.getVelocity().horizontalLengthSquared() <= STATIONARY_VELOCITY_SQUARED
						&& vehicle.squaredDistanceTo(center) <= radius * radius
						&& (config.supplyNonEnemyVehicles || links.isEnemyVehicle(vehicle.getUuid())));
	}

	/** Applies `intervals` seconds' worth of supply at once, using the prerequisite mod's own per-second steps for ammo and health (see AbstractVehicleEntityAccessor). Step counts are capped where more steps can no longer change anything: ammo reaches full within 20 steps (magazine and reserve 10% each), health within 50 (2% each). */
	private static void supply(AbstractVehicleEntity vehicle, int intervals) {
		if (!vehicle.tudursvehiclemod$isFuelless() && vehicle.getMaxFuel() > 0f && vehicle.getFuel() < vehicle.getMaxFuel()) {
			// addFuel() clamps to max fuel.
			vehicle.tudursvehiclemod$addFuel(vehicle.getMaxFuel() * Math.min(1f, FUEL_FRACTION_PER_INTERVAL * intervals));
		}
		AbstractVehicleEntityAccessor accessor = (AbstractVehicleEntityAccessor) vehicle;
		int ammoSteps = Math.min(intervals, MAX_AMMO_STEPS);
		for (int i = 0; i < ammoSteps; i++) {
			accessor.enemyvehicleaddon$receiveAmmoSupply();
		}
		int healthSteps = Math.min(intervals, MAX_HEALTH_STEPS);
		for (int i = 0; i < healthSteps && vehicle.getHealth() < vehicle.getMaxHealth(); i++) {
			accessor.enemyvehicleaddon$receiveHealthSupply();
		}
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.lastSupplyTime = view.getLong("LastSupplyTime", -1L);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putLong("LastSupplyTime", this.lastSupplyTime);
	}
}
