package unlucky.utility.client.util;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

/**
 * What an explosion at a given point would actually do to somebody.
 *
 * <p>Kept apart from any module on purpose: CrystalAura wants it for a target, AnchorAura
 * wants the same arithmetic for a different radius, AutoTotem wants it for you, AutoLog wants
 * to know whether the next one is lethal, and a bed module would want it too. One
 * implementation is also one place for the estimate to be wrong, which matters more than the
 * duplication — an aura that disagrees with the server about self-damage kills you.
 *
 * <p><b>The exposure sampling is vanilla's own.</b> {@link ServerExplosion#getSeenPercent} is
 * public, static, and touches nothing but {@code Entity.level()} and {@code Level.clip} — so
 * the client can call the exact method the server will, and the ray sampling can never drift.
 * That is the expensive and fiddly half of this file, and we do not own it.
 *
 * <p><b>What we do own, and why.</b> Two pieces cannot be borrowed. The damage curve lives on
 * {@link net.minecraft.world.level.ExplosionDamageCalculator}, whose methods want an
 * {@code Explosion} — an interface whose {@code level()} is a {@code ServerLevel} we do not
 * have — so the four-line formula is reproduced below and cited. And enchantment protection
 * goes through {@code EnchantmentHelper.getDamageProtection(ServerLevel, ...)}, same problem,
 * so the two protection enchantments that matter are read off the armour by registry key.
 * That last one is the only genuinely hardcoded rule here and the one most likely to age:
 * it assumes Protection is 1 point per level and Blast Protection 2, which has been true for
 * a decade but is data-driven since 1.20.5 and could stop being true without a compile error.
 */
public final class ExplosionDamageUtil {
	/** End crystal. */
	public static final float CRYSTAL_RADIUS = 6.0f;
	/** Respawn anchor detonating outside the Nether, and a bed outside the Overworld. */
	public static final float ANCHOR_RADIUS = 5.0f;
	public static final float BED_RADIUS = 5.0f;
	public static final float TNT_RADIUS = 4.0f;

	private ExplosionDamageUtil() {
	}

	/**
	 * Damage before any armour, exactly as vanilla's default
	 * {@code ExplosionDamageCalculator.getEntityDamageAmount} computes it:
	 *
	 * <pre>
	 *   q      = radius * 2
	 *   d      = distance(center, entity) / q
	 *   impact = (1 - d) * seenPercent
	 *   damage = (impact² + impact) / 2 * 7 * q + 1
	 * </pre>
	 *
	 * Zero once the target is outside the radius, which is where the real method stops too.
	 */
	public static float raw(Vec3 center, float radius, Entity target) {
		if (target == null || center == null) {
			return 0.0f;
		}
		float q = radius * 2.0f;
		double distance = Math.sqrt(target.distanceToSqr(center)) / q;
		if (distance > 1.0) {
			return 0.0f;
		}
		double impact = (1.0 - distance) * ServerExplosion.getSeenPercent(center, target);
		return (float) ((impact * impact + impact) / 2.0 * 7.0 * q + 1.0);
	}

	/**
	 * Damage an explosion would land on {@code target} after everything that reduces it.
	 *
	 * <p>Applied in vanilla's order, which is not the order the settings screen implies:
	 * difficulty scaling first and players only, then armour and toughness, then Resistance,
	 * then enchantment protection. Doing armour before difficulty gives a different — and
	 * wrong — number.
	 */
	public static float damage(Vec3 center, float radius, LivingEntity target) {
		return afterProtection(target, raw(center, radius, target));
	}

	/** The reduction half of {@link #damage}, for callers that already have a raw number. */
	public static float afterProtection(LivingEntity target, float raw) {
		if (target == null || raw <= 0.0f) {
			return 0.0f;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return raw;
		}
		DamageSource source = mc.level.damageSources().explosion(null, null);
		float damage = raw;

		if (target instanceof Player) {
			Difficulty difficulty = mc.level.getDifficulty();
			damage = switch (difficulty) {
				case PEACEFUL -> 0.0f;
				case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
				case NORMAL -> damage;
				case HARD -> damage * 3.0f / 2.0f;
			};
			if (damage <= 0.0f) {
				return 0.0f;
			}
		}

		damage = CombatRules.getDamageAfterAbsorb(target, damage, source, target.getArmorValue(),
				(float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		damage = afterResistance(target, damage);
		return CombatRules.getDamageAfterMagicAbsorb(damage, protectionPoints(target));
	}

	/** Damage the local player would take. */
	public static float self(Vec3 center, float radius) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? 0.0f : damage(center, radius, mc.player);
	}

	/**
	 * Effective health: what actually has to be spent before the entity dies.
	 *
	 * <p>Absorption counts — a golden-apple shield really does absorb an explosion — which is
	 * why every caller here should ask this rather than {@code getHealth()}.
	 */
	public static float effectiveHealth(LivingEntity target) {
		return target == null ? 0.0f : target.getHealth() + target.getAbsorptionAmount();
	}

	/**
	 * Whether the explosion would leave {@code target} alive with {@code margin} health to
	 * spare. The margin is what makes this usable for an anti-suicide check: surviving on
	 * a quarter of a heart is not surviving in any sense the next tick will respect.
	 */
	public static boolean survivable(Vec3 center, float radius, LivingEntity target, float margin) {
		return effectiveHealth(target) - damage(center, radius, target) > margin;
	}

	/** {@link #survivable} for the local player. Answers false with no player, which is safe. */
	public static boolean selfSurvivable(Vec3 center, float radius, float margin) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && survivable(center, radius, mc.player, margin);
	}

	private static float afterResistance(LivingEntity target, float damage) {
		MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
		if (resistance == null) {
			return damage;
		}
		int reduction = (resistance.getAmplifier() + 1) * 5;
		return Math.max(damage * (25 - reduction) / 25.0f, 0.0f);
	}

	/**
	 * Protection points across the worn armour, in the units
	 * {@link CombatRules#getDamageAfterMagicAbsorb} expects (it clamps to 20 itself).
	 *
	 * <p>Read by registry key rather than through {@code EnchantmentHelper}, which needs a
	 * {@code ServerLevel}. Same technique as {@code GearUtil}'s enchant names and
	 * {@code AutoTool}'s efficiency lookup — and the namespace is checked so a mod's own
	 * "protection" is not silently fed into a vanilla formula.
	 */
	public static float protectionPoints(LivingEntity target) {
		float points = 0.0f;
		for (EquipmentSlot slot : new EquipmentSlot[] {
				EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ItemStack stack = target.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			points += enchantLevel(stack, "protection");
			points += enchantLevel(stack, "blast_protection") * 2.0f;
		}
		return points;
	}

	/** Level of a vanilla enchantment on a stack, by registry path. */
	private static int enchantLevel(ItemStack stack, String path) {
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
