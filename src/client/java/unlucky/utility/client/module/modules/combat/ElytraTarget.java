package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Flies an elytra toward a chosen target and keeps it from flying into terrain doing it.
 *
 * <p>There is no silent variant, and that is a physics fact rather than a missing feature.
 * Elytra heading comes from {@code getLookAngle()} on the player's <em>real</em> rotation
 * inside vanilla's travel code, so a spoofed server-only angle would leave the glide going
 * exactly where it was already going. Steering therefore turns the camera, and the shared
 * {@link RotationManager} lease is still taken so a combat rotation can outrank it rather
 * than silently fight it every tick.
 *
 * <p>Collision avoidance is checked before the steering is applied, not after. The follow
 * vector points at where the target will be, which on a low chase is frequently straight
 * through a hillside; when the look-ahead clip finds terrain the module pitches up and away
 * instead, because a glide that hits a wall at full speed ends the fight rather than winning it.
 */
public class ElytraTarget extends Module {
	private static final double FIREWORK_MIN_INTERVAL = 10.0;

	public final NumberSetting range = add(new NumberSetting("Range",
			"Maximum target range", 40, 5, 120, 1));
	public final NumberSetting fov = add(new NumberSetting("FOV",
			"Full targeting cone", 120, 10, 360, 5));
	public final BooleanSetting lineOfSight = add(new BooleanSetting("Require line of sight",
			"Only chase a target you can actually see", true));
	public final ModeSetting priority = add(new ModeSetting("Priority",
			"Target ranking", "Closest angle", "Closest angle", "Closest distance", "Lowest health"));
	public final BooleanSetting players = add(new BooleanSetting("Players", "Target players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostile", "Target hostile mobs", false));
	public final BooleanSetting passives = add(new BooleanSetting("Passive", "Target passive mobs", false));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never chase a friend", true));

	public final NumberSetting followDistance = add(new NumberSetting("Follow distance",
			"How far short of the target the aim point sits", 2.5, 0, 10, 0.5));
	public final NumberSetting verticalOffset = add(new NumberSetting("Vertical offset",
			"Raise or lower the aim point relative to the target", 0, -5, 5, 0.5));
	public final NumberSetting predictionTicks = add(new NumberSetting("Target prediction",
			"Ticks of target velocity to lead by", 4, 0, 20, 1));
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Maximum degrees turned per tick", 12, 1, 90, 1));

	public final BooleanSetting safeCollision = add(new BooleanSetting("Safe collision",
			"Pitch away instead of steering into terrain", true));
	public final NumberSetting lookAhead = add(new NumberSetting("Collision look-ahead",
			"Ticks of the current velocity tested for terrain", 6, 1, 40, 1), safeCollision::get);

	public final BooleanSetting autoFirework = add(new BooleanSetting("Auto firework",
			"Use a rocket when the glide drops below the minimum speed", false));
	public final NumberSetting fireworkMinSpeed = add(new NumberSetting("Firework min speed",
			"Speed below which a rocket is used", 1.0, 0.1, 3.0, 0.05), autoFirework::get);
	public final NumberSetting fireworkCooldown = add(new NumberSetting("Firework cooldown",
			"Minimum ticks between rockets", 20, 5, 200, 5), autoFirework::get);

	public final NumberSetting pauseDurability = add(new NumberSetting("Pause below elytra durability",
			"Stop steering when the elytra is this close to breaking, as a percent", 10, 0, 100, 1));

	private int fireworkTimer;

	public ElytraTarget() {
		super("ElytraTarget", "Steers an elytra glide toward a target without flying into terrain",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		fireworkTimer = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null
				|| !player.isFallFlying() || mc().gui.screen() != null) {
			InventoryActionCoordinator.release(this);
			return;
		}
		if (fireworkTimer > 0) {
			fireworkTimer--;
		}
		if (elytraTooWorn(player)) {
			InventoryActionCoordinator.release(this);
			return;
		}

		LivingEntity target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(players.get(), hostiles.get(), passives.get())
						.ignoreFriends(ignoreFriends.get()).range(range.get()).fov(fov.get())
						.lineOfSight(lineOfSight.get()).priority(targetPriority()));
		if (target == null) {
			InventoryActionCoordinator.release(this);
			return;
		}

