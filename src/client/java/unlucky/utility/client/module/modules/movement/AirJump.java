package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MovementActionCoordinator;

/** Manual repeated air jump or a lightweight maintained activation altitude. */
public class AirJump extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Air-jump behavior", "Manual", "Manual", "Maintain level"));
	public final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Ticks between manual jumps", 0, 0, 40, 1));
	public final NumberSetting tolerance = add(new NumberSetting("Maintain tolerance", "Allowed drop below activation height", 0.10, 0, 1, 0.05), () -> mode.is("Maintain level"));
	public final BooleanSetting sneakLowers = add(new BooleanSetting("Sneak lowers maintained level", "Move the held altitude down while sneaking", true), () -> mode.is("Maintain level"));
	public final BooleanSetting ignoreElytra = add(new BooleanSetting("Ignore while elytra flying", "Do not interfere with gliding", true));
	public final BooleanSetting ignoreFly = add(new BooleanSetting("Ignore while another Fly owns movement", "Yield to enabled flight modules", true));
	public final BooleanSetting liquids = add(new BooleanSetting("In liquids", "Allow air jump in water/lava", false));
	public final NumberSetting maxJumps = add(new NumberSetting("Max air jumps", "Manual jumps allowed before touching ground; 0 is unlimited", 0, 0, 20, 1), () -> mode.is("Manual"));
	private boolean jumpWasDown;
	private int cooldownTicks;
	private int airJumps;
	private double heldY;

	public AirJump() {
		super("AirJump", "Jumps again in air or maintains an activation level", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override protected void onEnable() { heldY = mc().player == null ? 0 : mc().player.getY(); jumpWasDown = false; airJumps = 0; }

	@Override public void onTick() {
		if (mc().player == null) return;
		boolean jump = mc().options.keyJump.isDown();
		if (cooldownTicks > 0) cooldownTicks--;
		if (blocked()) { jumpWasDown = jump; return; }
		if (mode.is("Manual")) {
			if (mc().player.onGround()) airJumps = 0;
			if (jump && !jumpWasDown && !mc().player.onGround() && cooldownTicks == 0
					&& (maxJumps.getInt() == 0 || airJumps < maxJumps.getInt())) {
				mc().player.jumpFromGround();
				cooldownTicks = cooldown.getInt();
				airJumps++;
			}
		} else {
			if (sneakLowers.get() && mc().player.isShiftKeyDown()) heldY = Math.min(heldY, mc().player.getY());
			if (mc().player.getY() < heldY - tolerance.get()) {
				MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
						v -> new net.minecraft.world.phys.Vec3(v.x, Math.max(v.y, 0.42), v.z));
			}
		}
		jumpWasDown = jump;
	}

	private boolean blocked() {
		if (ignoreElytra.get() && mc().player.isFallFlying()) return true;
		if (!liquids.get() && (mc().player.isInWater() || mc().player.isInLava())) return true;
		return ignoreFly.get() && (UnluckyClient.INSTANCE.modules.get(ElytraFly.class).isEnabled()
				|| UnluckyClient.INSTANCE.modules.get(EventlessFly.class).isEnabled()
				|| UnluckyClient.INSTANCE.modules.get(FakeFly.class).isEnabled()
				|| UnluckyClient.INSTANCE.modules.get(CreativeFlight.class).isEnabled());
	}
}
