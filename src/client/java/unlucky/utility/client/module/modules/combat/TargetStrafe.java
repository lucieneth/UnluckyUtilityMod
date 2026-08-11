package unlucky.utility.client.module.modules.combat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Locks your movement onto a circle around the nearest target: you orbit them in
 * the chosen direction. By default the trigger is holding W; with <b>On hold</b>
 * it's a dedicated key instead, and while that key is up the module just shows
 * who the target <em>would</em> be (draws the circle) without moving you.
 * Jumping is untouched, so BunnyHop works mid-orbit.
 */
public class TargetStrafe extends Module {
	// per-mob whitelists, opened by right-clicking the group toggles in the GUI
	public final unlucky.utility.client.settings.EntityListSetting hostileMobs =
			new unlucky.utility.client.settings.EntityListSetting("Hostile mobs", "Which hostile mobs to orbit");
	public final unlucky.utility.client.settings.EntityListSetting passiveMobs =
			new unlucky.utility.client.settings.EntityListSetting("Passive mobs", "Which passive mobs to orbit");

	public final NumberSetting range = add(new NumberSetting("Range", "How far away targets are picked up", 6.0, 2.0, 12.0, 0.5));
	public final BooleanSetting players = add(new BooleanSetting("Players", "Orbit players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles", "Orbit hostile mobs — right-click to pick which", true)
			.withMobList(hostileMobs, true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives", "Orbit passive mobs — right-click to pick which", false)
			.withMobList(passiveMobs, false));
	public final BooleanSetting onHold = add(new BooleanSetting("On hold", "Orbit only while the hold key is down, instead of while holding W", false));
	public final KeybindSetting holdKey = add(new KeybindSetting("Hold key", "The key that triggers orbiting", GLFW.GLFW_KEY_LEFT_ALT),
			onHold::get);
	public final NumberSetting circleSize = add(new NumberSetting("Circle size", "Orbit radius in blocks", 3.0, 1.0, 6.0, 0.25));
	public final ModeSetting targeting = add(new ModeSetting("Targeting", "How the first target is picked", "Closest", "Closest", "Health", "Crosshair"));
	public final ModeSetting fallback = add(new ModeSetting("Fallback", "When the target dies or leaves: grab the next one, or release until you re-press W", "Next target", "Next target", "Release"));
	public final ModeSetting direction = add(new ModeSetting("Direction", "Orbit direction", "Clockwise", "Clockwise", "Counter", "Auto"));
	public final BooleanSetting reverseOnCollision = add(new BooleanSetting("Reverse on collision",
			"In Auto direction, reverse when the orbit path hits an obstacle", true), () -> direction.is("Auto"));
	public final BooleanSetting sticky = add(new BooleanSetting("Sticky", "Aggressively glue to the circle — chases it through knockback", false));
	public final BooleanSetting showCircle = add(new BooleanSetting("Show circle", "Draw the orbit circle on the ground", true));

	private LivingEntity current;
	private boolean waitRepress; // Release fallback: no new target until W is re-pressed
	private boolean autoClockwise = true;
	private int directionCooldown;

	public TargetStrafe() {
		super("TargetStrafe", "Orbit the closest target while holding W", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
		add(hostileMobs);
		add(passiveMobs);
	}

	@Override
	protected void onDisable() {
		current = null;
		waitRepress = false;
		directionCooldown = 0;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		// what actually triggers the orbit: the hold key in On-hold mode, else W
		boolean engaged = mc().gui.screen() == null && (onHold.get()
				? holdKey.isBound() && InputConstants.isKeyDown(mc().getWindow(), holdKey.get())
				: mc().options.keyUp.isDown());
		if (!engaged) {
			waitRepress = false; // releasing the trigger re-arms target acquisition
		}

		// drop targets that died, left pickup range, or no longer match filters
		if (current != null && !TargetingUtil.matches(mc().player, current,
				targetFilter(range.get() * 1.5))) {
			current = null;
			if (fallback.is("Release")) {
				waitRepress = engaged;
			}
		}
		if (current == null && !waitRepress) {
			current = pickTarget();
		}
		if (current == null) {
			return;
		}
		if (direction.is("Auto") && reverseOnCollision.get()) {
			if (directionCooldown > 0) {
				directionCooldown--;
			} else if (mc().player.horizontalCollision) {
				autoClockwise = !autoClockwise;
				directionCooldown = 10;
			}
		}

		if (showCircle.get()) {
			drawCircle(current.position());
		}
		if (engaged) {
			strafe(current.position());
		}
	}

	/** Steers along the circle: tangential motion plus a pull back onto the radius. */
	private void strafe(Vec3 target) {
		Vec3 pos = mc().player.position();
		double dx = pos.x - target.x;
		double dz = pos.z - target.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 0.05) {
			return;
		}
		double rx = dx / dist; // radial, pointing away from the target
		double rz = dz / dist;
		double dir = clockwise() ? 1.0 : -1.0;

		// keep the current horizontal speed (preserves BunnyHop gains), floor at walk pace
		Vec3 vel = mc().player.getDeltaMovement();
		double speed = Math.max(Math.hypot(vel.x, vel.z), mc().player.isSprinting() ? 0.26 : 0.2);

		if (sticky.get()) {
			// chase the next point ON the circle directly: overwrites knockback
			// every tick and catches up at up to 10 b/s until back on the ring
			double r = circleSize.get();
			double angle = Math.atan2(dz, dx) + dir * (speed / Math.max(r, 0.5));
			double ndx = target.x + Math.cos(angle) * r - pos.x;
			double ndz = target.z + Math.sin(angle) * r - pos.z;
			double need = Math.sqrt(ndx * ndx + ndz * ndz);
			if (need > 1.0e-4) {
				double chase = Math.min(need, Math.max(speed, 0.5));
				mc().player.setDeltaMovement(ndx / need * chase, vel.y, ndz / need * chase);
			}
			return;
		}

		double tx = -rz * dir; // tangent
		double tz = rx * dir;
		// too far -> pull inward, too close -> push outward (clamped)
		double pull = Math.clamp((dist - circleSize.get()) * 0.35, -1.0, 1.0);
		double mx = tx - rx * pull;
		double mz = tz - rz * pull;
		double len = Math.sqrt(mx * mx + mz * mz);
		mc().player.setDeltaMovement(mx / len * speed, vel.y, mz / len * speed);
	}

	private boolean clockwise() {
		return direction.is("Clockwise") || direction.is("Auto") && autoClockwise;
	}

	private void drawCircle(Vec3 target) {
		double r = circleSize.get();
		double y = target.y + 0.05;
		Vec3 prev = null;
		for (int i = 0; i <= 48; i++) {
			double a = i * Math.PI * 2.0 / 48.0;
			Vec3 point = new Vec3(target.x + Math.cos(a) * r, y, target.z + Math.sin(a) * r);
			if (prev != null) {
				Render3D.line(prev, point, Theme.accent1, 2.0f, true);
			}
			prev = point;
		}
	}

	private LivingEntity pickTarget() {
		return TargetingUtil.select(mc().player, mc().level.entitiesForRendering(),
				targetFilter(range.get()));
	}

	private TargetingUtil.Filter targetFilter(double pickupRange) {
		TargetingUtil.Priority order = switch (targeting.get()) {
			case "Health" -> TargetingUtil.Priority.LOWEST_HEALTH;
			case "Crosshair" -> TargetingUtil.Priority.SMALLEST_ANGLE;
			default -> TargetingUtil.Priority.CLOSEST;
		};
		return new TargetingUtil.Filter()
				.groups(players.get(), hostiles.get(), passives.get())
				.typeLists(hostileMobs, passiveMobs)
				.range(pickupRange)
				.priority(order);
	}
}
