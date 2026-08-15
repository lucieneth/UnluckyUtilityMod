package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Stops other bodies shoving you around.
 *
 * <p><b>Collision push only.</b> Knockback, explosions, fluid currents and the fishing rod all
 * belong to {@link Velocity} and stay there. The two modules look similar from the outside and
 * are not: Velocity scales a <em>force the server applied to you</em>, this one declines a
 * <em>push the client is computing locally</em> because something is standing in your square.
 * Splitting them the other way — one module for "all incoming motion" — would mean a player who
 * wants to stop being nudged in a queue also silently turns off explosion knockback, which is a
 * combat decision they never made.
 *
 * <p><b>One hook, with the source classified there.</b> {@code Entity.push(Entity)} is the single
 * place vanilla turns "we are overlapping" into velocity, and it is already wrapped for Velocity.
 * Joining that wrap rather than adding a second one is what keeps the two composable: this module
 * decides whether the push happens at all, Velocity then decides how much of it survives.
 *
 * <p><b>Vertical is kept by default.</b> The horizontal shove is the annoyance; the vertical
 * component of a collision correction is how you end up on top of the boat instead of inside it,
 * and zeroing it wholesale produces stranger bugs than the one being fixed.
 */
public class NoPush extends Module {
	public final BooleanSetting playerPush = add(new BooleanSetting("Player push",
			"Ignore the body push from other players", true));
	public final BooleanSetting mobPush = add(new BooleanSetting("Mob push",
			"Ignore the body push from mobs", true));
	public final BooleanSetting vehiclePush = add(new BooleanSetting("Vehicle push",
			"Ignore the body push from boats, minecarts and other vehicles", false));
	public final BooleanSetting blockPush = add(new BooleanSetting("Block suffocation push",
			"Suppress the push that squeezes you out of a block you are inside", true));
	public final BooleanSetting horizontalOnly = add(new BooleanSetting("Horizontal only",
			"Keep the vertical part of a collision correction", true));
	public final BooleanSetting whileSneakingOnly = add(new BooleanSetting("While sneaking only",
			"Only ignore entity pushes while you are sneaking", false));

	public NoPush() {
		super("NoPush", "Ignores collision pushing from entities and blocks", Category.MOVEMENT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * The entity-push decision, from the shared {@code Entity.push} wrap.
	 *
	 * @param source   whoever is doing the pushing — what the toggles are about
	 * @param receiver whoever is being pushed; only ever ourselves matters here
	 * @param push     what vanilla worked out
	 * @return the push to apply, possibly nothing
	 */
	public Vec3 entityPush(Entity source, Entity receiver, Vec3 push) {
		if (!isEnabled() || receiver == null || receiver != mc().player || source == receiver) {
			return push;
		}
		if (whileSneakingOnly.get() && !receiver.isShiftKeyDown()) {
			return push;
		}
		if (!ignores(source)) {
			return push;
		}
		// Horizontal only keeps the Y term rather than the whole vector: vanilla's entity push is
		// flat, so this reads as a no-op today and stops the setting from lying the moment some
		// future push is not.
		return horizontalOnly.get() ? new Vec3(0.0, push.y, 0.0) : Vec3.ZERO;
	}

	/**
	 * Whether the block-escape push is suppressed.
	 *
	 * <p>Not gated on {@code While sneaking only} — that setting is about being jostled by other
	 * bodies. Being pushed out of a wall has nothing to do with whether you are crouching, and
	 * tying the two together would make the suffocation switch mean something different depending
	 * on a key.
	 */
	public boolean preventsBlockPush(Entity entity) {
		return isEnabled() && blockPush.get() && entity != null && entity == mc().player;
	}

	/**
	 * Player, mob, or vehicle — the classification the settings are written in.
	 *
	 * <p>Asked of the pushing entity's type rather than of what it is being used for: a horse
	 * someone else is riding pushes you as a mob, which is what it is.
	 */
	private boolean ignores(Entity source) {
		if (source instanceof Player) {
			return playerPush.get();
		}
		if (source instanceof LivingEntity) {
			return mobPush.get();
		}
		return vehiclePush.get();
	}
}
