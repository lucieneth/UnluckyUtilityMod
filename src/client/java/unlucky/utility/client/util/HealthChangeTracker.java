package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * One confirmed damage/heal event per health change, for everything that draws one.
 *
 * <p>Nothing tells the client "this entity took 4 damage": the server syncs the new health value
 * and that is all. The change has to be recovered by diffing, and diffing is exactly the kind of
 * thing two modules must not do separately — HealthIndicators drawing {@code -4hp} while
 * HitEffects spawns particles for {@code -3.5} is not two opinions, it is one of them being
 * wrong, and no player can tell which. Worse, two diffs of the same tick's health can each
 * decide the change was theirs to report and the screen gets both.
 *
 * <p><b>What is diffed is health + absorption, not health.</b> A hit lands on the absorption
 * hearts first and leaves health untouched, so watching health alone shows nothing for the whole
 * time you are shielded. The caveat that comes with it: {@code absorptionAmount} is a plain
 * field, <b>not synched entity data</b>. The client really only knows its own — it simulates it
 * from the effect packets, which is how the yellow hearts render — and reads 0 for everyone
 * else, so the sum quietly collapses back to health and other players' absorption hits stay
 * invisible. There is nothing to be done client-side; the server never sends it.
 *
 * <p><b>A drop is not automatically a hit.</b> Absorption also falls when the effect simply runs
 * out, and a gapple timing out would otherwise flash a red {@code -4hp} for nothing. Health only
 * ever falls from damage, so a health drop is proof by itself; for an absorption-only drop the
 * confirmation is {@code hurtTime}, which vanilla sets to 10 on every hit that lands.
 *
 * <p>Runs only while something is listening — see {@link #addConsumer}. An idle client should
 * not be walking the entity list.
 */
public final class HealthChangeTracker {
	/**
	 * Ignore changes under this. Regen ticks are half a heart and would be constant noise, and
	 * a tracker that reported them would make every consumer filter them out again.
	 */
	private static final float MIN_CHANGE = 0.5f;

	/**
	 * Beyond this nothing displays a health change anyway, and tracking is what costs — a
	 * baseline per entity for the whole loaded world is a map that never stops growing.
	 * Deliberately wider than any consumer's own range so the consumer's limit is the visible
	 * one.
	 */
	private static final double MAX_DISTANCE_SQR = 64.0 * 64.0;

	/**
	 * How long after an attack a health change still counts as ours. Five ticks covers the
	 * server round trip on any playable connection without reaching into the next swing.
	 */
	private static final int ATTRIBUTION_TICKS = 5;

	/**
	 * Entity id to the tick we last swung at it, for {@link #attributable}.
	 *
	 * <p>A map rather than a single "last target" slot because an aura at two mobs, or a hit
	 * traded back and forth, puts several attacks inside one attribution window — and with one
	 * slot every hit but the newest would come back unattributed.
	 */
	private static final Map<Integer, Long> recentAttacks = new HashMap<>();

	/**
	 * Client ticks since load. Its own counter rather than {@code player.tickCount}, which starts
	 * again from zero every respawn and dimension change and would make the age of an attack
	 * recorded just before one come out negative.
	 */
	private static long clientTick;

	/** A confirmed change in one entity's effective health. */
	public record Event(int entityId, LivingEntity entity, float change, boolean attributableToSelf) {
		/** Whether this was damage rather than healing. */
		public boolean damage() {
			return change < 0.0f;
		}

		/** The size of the change, sign removed. */
		public float amount() {
			return Math.abs(change);
		}
	}

	/** Last effective health seen per entity, which is what a change is measured against. */
	private static final Map<Integer, Float> lastHealth = new HashMap<>();
	private static final Map<Integer, Float> lastAbsorption = new HashMap<>();

	/** This tick's events, rebuilt from scratch every tick and read by every consumer. */
	private static final List<Event> events = new ArrayList<>();

	/**
	 * Who is listening. Identity-based and explicit rather than "is any module enabled",
	 * because a tracker that inspects its own consumers to decide whether to run is a
	 * dependency in the wrong direction.
	 */
	private static final Set<Object> consumers = new HashSet<>();

	/** Weak, like the coordinators: never pin a dead level alive to notice it was replaced. */
	private static java.lang.ref.WeakReference<Object> level = new java.lang.ref.WeakReference<>(null);

	private HealthChangeTracker() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Starts tracking for {@code consumer}. Call from {@code onEnable}. */
	public static void addConsumer(Object consumer) {
		if (consumer != null && consumers.add(consumer) && consumers.size() == 1) {
			// First one in: every baseline is stale by however long nobody was watching, and a
			// diff against a stale baseline is a fabricated event on the first tick.
			reset();
		}
	}

	/** Stops tracking for {@code consumer}. Call from {@code onDisable}. */
	public static void removeConsumer(Object consumer) {
		if (consumers.remove(consumer) && consumers.isEmpty()) {
			reset();
		}
	}

	/** This tick's confirmed changes. Empty on any tick nothing changed. */
	public static List<Event> events() {
		return events;
	}

	/**
	 * Rebuilds this tick's events.
	 *
	 * <p>Called at the <em>start</em> of the client tick, before modules run, so every consumer
	 * reads the same list within the same tick regardless of registration order.
	 */
	public static void onTickStart() {
		events.clear();
		// Before the consumer check, and before anything can return: an attack recorded while the
		// clock was standing still would keep looking like it happened this very tick.
		clientTick++;
		recentAttacks.values().removeIf(at -> clientTick - at > ATTRIBUTION_TICKS);
		if (consumers.isEmpty()) {
			return;
		}
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) {
			reset();
			return;
		}
		if (mc.level != level.get()) {
			// Entity ids are reassigned per world, so a baseline kept across a dimension change
			// is a number belonging to whatever now happens to hold that id.
			level = new java.lang.ref.WeakReference<>(mc.level);
			reset();
			return;
		}
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}
			int id = living.getId();
			if (mc.player.distanceToSqr(living) > MAX_DISTANCE_SQR) {
				// Drop the baseline rather than keeping it: an entity that wanders back into
				// range would otherwise report every point it lost while nobody was watching as
				// one enormous hit.
				lastHealth.remove(id);
				lastAbsorption.remove(id);
				continue;
			}
			float health = living.getHealth();
			float absorption = living.getAbsorptionAmount();
			Float wasHealth = lastHealth.put(id, health);
			Float wasAbsorption = lastAbsorption.put(id, absorption);
			// First sight: take a baseline. Its health did not change, we just met it.
			if (wasHealth == null || wasAbsorption == null) {
				continue;
			}
			float change = (health + absorption) - (wasHealth + wasAbsorption);
			if (Math.abs(change) < MIN_CHANGE) {
				continue;
			}
			if (change < 0.0f && !confirmedHit(living, health, wasHealth)) {
				continue;
			}
			events.add(new Event(id, living, change, attributable(living)));
		}
		// Entities that left, so their vitals cannot be diffed against a stale value if they
		// come back, and so the maps cannot grow forever.
		lastHealth.keySet().removeIf(id -> mc.level.getEntity(id) == null);
		lastAbsorption.keySet().removeIf(id -> mc.level.getEntity(id) == null);
	}

	/** Drops every baseline and pending event. World change, disconnect, panic. */
	public static void reset() {
		lastHealth.clear();
		lastAbsorption.clear();
		events.clear();
		// Entity ids are reassigned per world, so a pending attack kept across one would attribute
		// the next hit on whatever now holds that id to us.
		recentAttacks.clear();
	}

	/**
	 * Whether a drop in effective health was really a hit — see the class note on absorption
	 * expiry, which is the case this exists to reject.
	 */
	private static boolean confirmedHit(LivingEntity living, float health, float wasHealth) {
		return health < wasHealth || living.hurtTime > 0;
	}

	/**
	 * Records a swing at {@code target}, for {@link #attributable} to match a later health drop
	 * against. Called from {@code MultiPlayerGameModeMixin}, which every attack path in the client
	 * — manual clicks, Aura, TriggerBot, Criticals' replayed swing — goes through.
	 *
	 * <p><b>Why this exists instead of vanilla's own record.</b> The obvious source is
	 * {@code Player.getLastHurtMob()}, and on the client it is always null: {@code Player.attack}
	 * only reaches {@code setLastHurtMob} inside {@code if (target.hurtOrSimulate(...))}, and off
	 * a {@code ServerLevel} that call lands on {@code Entity.hurtClient}, which is a bare
	 * {@code return false} that no {@code LivingEntity} overrides. Everything in that block —
	 * knockback, the attack visuals, the record — is server-only. The client has to keep its own.
	 */
	public static void onAttack(Entity target) {
		if (target instanceof LivingEntity) {
			recentAttacks.put(target.getId(), clientTick);
		}
	}

	/**
	 * Whether the local player plausibly caused this damage.
	 *
	 * <p>Deliberately weak, and named so callers know it. The client is never told who dealt a
	 * hit; all this knows is that we swung at this entity within the last few ticks and that its
	 * health then fell. That is right almost always and wrong in the interesting cases — someone
	 * else's arrow landing on the mob we happen to be hitting, a sweep hitting neighbours we never
	 * targeted, or a crystal, whose damage lands on players rather than on the thing we attacked.
	 * A setting built on it ("own hits only") is a filter, not a guarantee.
	 */
	private static boolean attributable(LivingEntity living) {
		Long at = recentAttacks.get(living.getId());
		return at != null && clientTick - at <= ATTRIBUTION_TICKS;
	}
}
