package unlucky.utility.client.module.modules.misc;

import java.util.Random;

import net.minecraft.util.Mth;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.RotationManager;

/**
 * CS:GO-style spinbot. Purely for show: it spins the rotation sent to the
 * server (so other players and your own third-person / freecam model whirl
 * around) while your first-person camera stays perfectly still — the same
 * detached-rotation trick Aura uses. The head and body can spin independently
 * for that classic desynced look. It doesn't aim or hit anything.
 *
 * <p>Because it doesn't aim, it takes the head at {@code PRIORITY_COSMETIC} and
 * loses it to anything that does — Aura mid-swing keeps its own aim and the spin
 * simply pauses for those ticks. The body keeps spinning either way; that's local
 * render flair and costs nobody a hit.
 */
public class Spinbot extends Module {
	// head / sent rotation — this is what the server and other players see
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Spin = whirl, Jitter = snap around, Sway = swing, Static = fixed offset",
			"Spin", "Spin", "Jitter", "Sway", "Static"));
	public final NumberSetting speed = add(new NumberSetting("Speed", "Spin degrees per tick", 25, 1, 60, 1));
	public final ModeSetting direction = add(new ModeSetting("Direction", "Which way to spin", "Right", "Right", "Left"));
	public final NumberSetting sway = add(new NumberSetting("Sway range", "Swing amplitude in Sway mode", 90, 5, 180, 5));
	public final NumberSetting offset = add(new NumberSetting("Static offset", "Fixed yaw offset in Static mode", 45, 0, 180, 5));
	public final NumberSetting jitter = add(new NumberSetting("Jitter range", "Random snap range in Jitter mode", 120, 5, 180, 5));

	// body / torso rotation — local third-person flair, desynced from the head
	public final ModeSetting bodyMode = add(new ModeSetting("Body",
			"Sync = follow head, Spin = own spin, Opposite = mirror head, Static = fixed offset",
			"Sync", "Sync", "Spin", "Opposite", "Static"));
	public final NumberSetting bodySpeed = add(new NumberSetting("Body speed", "Body spin degrees per tick", 40, 1, 60, 1));
	public final NumberSetting bodyOffset = add(new NumberSetting("Body offset", "Fixed body yaw offset", 90, 0, 180, 5));

	// pitch — head tilt others see (your camera is untouched)
	public final ModeSetting pitchMode = add(new ModeSetting("Pitch",
			"Camera = keep yours, Up/Down = extremes, Static = fixed, Nod = bob",
			"Camera", "Camera", "Up", "Down", "Static", "Nod"));
	public final NumberSetting pitchAngle = add(new NumberSetting("Pitch angle", "Fixed pitch in Static mode", 0, -90, 90, 5));

	// a hidden ghost silhouette of your real facing, under all the spin
	public final BooleanSetting realOutline = add(new BooleanSetting("Real outline",
			"Draw a see-through chams silhouette at your true facing (third person / freecam)", false));
	public final ColorSetting outlineColor = add(new ColorSetting("Outline color", "Color of the real-player ghost", 0xA040C4FF));

	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUIs", "Stop spinning while a screen is open", true));

	/** ARGB for the real-player ghost outline, or 0 when disabled. */
	public int outlineArgb() {
		return isEnabled() && realOutline.get() ? outlineColor.get() : 0;
	}

	private final Random rng = new Random();
	private float headYaw;
	private float bodyYaw;

	public Spinbot() {
		super("Spinbot", "Visual-only rotation spin, CS:GO style", Category.MISC);
	}

	@Override
	protected void onEnable() {
		// start from where you're looking so there's no jump on activation
		if (mc().player != null) {
			headYaw = mc().player.getYRot();
			bodyYaw = mc().player.getYRot();
		}
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return;
		}

		float camYaw = mc().player.getYRot();
		int dir = direction.is("Left") ? -1 : 1;
		float time = (System.currentTimeMillis() % 1_000_000L) / 1000.0f;

		// head / sent yaw
		headYaw = switch (mode.get()) {
			case "Jitter" -> camYaw + (rng.nextFloat() * 2.0f - 1.0f) * jitter.getFloat();
			case "Sway" -> camYaw + Mth.sin(time * speed.getFloat() * 0.15f) * sway.getFloat();
			case "Static" -> camYaw + offset.getFloat() * dir;
			default -> wrap(headYaw + speed.getFloat() * dir); // Spin
		};

		float pitch = switch (pitchMode.get()) {
			case "Up" -> -90.0f;
			case "Down" -> 90.0f;
			case "Static" -> pitchAngle.getFloat();
			case "Nod" -> Mth.sin(time * 3.0f) * 90.0f;
			default -> mc().player.getXRot(); // Camera
		};

		// cosmetic: if Aura (or Nuker, or anything else that has to actually hit what
		// it's pointed at) already claimed the head this tick, leave it alone — a spin
		// through their aim would just make them miss
		RotationManager.rotate(headYaw, pitch, RotationManager.PRIORITY_COSMETIC);

		// body / torso yaw (local visual desync)
		bodyYaw = switch (bodyMode.get()) {
			case "Spin" -> wrap(bodyYaw + bodySpeed.getFloat() * dir);
			case "Opposite" -> camYaw - (headYaw - camYaw);
			case "Static" -> camYaw + bodyOffset.getFloat() * dir;
			default -> headYaw; // Sync
		};
		RotationManager.setBodyYaw(bodyYaw, RotationManager.PRIORITY_COSMETIC);
	}

	/** Keeps the accumulated spin in [-180, 180) so it never overflows. */
	private static float wrap(float yaw) {
		return Mth.wrapDegrees(yaw);
	}
}
