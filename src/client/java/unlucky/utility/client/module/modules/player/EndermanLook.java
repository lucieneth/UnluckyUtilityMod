package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.RotationManager;

/**
 * Looks away before an enderman decides you were staring at it.
 *
 * <p>The trigger is reproduced from vanilla rather than approximated, because the cone is much
 * narrower than it feels and it <em>changes width with distance</em>. 26.2 computes it in
 * {@code LivingEntity.isLookingAtMe}, which {@code EnderMan} calls with a tolerance of 0.025,
 * distance scaling on, and the enderman's <b>eye</b> Y as the target:
 *
 * <pre>
 *   toward = (e.getX() - p.getX(), e.getEyeY() - p.getEyeY(), e.getZ() - p.getZ())
 *   staring = view · toward.normalize() &gt; 1 - 0.025 / toward.length()
 * </pre>
 *
 * That is a half-angle of about 6.4° at four blocks but only 2.3° at thirty-two, so a fixed
 * angular threshold would either deflect constantly up close or miss entirely at range. The
 * module evaluates the real expression per enderman, per tick.
 *
 * <p>Two details are taken straight from vanilla instead of being re-stated as settings. The
 * disguise check is {@code LivingEntity.PLAYER_NOT_WEARING_DISGUISE_ITEM}, so a carved pumpkin —
 * or whatever else 26.2 counts — disables the module for free and stays correct if that list
 * changes. And line of sight is required for aggro, so an enderman behind a wall is not a threat
 * and is never worth turning away from.
 *
 * <p>The turn is a {@link RotationManager} spoof by default, which is why it reads like Aura in
 * third person: the camera never moves, only the rotation on the wire and the rendered pose.
 * That is also the whole mechanism — the server decides aggro from the last rotation packet it
 * received, so spoofing that packet is precisely what prevents it.
 */
public class EndermanLook extends Module {
	/** EnderMan's own argument to {@code isLookingAtMe}. */
	private static final double TOLERANCE = 0.025;

