package unlucky.utility.client.util;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Damage that has not happened yet but is already decided.
 *
 * <p>Two modules ask these questions and they must not answer them differently: AutoTotem
 * wants a totem in hand before the landing, AutoLog wants to be gone before it. If one of them
 * thinks a fall is survivable and the other does not, the disagreement shows up as a totem
 * spent on a logout or a logout that never came — so the arithmetic lives here, once.
 *
 * <p>Explosion damage itself belongs to {@link ExplosionDamageUtil}; this file is about
 * <em>finding</em> the things that are going to hurt and asking it.
 */
public final class DamageForecast {
	/** Blocks of fall the vanilla formula gives you for free. */
	public static final float FALL_GRACE = 3.0f;

	/** How far out to look for things that explode. A crystal cannot reach past 12 blocks. */
	public static final double EXPLOSION_SEARCH = 12.0;

	/** Deepest a ground scan goes before it calls the answer void. */
	private static final int GROUND_SCAN = 384;

	private DamageForecast() {
	}

	/**
	 * Blocks between an entity's feet and whatever it is going to land on, or -1 when the
	 * answer is "nothing, all the way down".
	 *
	 * <p>A straight column scan rather than a movement simulation. It is wrong for something
	 * moving sideways over a ledge, and right for the case that matters — already falling,
	 * nothing to be done about it — at a cost of a few hundred block lookups.
	 */
	public static double distanceToGround(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || entity == null) {
			return -1;
		}
		BlockPos pos = entity.blockPosition();
		int floor = Math.max(mc.level.getMinY(), pos.getY() - GROUND_SCAN);
		for (int y = pos.getY() - 1; y >= floor; y--) {
			BlockPos below = new BlockPos(pos.getX(), y, pos.getZ());
			if (!mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()) {
				return entity.getY() - (y + 1);
			}
		}
		return -1;
	}

	/**
	 * Distance below a predicted footprint rather than below the entity's current centre.
	 *
	 * <p>AntiVoid moves sideways, which is exactly the case the cheap column overload says it
	 * does not model. Five collider rays cover the centre and inset corners of the supplied
	 * box. The closest support wins: a toe still over the ledge is support, while a future box
	 * wholly beyond it is true void. Fluids do not count as solid recovery ground.
	 */
	public static double distanceToGround(Entity entity, AABB footprint) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || entity == null || footprint == null) {
			return -1;
		}
		double insetX = Math.min(0.05, footprint.getXsize() * 0.25);
		double insetZ = Math.min(0.05, footprint.getZsize() * 0.25);
		double centreX = (footprint.minX + footprint.maxX) * 0.5;
		double centreZ = (footprint.minZ + footprint.maxZ) * 0.5;
		double[] xs = { centreX, footprint.minX + insetX, footprint.maxX - insetX,
				footprint.minX + insetX, footprint.maxX - insetX };
		double[] zs = { centreZ, footprint.minZ + insetZ, footprint.minZ + insetZ,
				footprint.maxZ - insetZ, footprint.maxZ - insetZ };
		double startY = footprint.minY + 1.0e-4;
		double endY = mc.level.getMinY() - 1.0;
		double nearest = Double.POSITIVE_INFINITY;
		for (int i = 0; i < xs.length; i++) {
			Vec3 start = new Vec3(xs[i], startY, zs[i]);
			HitResult hit = mc.level.clip(new ClipContext(start, new Vec3(xs[i], endY, zs[i]),
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
			if (hit.getType() != HitResult.Type.MISS) {
				nearest = Math.min(nearest, Math.max(0.0, startY - hit.getLocation().y));
			}
		}
		return Double.isFinite(nearest) ? nearest : -1;
	}

	/** Nothing below at all, and on the way down. */
	public static boolean fallingIntoVoid(Entity entity) {
		return entity != null && entity.getDeltaMovement().y < 0 && distanceToGround(entity) < 0;
	}

	/**
	 * Fall damage the entity is already committed to: what it has fallen so far, plus what is
	 * left before the ground. Zero when there is no ground to hit — that is the void's
	 * problem, not the fall formula's.
	 */
	public static float predictedFallDamage(LivingEntity entity) {
		double drop = distanceToGround(entity);
		if (drop < 0) {
			return 0.0f;
		}
		return fallDamage(entity, (float) (entity.fallDistance + drop));
	}

	/**
	 * Fall damage for a given distance, after the enchantments that reduce it.
	 *
	 * <p>Armour is deliberately not applied — vanilla's fall damage bypasses it. Feather
	 * Falling counts at three points per level, Protection at one, which is the same currency
	 * {@link CombatRules#getDamageAfterMagicAbsorb} spends everywhere else.
	 */
	public static float fallDamage(LivingEntity entity, float distance) {
		float raw = Math.max(0.0f, distance - FALL_GRACE);
		if (raw <= 0.0f) {
			return 0.0f;
		}
		float points = 0.0f;
		for (EquipmentSlot slot : new EquipmentSlot[] {
				EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ItemStack stack = entity.getItemBySlot(slot);
			points += enchantLevel(stack, "feather_falling") * 3.0f;
			points += enchantLevel(stack, "protection");
		}
		return CombatRules.getDamageAfterMagicAbsorb(raw, points);
	}

	/**
	 * The most damage any one nearby explosive would do to {@code entity} if it went off this
	 * instant. Crystals and primed TNT only — a bed or an anchor is inert until somebody uses
	 * it, and a prediction that assumes every bed is about to detonate is noise.
	 *
	 * <p><b>Memoised per tick for the local player</b>, which is the only caller that repeats.
	 * The cost here is not the entity query, it is the ray sampling underneath: vanilla's
	 * {@code getSeenPercent} casts on the order of forty rays <em>per explosive</em>, and both
	 * AutoTotem and AutoLog ask this question every tick. Answering it twice would double a
	 * genuinely expensive number for two callers that cannot disagree anyway.
	 */
	public static float worstNearbyExplosion(LivingEntity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || entity == null) {
			return 0.0f;
		}
		boolean cacheable = entity == mc.player;
		long now = mc.level.getGameTime();
		if (cacheable && now == cachedTick) {
			return cachedWorst;
		}
		float worst = computeWorstNearbyExplosion(mc, entity);
		if (cacheable) {
			cachedTick = now;
			cachedWorst = worst;
		}
		return worst;
	}

	/** Tick the memo was taken on, and its answer. Local player only — see above. */
	private static long cachedTick = Long.MIN_VALUE;
	private static float cachedWorst;

	private static float computeWorstNearbyExplosion(Minecraft mc, LivingEntity entity) {
		AABB box = entity.getBoundingBox().inflate(EXPLOSION_SEARCH);
		float worst = 0.0f;
		for (Entity other : mc.level.getEntities(entity, box)) {
			float radius;
			if (other instanceof EndCrystal) {
				radius = ExplosionDamageUtil.CRYSTAL_RADIUS;
			} else if (other instanceof PrimedTnt) {
				radius = ExplosionDamageUtil.TNT_RADIUS;
			} else {
				continue;
			}
			worst = Math.max(worst, ExplosionDamageUtil.damage(other.position(), radius, entity));
		}
		return worst;
	}

	/** Level of a vanilla enchantment on a stack, by registry path. */
	private static int enchantLevel(ItemStack stack, String path) {
		if (stack.isEmpty()) {
			return 0;
		}
		for (var entry : stack.getEnchantments().entrySet()) {
			Holder<Enchantment> holder = entry.getKey();
			Optional<ResourceKey<Enchantment>> key = holder.unwrapKey();
			if (key.isPresent() && key.get().identifier().getNamespace().equals("minecraft")
					&& key.get().identifier().getPath().equals(path)) {
				return entry.getIntValue();
			}
		}
		return 0;
	}
}
