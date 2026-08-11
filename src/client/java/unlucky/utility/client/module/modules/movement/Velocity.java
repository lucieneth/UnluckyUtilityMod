package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Scales external forces without erasing movement the player already had.
 * Every multiplier uses 0 = cancel that force and 1 = vanilla.
 */
public class Velocity extends Module {
	public final BooleanSetting knockback = add(new BooleanSetting("Knockback",
			"Modify knockback received from attacks", true));
	public final NumberSetting horizontal = add(new NumberSetting("Horizontal",
			"Horizontal attack knockback multiplier", 0.0, 0.0, 1.0, 0.01), knockback::get);
	public final NumberSetting vertical = add(new NumberSetting("Vertical",
			"Vertical attack knockback multiplier", 0.0, 0.0, 1.0, 0.01), knockback::get);

	public final BooleanSetting explosions = add(new BooleanSetting("Explosions",
			"Modify explosion knockback", true));
	public final NumberSetting explosionsHorizontal = add(new NumberSetting("Explosion horizontal",
			"Horizontal explosion knockback multiplier", 0.0, 0.0, 1.0, 0.01), explosions::get);
	public final NumberSetting explosionsVertical = add(new NumberSetting("Explosion vertical",
			"Vertical explosion knockback multiplier", 0.0, 0.0, 1.0, 0.01), explosions::get);

	public final BooleanSetting liquids = add(new BooleanSetting("Liquids",
			"Modify movement added by flowing water and lava", true));
	public final NumberSetting liquidsHorizontal = add(new NumberSetting("Liquid horizontal",
			"Horizontal liquid-current multiplier", 0.0, 0.0, 1.0, 0.01), liquids::get);
	public final NumberSetting liquidsVertical = add(new NumberSetting("Liquid vertical",
			"Vertical liquid-current multiplier", 0.0, 0.0, 1.0, 0.01), liquids::get);

	public final BooleanSetting entityPush = add(new BooleanSetting("Entity push",
			"Modify collision pushing from other entities", true));
	public final NumberSetting entityPushAmount = add(new NumberSetting("Entity push amount",
			"Entity collision-push multiplier", 0.0, 0.0, 1.0, 0.01), entityPush::get);
	public final BooleanSetting blocks = add(new BooleanSetting("Blocks",
			"Prevent blocks from pushing you out while suffocating", true));
	public final BooleanSetting sinking = add(new BooleanSetting("Sinking",
			"Stop passive sinking in liquids unless jump or sneak is held", false));
	public final BooleanSetting fishing = add(new BooleanSetting("Fishing",
			"Prevent fishing rods from pulling you", false));
	public final BooleanSetting onlyGround = add(new BooleanSetting("Only on ground",
			"Leave velocity untouched while airborne", false));
	public final NumberSetting minimum = add(new NumberSetting("Minimum force",
			"Ignore forces smaller than this horizontal speed", 0.0, 0.0, 1.0, 0.01));

	public Velocity() {
		super("Velocity", "Controls knockback and other external movement forces", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** An entity-motion packet is absolute, so scale only its change from our current velocity. */
	public Vec3 attackKnockback(Entity entity, Vec3 incoming) {
		if (!isEnabled() || !knockback.get() || entity != mc().player || !canModify(entity, incoming)) {
			return incoming;
		}
		Vec3 current = entity.getDeltaMovement();
		return new Vec3(
				current.x + (incoming.x - current.x) * horizontal.get(),
				current.y + (incoming.y - current.y) * vertical.get(),
				current.z + (incoming.z - current.z) * horizontal.get());
	}

	/** Explosion knockback is additive, unlike the absolute entity-motion packet. */
	public Vec3 explosionKnockback(Vec3 knockback) {
		if (!isEnabled() || !explosions.get() || !canModify(mc().player, knockback)) {
			return knockback;
		}
		return knockback.multiply(explosionsHorizontal.get(), explosionsVertical.get(), explosionsHorizontal.get());
	}

	public Vec3 fluidFlow(Entity entity, Vec3 flow) {
		if (!isEnabled() || !liquids.get() || entity != mc().player) {
			return flow;
		}
		return flow.multiply(liquidsHorizontal.get(), liquidsVertical.get(), liquidsHorizontal.get());
	}

	public Vec3 entityPush(Entity entity, Vec3 push) {
		if (!isEnabled() || !entityPush.get() || entity != mc().player) {
			return push;
		}
		return push.scale(entityPushAmount.get());
	}

	public boolean preventsBlockPush(Entity entity) {
		return isEnabled() && blocks.get() && entity == mc().player;
	}

	public boolean preventsFishingPull(Entity entity) {
		return isEnabled() && fishing.get() && entity == mc().player;
	}

	private boolean canModify(Entity entity, Vec3 force) {
		return entity != null && (!onlyGround.get() || entity.onGround())
				&& force.horizontalDistance() >= minimum.get();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || !sinking.get() || mc().options.keyJump.isDown() || mc().options.keyShift.isDown()
				|| (!player.isInWater() && !player.isInLava()) || player.getDeltaMovement().y >= 0.0) {
			return;
		}
		Vec3 movement = player.getDeltaMovement();
		player.setDeltaMovement(movement.x, 0.0, movement.z);
	}
}
