package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.settings.EntityListSetting;

/** Shared combat logic: target filtering and attack timing. */
public final class CombatUtil {
	private CombatUtil() {
	}

	/** Living, alive, not us, matching the enabled target groups. */
	public static boolean validTarget(Entity entity, boolean players, boolean hostiles, boolean passives) {
		return validTarget(entity, players, hostiles, passives, null, null);
	}

	/** Group check plus optional per-mob whitelists (null list = allow all). */
	public static boolean validTarget(Entity entity, boolean players, boolean hostiles, boolean passives,
			EntityListSetting hostileList, EntityListSetting passiveList) {
		Minecraft mc = Minecraft.getInstance();
		if (!(entity instanceof LivingEntity living) || entity == mc.player || !living.isAlive()) {
			return false;
		}
		if (entity instanceof Player player) {
			return players && !player.isSpectator();
		}
		// Mannequins are player-shaped practice dummies (a sibling Avatar, not a
		// Player), so they fall through the Enemy/passive buckets — treat them as
		// players so PvP-practice targeting picks them up.
		if (entity instanceof Mannequin) {
			return players;
		}
		// Enemy is the hostile marker interface — also covers ghasts, slimes,
		// hoglins and the dragon, which don't extend Monster
		if (entity instanceof Enemy) {
			return hostiles && (hostileList == null || hostileList.allows(entity.getType()));
		}
		return passives && (passiveList == null || passiveList.allows(entity.getType()));
	}

	/**
	 * Whether the next attack should fire. "Attributes" waits for the held
	 * weapon's full charge (attack speed attribute); otherwise a flat CPS timer.
	 */
	public static boolean ready(boolean attributes, double cps, int ticksSinceAttack) {
		if (attributes) {
			return Minecraft.getInstance().player.getAttackStrengthScale(0.0f) >= 1.0f;
		}
		return ticksSinceAttack >= 20.0 / cps;
	}

	/** Attacks the entity (packet + cooldown reset) and swings the arm. */
	public static void attack(Entity target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.gameMode != null) {
			mc.gameMode.attack(mc.player, target);
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
	}
}
