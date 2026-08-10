package unlucky.utility.client.util;

import java.util.function.Predicate;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.settings.EntityListSetting;

/**
 * One target filter and ranking implementation for every combat/aim module.
 *
 * <p>The important default is social, not mathematical: friends are ignored. A new targeting
 * module has to opt in to aiming at a friend instead of remembering to opt out. The rest of the
 * request is explicit so Aura, aim assist and projectile aim cannot quietly disagree about what
 * "closest" or "inside FOV" means.
 */
public final class TargetingUtil {
	private static final double SCORE_EPSILON = 1.0e-9;

	public enum Priority {
		CLOSEST,
		LOWEST_HEALTH,
		SMALLEST_ANGLE,
		LOWEST_ARMOR,
		DISTANCE_ANGLE
	}

	/** Mutable builder used for one selection pass. Defaults are safe PvP targeting. */
	public static final class Filter {
		private boolean players = true;
		private boolean hostiles = true;
		private boolean passives;
		private EntityListSetting hostileTypes;
		private EntityListSetting passiveTypes;
		private boolean ignoreFriends = true;
		private boolean includeDead;
		private boolean includeSpectators;
		private boolean includeInvisible;
		private boolean includeFakePlayers = true;
		private double range = 6.0;
		private double fov = 360.0;
		private boolean lineOfSight;
		private Priority priority = Priority.CLOSEST;
		private double distanceWeight = 0.55;
		private double angleWeight = 0.45;
		private Predicate<LivingEntity> extra = entity -> true;

		public Filter groups(boolean players, boolean hostiles, boolean passives) {
			this.players = players;
			this.hostiles = hostiles;
			this.passives = passives;
			return this;
		}

		public Filter typeLists(EntityListSetting hostiles, EntityListSetting passives) {
			this.hostileTypes = hostiles;
			this.passiveTypes = passives;
			return this;
		}

		public Filter ignoreFriends(boolean ignore) {
			this.ignoreFriends = ignore;
			return this;
		}

		public Filter includeDead(boolean include) {
			this.includeDead = include;
			return this;
		}

		public Filter includeSpectators(boolean include) {
			this.includeSpectators = include;
			return this;
		}

		public Filter includeInvisible(boolean include) {
			this.includeInvisible = include;
			return this;
		}

		/** Whether client-side practice players are eligible targets. */
		public Filter fakePlayers(boolean include) {
			this.includeFakePlayers = include;
			return this;
		}

		public Filter range(double range) {
			this.range = Math.max(0.0, range);
			return this;
		}

		/** Full cone width in degrees; 360 disables the FOV gate. */
		public Filter fov(double fov) {
			this.fov = Mth.clamp(fov, 0.0, 360.0);
			return this;
		}

		public Filter lineOfSight(boolean required) {
			this.lineOfSight = required;
			return this;
		}

		public Filter priority(Priority priority) {
			this.priority = priority == null ? Priority.CLOSEST : priority;
			return this;
		}

		/** Weights are normalized, so callers may supply percentages or fractions. */
		public Filter distanceAngleWeights(double distance, double angle) {
			this.distanceWeight = Math.max(0.0, distance);
			this.angleWeight = Math.max(0.0, angle);
			return this;
		}

		public Filter extra(Predicate<LivingEntity> predicate) {
			this.extra = predicate == null ? entity -> true : predicate;
			return this;
		}
	}

	private TargetingUtil() {
	}

	/** Returns the best matching entity, with entity id as a deterministic final tie-break. */
	public static LivingEntity select(LivingEntity source, Iterable<? extends Entity> candidates,
			Filter filter) {
		if (source == null || candidates == null || filter == null) {
			return null;
		}
		LivingEntity best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (Entity candidate : candidates) {
			if (!(candidate instanceof LivingEntity living) || !matches(source, living, filter)) {
				continue;
			}
			double score = score(source, living, filter);
			if (score + SCORE_EPSILON < bestScore
					|| (Math.abs(score - bestScore) <= SCORE_EPSILON
							&& (best == null || living.getId() < best.getId()))) {
				best = living;
				bestScore = score;
			}
		}
		return best;
	}

	/** Applies every common filter, including friends, visibility, range and FOV. */
	public static boolean matches(LivingEntity source, LivingEntity target, Filter filter) {
		if (target == source || !matchesGroup(target, filter.players, filter.hostiles,
				filter.passives, filter.hostileTypes, filter.passiveTypes)) {
			return false;
		}
		if (!filter.includeFakePlayers && target instanceof FakePlayerEntity) {
			return false;
		}
		if (!filter.includeDead && !target.isAlive()) {
			return false;
		}
		if (!filter.includeSpectators && target.isSpectator()) {
			return false;
		}
		if (!filter.includeInvisible && target.isInvisible()) {
			return false;
		}
		if (filter.ignoreFriends && target instanceof Player
				&& FriendManager.isFriend(target.getUUID())) {
			return false;
		}
		if (source.distanceToSqr(target) > filter.range * filter.range) {
			return false;
		}
		if (filter.fov < 360.0 && angleDegrees(source, target) > filter.fov * 0.5) {
			return false;
		}
		if (filter.lineOfSight && !source.hasLineOfSight(target)) {
			return false;
		}
		return filter.extra.test(target);
	}

	/** Group classification shared with legacy callers in {@link CombatUtil}. */
	public static boolean matchesGroup(Entity entity, boolean players, boolean hostiles,
			boolean passives, EntityListSetting hostileTypes, EntityListSetting passiveTypes) {
		if (!(entity instanceof LivingEntity)) {
			return false;
		}
		if (entity instanceof Player) {
			return players;
		}
		// Mannequins are player-shaped practice dummies, not mobs.
		if (entity instanceof Mannequin) {
			return players;
		}
		if (entity instanceof Enemy) {
			return hostiles && (hostileTypes == null || hostileTypes.allows(entity.getType()));
		}
		return passives && (passiveTypes == null || passiveTypes.allows(entity.getType()));
	}

	/** Smallest three-dimensional angle from the source look vector to the target box centre. */
	public static double angleDegrees(LivingEntity source, Entity target) {
		Vec3 toward = target.getBoundingBox().getCenter().subtract(source.getEyePosition());
		if (toward.lengthSqr() < 1.0e-12) {
			return 0.0;
		}
		double dot = Mth.clamp(source.getLookAngle().dot(toward.normalize()), -1.0, 1.0);
		return Math.toDegrees(Math.acos(dot));
	}

	private static double score(LivingEntity source, LivingEntity target, Filter filter) {
		double distance = Math.sqrt(source.distanceToSqr(target));
		double angle = angleDegrees(source, target);
		return switch (filter.priority) {
			case LOWEST_HEALTH -> target.getHealth() + target.getAbsorptionAmount();
			case SMALLEST_ANGLE -> angle;
			case LOWEST_ARMOR -> target.getArmorValue();
			case DISTANCE_ANGLE -> weighted(distance / Math.max(filter.range, 1.0e-6),
					angle / 180.0, filter.distanceWeight, filter.angleWeight);
			default -> distance;
		};
	}

	private static double weighted(double distance, double angle, double distanceWeight,
			double angleWeight) {
		double total = distanceWeight + angleWeight;
		return total <= 1.0e-12 ? distance :
				(distance * distanceWeight + angle * angleWeight) / total;
	}
}