	public final NumberSetting range = add(new NumberSetting("Range",
			"Only consider endermen within this distance", 32, 1, 64, 1));
	public final NumberSetting margin = add(new NumberSetting("Margin",
			"Extra degrees outside the real aggro cone at which to start turning away",
			2, 0, 30, 0.5));
	public final ModeSetting deflect = add(new ModeSetting("Deflect",
			"Down looks straight at the floor; Minimum turns only as far as it has to",
			"Down", "Down", "Minimum"));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"Silent turns only the server/model, like Aura; Visible also turns your camera",
			"Silent", "Silent", "Visible"));
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Degrees turned per tick. The server re-checks every tick, so anything below a snap "
					+ "can lose the race and let the aggro through", 180, 1, 180, 1));
	public final BooleanSetting ignoreProvoked = add(new BooleanSetting("Ignore provoked",
			"Do not bother hiding from an enderman that is already angry", true));
	public final BooleanSetting pauseAttacking = add(new BooleanSetting("Pause while attacking",
			"Stand down while the attack key is held, so you can actually fight one", true));

	private boolean deflecting;

	public EndermanLook() {
		super("EndermanLook", "Turns your server-visible look away from enderman eye contact",
				Category.PLAYER, ServerVisibility.CONDITIONAL);
	}

	/**
	 * Only observable while actually deflecting — the rest of the time this module sends
	 * nothing at all, which is exactly the reactive shape {@code CONDITIONAL} is for.
	 */
	@Override
	public boolean isServerObservableNow() {
		return deflecting;
	}

	@Override
	protected void onDisable() {
		deflecting = false;
	}

	@Override
	public void onTick() {
		deflecting = false;
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()
				|| (pauseAttacking.get() && mc().options.keyAttack.isDown())) {
			return;
		}
		// Vanilla's own disguise gate: wearing the right helmet means no enderman ever checks.
		if (!LivingEntity.PLAYER_NOT_WEARING_DISGUISE_ITEM.test(player)) {
			return;
		}

		float yaw = player.getYRot();
		float pitch = player.getXRot();
		float needed = requiredPitch(player, yaw, pitch);
		if (Float.isNaN(needed)) {
			return;
		}

		float speed = rotationSpeed.getFloat();
		float stepped = Mth.clamp(pitch + Mth.clamp(needed - pitch, -speed, speed), -90.0f, 90.0f);
		if (!RotationManager.rotateIfAllowed(yaw, stepped, RotationManager.PRIORITY_PLACEMENT)) {
			return; // a combat rotation owns the tick; do not fight it
		}
		deflecting = true;
		if (rotation.is("Visible")) {
			player.setXRot(stepped);
		}
	}

	/**
	 * The pitch that clears every threatening enderman, or NaN when none is threatening.
	 *
	 * <p>Minimum mode adds only the shortfall to the current pitch, which is exact when the
	 * enderman is roughly ahead — the case that matters, since we are nearly looking at it. It
	 * is then <em>verified</em> against the real expression, because a pitch-only turn cannot
	 * always escape a cone that is offset in yaw; when it fails, straight down always works.
	 */
	private float requiredPitch(LocalPlayer player, float yaw, float pitch) {
		Vec3 eye = player.getEyePosition();
		double worstShortfall = 0.0;

		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EnderMan enderman) || !enderman.isAlive()) {
				continue;
			}
			if (ignoreProvoked.get() && enderman.isCreepy()) {
				continue;
			}
			if (enderman.distanceToSqr(player) > range.get() * range.get()) {
				continue;
			}
			Vec3 toward = new Vec3(enderman.getX() - player.getX(),
					enderman.getEyeY() - eye.y, enderman.getZ() - player.getZ());
			double distance = toward.length();
			if (distance < 1.0e-6) {
				continue;
			}
			// Line of sight is part of the aggro condition, so an obstructed enderman is not a
			// threat. Checked last: it is the only expensive test here.
			if (!visible(player, eye, new Vec3(enderman.getX(), enderman.getEyeY(), enderman.getZ()))) {
				continue;
			}

			double separation = Math.toDegrees(Math.acos(Mth.clamp(
					view(yaw, pitch).dot(toward.normalize()), -1.0, 1.0)));
			double cone = Math.toDegrees(Math.acos(Mth.clamp(1.0 - TOLERANCE / distance, -1.0, 1.0)));
			double wanted = cone + margin.get();
			if (separation < wanted) {
				worstShortfall = Math.max(worstShortfall, wanted - separation);
			}
		}

		if (worstShortfall <= 0.0) {
			return Float.NaN;
		}
		if (deflect.is("Down")) {
			return 90.0f;
		}
		float minimum = Mth.clamp(pitch + (float) worstShortfall, -90.0f, 90.0f);
		return stillStaring(player, yaw, minimum) ? 90.0f : minimum;
	}

	/** Re-runs the exact vanilla test against a candidate rotation. */
	private boolean stillStaring(LocalPlayer player, float yaw, float pitch) {
		Vec3 view = view(yaw, pitch);
		Vec3 eye = player.getEyePosition();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EnderMan enderman) || !enderman.isAlive()) {
				continue;
			}
			if (ignoreProvoked.get() && enderman.isCreepy()) {
				continue;
			}
			if (enderman.distanceToSqr(player) > range.get() * range.get()) {
				continue;
			}
			Vec3 toward = new Vec3(enderman.getX() - player.getX(),
					enderman.getEyeY() - eye.y, enderman.getZ() - player.getZ());
			double distance = toward.length();
			if (distance > 1.0e-6
					&& view.dot(toward.normalize()) > 1.0 - TOLERANCE / distance) {
				return true;
			}
		}
		return false;
	}

	/** {@code Entity.getViewVector} for an arbitrary rotation, from the shared projectile math. */
	private static Vec3 view(float yaw, float pitch) {
		return ProjectilePathUtil.direction(pitch, yaw, 0.0f);
	}

	private boolean visible(LocalPlayer player, Vec3 eye, Vec3 target) {
		return mc().level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
	}
}