		Vec3 aim = followPoint(player, target);
		if (safeCollision.get()) {
			aim = avoidTerrain(player, aim);
		}
		if (!steer(player, aim)) {
			return; // a stronger rotation owner has the tick; do not also burn a rocket
		}
		if (autoFirework.get()) {
			maybeFirework(player);
		}
	}

	/** Where the target will be, pulled back by the follow distance along the approach line. */
	private Vec3 followPoint(LocalPlayer player, LivingEntity target) {
		Vec3 predicted = target.getBoundingBox().getCenter()
				.add(target.getDeltaMovement().scale(predictionTicks.get()))
				.add(0.0, verticalOffset.get(), 0.0);
		Vec3 approach = predicted.subtract(player.getEyePosition());
		double distance = approach.length();
		if (distance <= followDistance.get() + 1.0e-6) {
			return predicted;
		}
		return player.getEyePosition()
				.add(approach.scale((distance - followDistance.get()) / distance));
	}

	/**
	 * Replaces an aim point that would fly the player into terrain with one that climbs away.
	 *
	 * <p>The probe follows the current velocity rather than the desired heading: what kills a
	 * glide is where it is actually going in the next few ticks, and the steering being applied
	 * this tick has barely started to bend it.
	 */
	private Vec3 avoidTerrain(LocalPlayer player, Vec3 aim) {
		Vec3 eye = player.getEyePosition();
		Vec3 ahead = eye.add(player.getDeltaMovement().scale(lookAhead.get()));
		HitResult hit = mc().level.clip(new ClipContext(eye, ahead, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.MISS) {
			return aim;
		}
		// Keep the horizontal intent, discard the descent: climbing is the only escape that
		// works while already committed to a direction at glide speed.
		Vec3 horizontal = new Vec3(aim.x - eye.x, 0.0, aim.z - eye.z);
		double reach = Math.max(1.0, horizontal.length());
		return eye.add(horizontal.normalize().scale(reach)).add(0.0, reach, 0.0);
	}

	/**
	 * Turns toward {@code aim} at the configured rate.
	 *
	 * @return whether this module owns the rotation this tick
	 */
	private boolean steer(LocalPlayer player, Vec3 aim) {
		Vec3 delta = aim.subtract(player.getEyePosition());
		double horizontal = delta.horizontalDistance();
		if (horizontal < 1.0e-6 && Math.abs(delta.y) < 1.0e-6) {
			return false;
		}
		float wantedYaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
		float wantedPitch = (float) (-(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG));
		float speed = rotationSpeed.getFloat();
		float yaw = approach(player.getYRot(), wantedYaw, speed);
		float pitch = Mth.clamp(approach(player.getXRot(), wantedPitch, speed), -90.0f, 90.0f);

		if (!RotationManager.rotateIfAllowed(yaw, pitch, RotationManager.PRIORITY_PLACEMENT)) {
			return false;
		}
		player.setYRot(yaw);
		player.setXRot(pitch);
		return true;
	}

	/** Uses a rocket when the glide has decayed, borrowing the hotbar for exactly one tick. */
	private void maybeFirework(LocalPlayer player) {
		if (fireworkTimer > 0 || player.getDeltaMovement().length() >= fireworkMinSpeed.get()) {
			return;
		}
		if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
			mc().gameMode.useItem(player, InteractionHand.OFF_HAND);
			fireworkTimer = fireworkCooldown.getInt();
			return;
		}
		int slot = hotbarFirework(player);
		if (slot < 0 || !InventoryActionCoordinator.acquire(this,
				InventoryActionCoordinator.PRIORITY_COMBAT)) {
			return;
		}
		int previous = player.getInventory().getSelectedSlot();
		if (InventoryActionCoordinator.selectHotbar(this, slot)) {
			mc().gameMode.useItem(player, InteractionHand.MAIN_HAND);
			fireworkTimer = (int) Math.max(FIREWORK_MIN_INTERVAL, fireworkCooldown.get());
			InventoryActionCoordinator.selectHotbar(this, previous);
		}
		InventoryActionCoordinator.release(this);
	}

	private static int hotbarFirework(LocalPlayer player) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (player.getInventory().getItem(slot).is(Items.FIREWORK_ROCKET)) {
				return slot;
			}
		}
		return -1;
	}

	/** True once the worn elytra is inside the configured reserve of its durability. */
	private boolean elytraTooWorn(LocalPlayer player) {
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		if (!chest.isDamageableItem() || chest.getMaxDamage() <= 0) {
			return false;
		}
		double remaining = 100.0 * (chest.getMaxDamage() - chest.getDamageValue()) / chest.getMaxDamage();
		return remaining <= pauseDurability.get();
	}

	private TargetingUtil.Priority targetPriority() {
		return priority.is("Closest distance") ? TargetingUtil.Priority.CLOSEST
				: priority.is("Lowest health") ? TargetingUtil.Priority.LOWEST_HEALTH
				: TargetingUtil.Priority.SMALLEST_ANGLE;
	}

	private static float approach(float current, float wanted, float max) {
		return current + Mth.clamp(Mth.wrapDegrees(wanted - current), -max, max);
	}
}
