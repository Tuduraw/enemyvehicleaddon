package com.example.enemyvehicleaddon.link;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-dimension record of every Enemy Control Block and the vehicle bound to it, kept OUTSIDE both the block entity and the vehicle entity because the whole point is to reason about one of them while the other is not loaded (see EnemyLinkManager). Saved as data/enemyvehicleaddon_links.dat in the dimension's folder. */
public class EnemyLinkState extends PersistentState {

	public enum Mode {
		/** Nothing is force-loaded for this link: the vehicle only moves while players keep its chunk loaded. */
		DORMANT,
		/** Engaged with a target - vehicle (and block) chunks kept loaded. */
		PURSUIT,
		/** Pursuit ended, flying back to its route - still kept loaded until back on it (or timed out). */
		RETURNING
	}

	/** Mutable runtime view of one link. Fields below the blank line are transient (not saved) - see EnemyLinkManager for their use. */
	public static final class Link {
		final BlockPos blockPos;
		UUID vehicleUuid;
		/** ChunkPos#toLong() of where the vehicle was last seen, or null if never seen. */
		Long lastVehicleChunk;
		Mode mode;
		/** Horizontal distance from the block at the moment pursuit began - the vehicle was on its circular orbit then, so this is "the orbit" to get back to (routes with waypoints use the waypoints instead). */
		double returnOrbitRadius;
		/** World time the current mode began. */
		long modeSince;

		long lastEngagedTime = Long.MIN_VALUE / 2;
		long verifyUntil;
		long nextVerifyAllowed;

		Link(BlockPos blockPos) {
			this.blockPos = blockPos.toImmutable();
			this.mode = Mode.DORMANT;
		}

		public BlockPos blockPos() {
			return this.blockPos;
		}

		public UUID vehicleUuid() {
			return this.vehicleUuid;
		}

		public Mode mode() {
			return this.mode;
		}
	}

	private record LinkData(BlockPos pos, Optional<UUID> vehicle, Optional<Long> lastVehicleChunk, String mode,
			double returnOrbitRadius, long modeSince) {

		static final Codec<LinkData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(LinkData::pos),
				Uuids.CODEC.optionalFieldOf("vehicle").forGetter(LinkData::vehicle),
				Codec.LONG.optionalFieldOf("last_vehicle_chunk").forGetter(LinkData::lastVehicleChunk),
				Codec.STRING.optionalFieldOf("mode", Mode.DORMANT.name()).forGetter(LinkData::mode),
				Codec.DOUBLE.optionalFieldOf("return_orbit_radius", 0.0).forGetter(LinkData::returnOrbitRadius),
				Codec.LONG.optionalFieldOf("mode_since", 0L).forGetter(LinkData::modeSince)
		).apply(instance, LinkData::new));
	}

	public static final Codec<EnemyLinkState> CODEC = LinkData.CODEC.listOf().fieldOf("links").codec()
			.xmap(EnemyLinkState::fromData, EnemyLinkState::toData);

	/** DataFixTypes null: this is addon-own data with nothing to fix; Fabric API explicitly supports a null type here. */
	public static final PersistentStateType<EnemyLinkState> TYPE =
			new PersistentStateType<>("enemyvehicleaddon_links", EnemyLinkState::new, CODEC, null);

	private final Map<Long, Link> links = new ConcurrentHashMap<>();

	public EnemyLinkState() {
	}

	public static EnemyLinkState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE);
	}

	private static EnemyLinkState fromData(List<LinkData> data) {
		EnemyLinkState state = new EnemyLinkState();
		for (LinkData entry : data) {
			Link link = new Link(entry.pos());
			link.vehicleUuid = entry.vehicle().orElse(null);
			link.lastVehicleChunk = entry.lastVehicleChunk().orElse(null);
			try {
				link.mode = Mode.valueOf(entry.mode());
			} catch (IllegalArgumentException e) {
				link.mode = Mode.DORMANT;
			}
			link.returnOrbitRadius = entry.returnOrbitRadius();
			link.modeSince = entry.modeSince();
			state.links.put(link.blockPos.asLong(), link);
		}
		return state;
	}

	private List<LinkData> toData() {
		List<LinkData> data = new ArrayList<>();
		for (Link link : this.links.values()) {
			data.add(new LinkData(link.blockPos, Optional.ofNullable(link.vehicleUuid), Optional.ofNullable(link.lastVehicleChunk),
					link.mode.name(), link.returnOrbitRadius, link.modeSince));
		}
		return data;
	}

	public Link get(BlockPos pos) {
		return this.links.get(pos.asLong());
	}

	public Link getOrCreate(BlockPos pos) {
		return this.links.computeIfAbsent(pos.asLong(), key -> {
			this.markDirty();
			return new Link(pos);
		});
	}

	public void remove(BlockPos pos) {
		if (this.links.remove(pos.asLong()) != null) {
			this.vehicleIndex = null;
			this.markDirty();
		}
	}

	/** Vehicle UUIDs of all links - rebuilt lazily after any change (see setVehicle()/remove()), since isEnemyVehicle() runs for every supply cycle, every enemy projectile explosion and every vehicle explosion, and the number of links grows with every generated base ever visited. */
	private volatile java.util.Set<UUID> vehicleIndex;

	/** The only way a link's vehicle is changed (keeps the index current). */
	public void setVehicle(Link link, UUID vehicleUuid) {
		link.vehicleUuid = vehicleUuid;
		this.vehicleIndex = null;
		this.markDirty();
	}

	/** Whether uuid is the vehicle currently registered for any Enemy Control Block in this dimension. */
	public boolean isEnemyVehicle(UUID uuid) {
		java.util.Set<UUID> index = this.vehicleIndex;
		if (index == null) {
			java.util.Set<UUID> rebuilt = new java.util.HashSet<>();
			for (Link link : this.links.values()) {
				if (link.vehicleUuid != null) {
					rebuilt.add(link.vehicleUuid);
				}
			}
			index = rebuilt;
			this.vehicleIndex = index;
		}
		return index.contains(uuid);
	}

	public Collection<Link> all() {
		return this.links.values();
	}
}
