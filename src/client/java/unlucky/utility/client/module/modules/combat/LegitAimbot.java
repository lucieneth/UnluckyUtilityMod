package unlucky.utility.client.module.modules.combat;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.module.modules.render.Freelook;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Gentle visible aim assistance near the crosshair.
 *
 * <p>This is intentionally not Aura with a smaller range: it never attacks, never silently
 * rotates, and never locks the exact centre of a hitbox. Normal mouse movement happens first;
 * this module adds a bounded, accelerated correction and yields an axis when the player pulls
 * it the other way.
 */
public class LegitAimbot extends Module {
	public final ModeSetting activation = add(new ModeSetting("Activation",
			"When visible aim assistance may run", "Attack held", "Attack held",
			"Attack or use held", "Always while weapon held"));
	public final BooleanSetting onlyWeapon = add(new BooleanSetting("Only weapon",
			"Assist only while holding a melee or projectile weapon", true));
	public final NumberSetting range = add(new NumberSetting("Range",
			"Maximum target distance", 4.0, 1.0, 8.0, 0.1));
	public final NumberSetting fov = add(new NumberSetting("FOV",
			"Full aim-assist cone in degrees", 35, 5, 180, 1));
	public final BooleanSetting lineOfSight = add(new BooleanSetting("Require line of sight",
			"Ignore targets hidden behind blocks", true));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never assist toward a friend", true));

	public final BooleanSetting players = add(new BooleanSetting("Players", "Target players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostile", "Target hostile mobs", false));
	public final BooleanSetting passives = add(new BooleanSetting("Passive", "Target passive mobs", false));
	public final ModeSetting priority = add(new ModeSetting("Priority",
			"How a target near the crosshair is chosen", "Smallest angle",
			"Smallest angle", "Closest"));
	public final NumberSetting stickiness = add(new NumberSetting("Target stickiness",
			"Minimum milliseconds before switching between valid targets", 250, 0, 2000, 50));

	public final NumberSetting strength = add(new NumberSetting("Strength",
			"Percentage of the remaining angle corrected each tick", 35, 1, 100, 1));
	public final NumberSetting maxYawSpeed = add(new NumberSetting("Max yaw speed",
			"Maximum horizontal correction in degrees per tick", 4, 0.1, 20, 0.1));
	public final NumberSetting maxPitchSpeed = add(new NumberSetting("Max pitch speed",
			"Maximum vertical correction in degrees per tick", 3, 0.1, 20, 0.1));
	public final NumberSetting acceleration = add(new NumberSetting("Acceleration",
			"How quickly correction velocity approaches its requested speed", 0.35, 0.05, 1.0, 0.05));
	public final NumberSetting deadzone = add(new NumberSetting("Deadzone",
			"Do not correct while already this close in degrees", 1.5, 0, 10, 0.1));
	public final NumberSetting prediction = add(new NumberSetting("Prediction",
			"Ticks of target movement to lead", 1, 0, 5, 1));
	public final BooleanSetting respectOppositeInput = add(new BooleanSetting(
			"Respect opposite mouse input",
			"Cancel assistance on an axis while the mouse deliberately pulls away", true));

	public final ModeSetting aimRegion = add(new ModeSetting("Aim region",
			"Body region in which a stable imperfect point is chosen", "Torso",
			"Torso", "Upper body", "Random body"));
	public final NumberSetting horizontalSpread = add(new NumberSetting("Horizontal spread",
			"Maximum offset as a percentage of hitbox width/depth", 20, 0, 45, 1));
	public final NumberSetting verticalMinimum = add(new NumberSetting("Vertical minimum",
			"Lowest aim height as a percentage of the hitbox", 30, 5, 90, 1));
	public final NumberSetting verticalMaximum = add(new NumberSetting("Vertical maximum",
			"Highest aim height as a percentage of the hitbox", 75, 10, 95, 1));
	public final NumberSetting refreshMinimum = add(new NumberSetting("Aim refresh minimum",
			"Shortest time a randomized body point is held, in milliseconds", 350, 100, 2000, 25));
	public final NumberSetting refreshMaximum = add(new NumberSetting("Aim refresh maximum",
			"Longest time a randomized body point is held, in milliseconds", 700, 100, 3000, 25));
	public final NumberSetting microDrift = add(new NumberSetting("Micro drift",
			"Slow visible drift around the stable body point, in degrees", 0.15, 0, 1, 0.05));

