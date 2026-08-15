package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Walk up blocks the way you already walk up a slab.
 *
 * <p><b>This raises vanilla's step height and nothing else.</b> {@code maxUpStep()} is the number
 * vanilla's own collision code consults when it decides whether a horizontal move can be retried
 * one step higher; handing back a larger one means the same collision-aware movement runs, over a
 * taller obstacle. That is the entire module. There is deliberately no packet mode, no teleport
 * chain and no timer: those do not step, they relocate you and hope, and this client does not ship
 * that.
 *
 * <p><b>Nothing persists.</b> The value is intercepted on the way out of a getter, so there is no
 * attribute modifier to leak, nothing to remove on disable, and no state left on an entity you
 * dismounted three worlds ago. The only thing {@link #onDisable} has to clear is this module's own
 * cooldown.
 *
 * <p><b>The height is still a request.</b> Vanilla will not step into a ceiling, through a wall or
 * onto a block that is not there, whatever number it is given — a 2.5 setting in a one-block
 * corridor simply does nothing, which is the correct outcome and worth knowing before filing it as
 * a bug.
 */
public class Step extends Module {
	public final NumberSetting height = add(new NumberSetting("Height",
			"Tallest obstacle vanilla will step you up", 1.0, 0.6, 2.5, 0.1));
	public final ModeSetting activeWhen = add(new ModeSetting("Active when",
			"Restrict stepping to a sneak state", "Always", "Always", "Sneaking", "Not sneaking"));

	public final BooleanSetting safeHealth = add(new BooleanSetting("Safe health",
			"Stop stepping when you are low — a step is a movement the server did not expect, "
					+ "and the moment to stop being interesting is before you die of it", true));
	public final NumberSetting healthThreshold = add(new NumberSetting("Health threshold",
			"Health, absorption included, below which stepping stops", 8, 1, 36, 1), safeHealth::get);

	public final BooleanSetting pauseInLiquids = add(new BooleanSetting("Pause in liquids",
			"Restore vanilla height while swimming", true));
	public final BooleanSetting pauseWhileGliding = add(new BooleanSetting("Pause while gliding",
			"Restore vanilla height while an elytra is out", true));
	public final BooleanSetting vehicles = add(new BooleanSetting("Vehicles",
			"Also raise the step height of whatever you are riding", false));
	public final NumberSetting resetDelay = add(new NumberSetting("Reset delay",
			"Ticks of vanilla height after a successful step", 0, 0, 20, 1));

	/** Ticks left of the post-step cooldown. */
	private int cooldown;
	/** Feet height on the previous tick, for noticing that a step actually happened. */
	private double lastY;
	private boolean lastOnGround;

	public Step() {
		super("Step", "Walk up taller blocks without jumping", Category.MOVEMENT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		cooldown = 0;
		lastOnGround = false;
		lastY = mc().player == null ? 0.0 : mc().player.getY();
	}

	@Override
	protected void onDisable() {
		cooldown = 0;
		lastOnGround = false;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) {
			onDisable();
			return;
		}
		if (cooldown > 0) {
			cooldown--;
		}
		// A step is the one movement that gains height without leaving the ground, which makes it
		// distinguishable from a jump with no hook of its own.
		if (resetDelay.getInt() > 0 && player.onGround() && lastOnGround
				&& player.getY() - lastY > 0.5) {
			cooldown = resetDelay.getInt();
		}
		lastY = player.getY();
		lastOnGround = player.onGround();
	}

	/**
	 * The height {@code maxUpStep()} should answer for {@code entity}, from the two mixins that
	 * own that method.
	 *
	 * <p>Only ever raises. Handing back something lower than vanilla would silently break the
	 * slab-and-stair walking every player already relies on, and no setting here asks for that.
	 */
	public float stepHeight(Entity entity, float vanilla) {
		if (!isEnabled() || entity == null || !applies(entity)) {
			return vanilla;
		}
		return Math.max(vanilla, height.getFloat());
	}

	/** Whether this entity is the one we are allowed to raise right now. */
	private boolean applies(Entity entity) {
		LocalPlayer player = mc().player;
		if (player == null || cooldown > 0) {
			return false;
		}
		Entity vehicle = player.getVehicle();
		if (vehicle != null) {
			// Riding: the vehicle resolves the collision, so raising the passenger's own step
			// height would change nothing and pretending otherwise hides the Vehicles switch.
			if (!vehicles.get() || entity != vehicle) {
				return false;
			}
		} else if (entity != player) {
			return false;
		}
		if (!sneakStateAllows(player)) {
			return false;
		}
		if (safeHealth.get()
				&& player.getHealth() + player.getAbsorptionAmount() < healthThreshold.get()) {
			return false;
		}
		if (pauseInLiquids.get() && (player.isInWater() || player.isInLava())) {
			return false;
		}
		return !pauseWhileGliding.get() || !player.isFallFlying();
	}

	private boolean sneakStateAllows(LocalPlayer player) {
		return switch (activeWhen.get()) {
			case "Sneaking" -> player.isShiftKeyDown();
			case "Not sneaking" -> !player.isShiftKeyDown();
			default -> true;
		};
	}
}
