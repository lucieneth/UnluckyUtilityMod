package unlucky.utility.client.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.modules.combat.Hitboxes;

/**
 * Narrows Hitboxes to the one vanilla entity-pick call that selected the crosshair target.
 *
 * <p>The redirect lives in {@code ProjectileUtil}, which is also used by real projectiles.
 * A scoped context is therefore load-bearing: checking only whether the module is enabled
 * would enlarge arrow collision and every unrelated caller too.
 */
public final class HitboxPickContext {
	private static final ThreadLocal<Hitboxes> ACTIVE = new ThreadLocal<>();

	private HitboxPickContext() {
	}

	public static void enter(Hitboxes module) {
		ACTIVE.set(module);
	}

	public static void exit() {
		ACTIVE.remove();
	}

	public static AABB expand(Entity entity, AABB vanilla) {
		Hitboxes module = ACTIVE.get();
		return module == null ? vanilla : module.expand(entity, vanilla);
	}
}
