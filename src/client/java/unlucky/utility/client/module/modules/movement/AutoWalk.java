package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.ElytraSwap;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.EquipmentScorer;
import unlucky.utility.client.util.InputActionCoordinator;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.RotationManager;

/**
 * Holds a direction and stops before something goes wrong.
 *
 * <p><b>It is not a pathfinder and must not become one.</b> No goal, no follow, no highway, no
 * mining, no bridging. The value of a module this simple is that its behaviour fits in a sentence:
 * it holds a key, and it lets go when one of a short list of things is true. Every "just add
 * routing around obstacles" is the first step to a Baritone this client has decided not to ship,
 * and the moment it exists nobody can say what the module will do next.
 *
 * <p><b>Stopping means disabling, not pausing.</b> A travel module that silently resumes after a
 * hazard is a module that walks into the hazard again as soon as the hazard moves. The player
 * turned it on; they get to turn it back on.
 *
 * <p><b>The checks look one step ahead, never at where you already are.</b> Noticing the lava you
 * are standing in is not a safety feature. Every test here is against the block the next step
 * lands on, which is the only place a stop can still help.
 */
public class AutoWalk extends Module {
	/** How far ahead the hazard and edge probes look, in blocks. */
	private static final double PROBE = 1.0;

	public final ModeSetting direction = add(new ModeSetting("Direction",
			"Which way to hold", "Forward", "Forward", "Backward", "Left", "Right"));
	public final BooleanSetting lockHeading = add(new BooleanSetting("Lock heading",
			"Keep the yaw you started with. A visible rotation request, so anything functional "
					+ "— aiming, placing — takes the head back when it needs it.", false));
	public final BooleanSetting autoSprint = add(new BooleanSetting("Auto sprint",
			"Hold sprint while walking forward", true));
	public final BooleanSetting autoJump = add(new BooleanSetting("Auto jump",
			"Jump at a wall when there is somewhere safe to land", false));

	public final BooleanSetting avoidHazards = add(new BooleanSetting("Avoid hazards",
			"Stop before lava, fire, cactus, powder snow and the rest", true));
	public final BooleanSetting avoidUnloaded = add(new BooleanSetting("Avoid unloaded chunks",
			"Stop at the edge of what the client has been told about", true));
	public final BooleanSetting stopOnInput = add(new BooleanSetting("Stop on user input",
			"Disable when you take the controls back", true));
	public final BooleanSetting stopOnYChange = add(new BooleanSetting("Stop on Y change",
			"Disable after any vertical displacement", false));
	public final BooleanSetting stopOnCollision = add(new BooleanSetting("Stop on collision",
			"Disable at a wall instead of jumping", false));