	private static double mouseYaw;
	private static double mousePitch;

	private LivingEntity target;
	private long targetSinceMs;
	private long nextAimRefreshMs;
	private double aimXFraction;
	private double aimYFraction = 0.5;
	private double aimZFraction;
	private float yawVelocity;
	private float pitchVelocity;

	public LegitAimbot() {
		super("LegitAimbot", "Adds gentle visible camera assistance near a target",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Called from the one existing MouseHandler.turnPlayer redirect. */
	public static void recordMouseTurn(double yawInput, double pitchInput) {
		// Entity.turn applies this exact 0.15 scale. Store degrees so the opposition
		// comparison speaks the same units as the aim error.
		mouseYaw += yawInput * 0.15;
		mousePitch += pitchInput * 0.15;
	}

	@Override
	protected void onEnable() {
		consumeMouseTurn();
		clearTarget();
	}

	@Override
	protected void onDisable() {
		clearTarget();
		consumeMouseTurn();
	}

	@Override
	public void onTick() {
		MouseTurn mouse = consumeMouseTurn();
		if (mc().player == null || mc().level == null || mc().gui.screen() != null
				|| mc().gui.overlay() != null || !isActivated() || !weaponAllowed()
				|| UnluckyClient.INSTANCE.modules.get(Freecam.class).isEnabled()
				|| UnluckyClient.INSTANCE.modules.get(Freelook.class).isActive()) {
			clearTarget();
			return;
		}

		long now = System.currentTimeMillis();
		target = selectTarget(now);
		if (target == null) {
			stopCorrection();
			return;
		}
		if (now >= nextAimRefreshMs) {
			chooseAimPoint(now);
		}

		Vec3 aim = aimPoint(target).add(target.getDeltaMovement().scale(prediction.getInt()));
		Vec3 eye = mc().player.getEyePosition();
		double dx = aim.x - eye.x;
		double dy = aim.y - eye.y;
		double dz = aim.z - eye.z;
		float wantedYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
		float wantedPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		double phase = now / 1000.0 + target.getId() * 0.73;
		wantedYaw += (float) (Math.sin(phase * 1.31) * microDrift.get());
		wantedPitch += (float) (Math.sin(phase * 0.83 + 1.7) * microDrift.get());

		float yawError = Mth.wrapDegrees(wantedYaw - mc().player.getYRot());
		float pitchError = wantedPitch - mc().player.getXRot();
		if (Math.hypot(yawError, pitchError) <= deadzone.get()) {
			stopCorrection();
			return;
		}

		float factor = strength.getFloat() / 100.0f;
		float wantedYawVelocity = Mth.clamp(yawError * factor,
				-maxYawSpeed.getFloat(), maxYawSpeed.getFloat());
		float wantedPitchVelocity = Mth.clamp(pitchError * factor,
				-maxPitchSpeed.getFloat(), maxPitchSpeed.getFloat());
		if (respectOppositeInput.get()) {
			if (opposes(mouse.yawDegrees(), yawError)) wantedYawVelocity = 0;
			if (opposes(mouse.pitchDegrees(), pitchError)) wantedPitchVelocity = 0;
		}

		float accel = acceleration.getFloat();
		yawVelocity += (wantedYawVelocity - yawVelocity) * accel;
		pitchVelocity += (wantedPitchVelocity - pitchVelocity) * accel;
		float yawStep = boundedStep(yawVelocity, yawError);
		float pitchStep = boundedStep(pitchVelocity, pitchError);
		RotationManager.assistVisible(yawStep, pitchStep);
	}

	private LivingEntity selectTarget(long now) {
		TargetingUtil.Filter filter = filter();
		if (target != null && TargetingUtil.matches(mc().player, target, filter)
				&& now - targetSinceMs < stickiness.getInt()) {
			return target;
		}
		LivingEntity selected = TargetingUtil.select(mc().player,
				mc().level.entitiesForRendering(), filter);
		if (selected != target) {
			target = selected;
			targetSinceMs = now;
			nextAimRefreshMs = 0;
		}
		return selected;
	}

	private TargetingUtil.Filter filter() {
		return new TargetingUtil.Filter()
				.groups(players.get(), hostiles.get(), passives.get())
				.ignoreFriends(ignoreFriends.get())
				.range(range.get())
				.fov(fov.get())
				.lineOfSight(lineOfSight.get())
				.priority(priority.is("Closest") ? TargetingUtil.Priority.CLOSEST
						: TargetingUtil.Priority.SMALLEST_ANGLE);
	}

	private void chooseAimPoint(long now) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		double spread = horizontalSpread.get() / 100.0;
		aimXFraction = random.nextDouble(-spread, Math.nextUp(spread));
		aimZFraction = random.nextDouble(-spread, Math.nextUp(spread));
		double low = Math.min(verticalMinimum.get(), verticalMaximum.get()) / 100.0;
		double high = Math.max(verticalMinimum.get(), verticalMaximum.get()) / 100.0;
		if (aimRegion.is("Torso")) {
			low = Math.max(low, 0.35);
			high = Math.min(high, 0.65);
		} else if (aimRegion.is("Upper body")) {
			low = Math.max(low, 0.55);
		}
		if (high <= low) high = Math.min(0.95, low + 0.01);
		aimYFraction = random.nextDouble(low, Math.nextUp(high));
		// Even with zero horizontal spread, never pin the exact AABB centre.
		if (Math.abs(aimXFraction) < 1.0e-6 && Math.abs(aimZFraction) < 1.0e-6
				&& Math.abs(aimYFraction - 0.5) < 1.0e-6) {
			aimYFraction = Math.min(0.95, aimYFraction + 0.01);
		}
		long min = Math.min(refreshMinimum.getInt(), refreshMaximum.getInt());
		long max = Math.max(refreshMinimum.getInt(), refreshMaximum.getInt());
		nextAimRefreshMs = now + (min == max ? min : random.nextLong(min, max + 1));
	}

	private Vec3 aimPoint(LivingEntity entity) {
		AABB box = entity.getBoundingBox();
		return new Vec3(box.getCenter().x + box.getXsize() * aimXFraction,
				box.minY + box.getYsize() * aimYFraction,
				box.getCenter().z + box.getZsize() * aimZFraction);
	}

	private boolean isActivated() {
		return switch (activation.get()) {
			case "Attack or use held" -> mc().options.keyAttack.isDown() || mc().options.keyUse.isDown();
			case "Always while weapon held" -> true;
			default -> mc().options.keyAttack.isDown();
		};
	}

	private boolean weaponAllowed() {
		if (!onlyWeapon.get()) return true;
		ItemStack stack = mc().player.getMainHandItem();
		Item item = stack.getItem();
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || item instanceof MaceItem
				|| item instanceof TridentItem || item instanceof BowItem
				|| item instanceof CrossbowItem;
	}

	private static boolean opposes(double mouseDegrees, float error) {
		return Math.abs(mouseDegrees) > 0.01 && Math.signum(mouseDegrees) != Math.signum(error);
	}

	private static float boundedStep(float velocity, float error) {
		if (Math.signum(velocity) != Math.signum(error)) return 0;
		return Math.copySign(Math.min(Math.abs(velocity), Math.abs(error)), error);
	}

	private void clearTarget() {
		target = null;
		targetSinceMs = 0;
		nextAimRefreshMs = 0;
		stopCorrection();
	}

	private void stopCorrection() {
		yawVelocity = 0;
		pitchVelocity = 0;
	}

	private static MouseTurn consumeMouseTurn() {
		MouseTurn result = new MouseTurn(mouseYaw, mousePitch);
		mouseYaw = 0;
		mousePitch = 0;
		return result;
	}

	private record MouseTurn(double yawDegrees, double pitchDegrees) {
	}
}