	public final BooleanSetting stopOnLowFood = add(new BooleanSetting("Stop on low food",
			"Disable below the food threshold", true));
	public final NumberSetting foodThreshold = add(new NumberSetting("Food threshold",
			"Hunger level to stop at", 6, 0, 20, 1), stopOnLowFood::get);
	public final BooleanSetting stopOnLowHealth = add(new BooleanSetting("Stop on low health",
			"Disable below the health threshold", true));
	public final NumberSetting healthThreshold = add(new NumberSetting("Health threshold",
			"Health, absorption included, to stop at", 6, 1, 36, 1), stopOnLowHealth::get);
	public final BooleanSetting stopOnElytraWarning = add(new BooleanSetting("Stop on elytra warning",
			"Disable when the elytra you are wearing reaches ElytraSwap's replace threshold", true));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUI",
			"Release the keys while a screen is open, without disabling", true));

	/** Yaw at activation, for Lock heading. */
	private float lockedYaw;
	/** Y at activation, for Stop on Y change. */
	private double startY;

	public AutoWalk() {
		super("AutoWalk", "Holds a direction with simple safety stops", Category.MOVEMENT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		lockedYaw = player == null ? 0.0f : player.getYRot();
		startY = player == null ? 0.0 : player.getY();
	}

	@Override
	protected void onDisable() {
		InputActionCoordinator.release(this);
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			onDisable();
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			InputActionCoordinator.release(this);
			return;
		}
		String reason = stopReason(player);
		if (reason != null) {
			stop(reason);
			return;
		}
		if (lockHeading.get()) {
			// Visible and cosmetic: a travel heading is the least important thing anyone could
			// want the head for, and it must yield the moment a placement or an aim needs it.
			RotationManager.rotateIfAllowed(lockedYaw, player.getXRot(),
					RotationManager.PRIORITY_COSMETIC);
		}

		InputActionCoordinator.Key key = movementKey();
		InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_TRAVEL, key);
		if (autoSprint.get() && direction.is("Forward")) {
			InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_TRAVEL,
					InputActionCoordinator.Key.SPRINT);
		}
		if (autoJump.get() && player.horizontalCollision && player.onGround()
				&& landingAhead(player)) {
			InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_TRAVEL,
					InputActionCoordinator.Key.JUMP);
		}
	}

	/**
	 * The first thing that says stop, or null.
	 *
	 * <p>Ordered cheapest first, and the string is the message: naming the reason is most of the
	 * value here, because "AutoWalk turned itself off" with no explanation is indistinguishable
	 * from a bug.
	 */
	private String stopReason(LocalPlayer player) {
		if (stopOnInput.get() && userTookOver()) {
			return "you took the controls back";
		}
		if (stopOnLowHealth.get()
				&& player.getHealth() + player.getAbsorptionAmount() < healthThreshold.get()) {
			return "health is low";
		}
		if (stopOnLowFood.get() && player.getFoodData().getFoodLevel() < foodThreshold.getInt()) {
			return "food is low";
		}
		if (stopOnYChange.get() && Math.abs(player.getY() - startY) > 0.5) {
			return "you changed height";
		}
		if (stopOnCollision.get() && player.horizontalCollision) {
			return "something is in the way";
		}
		if (stopOnElytraWarning.get() && elytraLow(player)) {
			return "the elytra you are wearing is nearly broken";
		}
		String ground = groundProblem(player);
		return ground;
	}

	/**
	 * Whether the human is pressing something that conflicts.
	 *
	 * <p>Only the movement keys, and only ones we are not holding ourselves — asking the hardware
	 * rather than the mapping is what makes that distinction possible at all, since our own hold
	 * makes {@code isDown()} answer for us.
	 */
	private boolean userTookOver() {
		for (InputActionCoordinator.Key key : new InputActionCoordinator.Key[]{
				InputActionCoordinator.Key.FORWARD, InputActionCoordinator.Key.BACK,
				InputActionCoordinator.Key.LEFT, InputActionCoordinator.Key.RIGHT,
				InputActionCoordinator.Key.JUMP}) {
			if (InputActionCoordinator.physicallyDown(key)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What is wrong with the ground one step ahead, or null.
	 *
	 * <p>An unloaded chunk counts as a problem in its own right, before the hazard and support
	 * tests: the answers those give about terrain nobody has sent us are not "safe", they are
	 * "unknown", and walking on into it is how you end up somewhere the server has to teleport you
	 * back from.
	 */
	private String groundProblem(LocalPlayer player) {
		Vec3 heading = headingVector(player);
		if (heading.lengthSqr() < 1.0e-6) {
			return null;
		}
		AABB box = player.getBoundingBox();
		double x = player.getX() + heading.x * PROBE;
		double z = player.getZ() + heading.z * PROBE;
		BlockPos feet = BlockPos.containing(x, box.minY + 0.1, z);
		BlockPos below = feet.below();

		if (avoidUnloaded.get() && (!mc().level.isLoaded(feet) || !mc().level.isLoaded(below))) {
			return "the chunk ahead is not loaded";
		}
		if (avoidHazards.get()) {
			if (MoveUtil.hazardous(feet) || MoveUtil.hazardous(below)) {
				return "there is something dangerous ahead";
			}
			// A drop is a hazard too, and the one that kills most often. Ignored while the player
			// is already airborne, where the answer is about the fall they are in, not a choice.
			if (player.onGround() && !MoveUtil.solidSupport(below)) {
				double drop = unlucky.utility.client.util.DamageForecast.distanceToGround(player,
						box.move(heading.x * PROBE, 0.0, heading.z * PROBE));
				if (drop < 0.0 || drop > 3.0) {
					return "there is a drop ahead";
				}
			}
		}
		return null;
	}

	/** Whether a jump clears whatever is in front and lands somewhere real. */
	private boolean landingAhead(LocalPlayer player) {
		Vec3 heading = headingVector(player);
		AABB raised = player.getBoundingBox().move(heading.x * PROBE, 1.0, heading.z * PROBE);
		if (!mc().level.noCollision(player, raised)) {
			return false;
		}
		BlockPos landing = BlockPos.containing(player.getX() + heading.x * PROBE,
				raised.minY - 0.5, player.getZ() + heading.z * PROBE);
		return MoveUtil.solidSupport(landing) && !MoveUtil.hazardous(landing);
	}

	private boolean elytraLow(LocalPlayer player) {
		var chest = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
		if (!chest.is(net.minecraft.world.item.Items.ELYTRA) || !chest.isDamageableItem()) {
			return false;
		}
		// Read from ElytraSwap so the two agree about what "nearly broken" means; a second
		// threshold here would let the walk continue past the point the swap module gave up.
		ElytraSwap swap = UnluckyClient.INSTANCE.modules.get(ElytraSwap.class);
		int remaining = chest.getMaxDamage() - chest.getDamageValue();
		return remaining <= swap.replaceThreshold.getInt()
				|| EquipmentScorer.durabilityPercent(chest) <= 1.0f;
	}

	private InputActionCoordinator.Key movementKey() {
		return switch (direction.get()) {
			case "Backward" -> InputActionCoordinator.Key.BACK;
			case "Left" -> InputActionCoordinator.Key.LEFT;
			case "Right" -> InputActionCoordinator.Key.RIGHT;
			default -> InputActionCoordinator.Key.FORWARD;
		};
	}

	/** Unit vector the chosen direction points along, relative to the player's yaw. */
	private Vec3 headingVector(LocalPlayer player) {
		float radians = (float) Math.toRadians(lockHeading.get() ? lockedYaw : player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
		Vec3 left = new Vec3(Math.cos(radians), 0.0, Math.sin(radians));
		return switch (direction.get()) {
			case "Backward" -> forward.scale(-1.0);
			case "Left" -> left;
			case "Right" -> left.scale(-1.0);
			default -> forward;
		};
	}

	private void stop(String reason) {
		InputActionCoordinator.release(this);
		ChatUtil.info("§eAutoWalk stopped: " + reason + ".");
		setEnabled(false);
	}
}
