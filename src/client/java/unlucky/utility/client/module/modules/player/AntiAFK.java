package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.GroupSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringListSetting;
import unlucky.utility.client.util.DamageForecast;
import unlucky.utility.client.util.InputActionCoordinator;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.MoveUtil;

/**
 * Looks busy enough not to be kicked, and nothing more.
 *
 * <p><b>Every action is reversible and bounded.</b> That is the whole design brief. An AFK filler
 * that wanders is a filler that walks into lava while you are making tea, so movement is a leash
 * around the spot you stopped at and every step is checked against the ground it lands on. The
 * failure this exists to avoid is not "got kicked" — it is "came back to a dead player and no idea
 * why".
 *
 * <p><b>Real input always wins, in the same tick.</b> Keys are held through
 * {@link InputActionCoordinator}, which polls the hardware rather than the mapping, so the moment
 * a hand touches the keyboard the synthetic hold is released and control is handed back rather
 * than taken away. There is no notice period and no "finish the current action" — sitting down at
 * a client that keeps walking for half a second is the behaviour nobody forgives.
 *
 * <p><b>No command execution, deliberately.</b> Chat messages are opt-in and rate-limited; running
 * commands on a timer is how an AFK filler becomes something that does things you did not read.
 */
public class AntiAFK extends Module {
	/** How long a synthetic key is held for one action, in ticks. */
	private static final int HOLD_TICKS = 8;

	/** Tolerance on the return trip; closer than this counts as home. */
	private static final double HOME_TOLERANCE = 0.6;

	public final ModeSetting activation = add(new ModeSetting("Activation",
			"Always, or only once you have actually stopped touching anything",
			"After idle", "Always", "After idle"));
	public final NumberSetting idleTime = add(new NumberSetting("Idle time",
			"Minutes without keyboard or mouse input before it starts", 5, 1, 120, 1),
			() -> activation.is("After idle"));

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Random picks from the enabled actions; Custom does exactly what you tick",
			"Random", "Random", "Custom"));
	public final NumberSetting minimumInterval = add(new NumberSetting("Minimum interval",
			"Shortest gap between actions, in seconds", 5, 1, 300, 1));
	public final NumberSetting maximumInterval = add(new NumberSetting("Maximum interval",
			"Longest gap between actions, in seconds", 15, 1, 600, 1));

	public final GroupSetting actions = add(new GroupSetting("Actions",
			"What it is allowed to do"));
	public final BooleanSetting swing = add(new BooleanSetting("Swing",
			"Swing your arm", true), actions::isExpanded);
	public final BooleanSetting yaw = add(new BooleanSetting("Yaw",
			"Turn on the spot", true), actions::isExpanded);
	public final BooleanSetting pitch = add(new BooleanSetting("Pitch",
			"Look up and down", false), actions::isExpanded);
	public final BooleanSetting jump = add(new BooleanSetting("Jump",
			"Jump in place", true), actions::isExpanded);
	public final BooleanSetting sneak = add(new BooleanSetting("Sneak",
			"Crouch briefly", false), actions::isExpanded);
	public final BooleanSetting strafe = add(new BooleanSetting("Strafe",
			"Step sideways within the movement radius", false), actions::isExpanded);
	public final BooleanSetting changeSlot = add(new BooleanSetting("Change slot",
			"Switch hotbar slot and switch back", false), actions::isExpanded);

	public final NumberSetting rotationAmount = add(new NumberSetting("Rotation amount",
			"Largest turn one action may make, in degrees", 30, 1, 180, 1));
	public final NumberSetting movementRadius = add(new NumberSetting("Movement radius",
			"How far it may drift from where you stopped", 2, 0, 16, 1));
	public final BooleanSetting returnToOrigin = add(new BooleanSetting("Return to origin",
			"Walk back toward the starting spot. Straight-line only — this is not a pathfinder.",
			true));
	public final BooleanSetting safeMovement = add(new BooleanSetting("Safe movement",
			"Refuse any step onto ground that is not loaded, solid and harmless", true));

	public final BooleanSetting messages = add(new BooleanSetting("Messages",
			"Send the occasional chat message", false));
	public final StringListSetting messageList = add(new StringListSetting("Message list",
			"One is picked at random"), messages::get);
	public final NumberSetting messageInterval = add(new NumberSetting("Message interval",
			"Minimum minutes between messages", 15, 1, 120, 1), messages::get);

	public final BooleanSetting stopOnInput = add(new BooleanSetting("Stop on user input",
			"Release everything the instant you touch a key", true));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUI",
			"Do nothing while a screen is open", true));
	public final BooleanSetting pauseInCombat = add(new BooleanSetting("Pause in combat",
			"Stand down when you have recently been hurt", true));
	public final BooleanSetting pauseInDanger = add(new BooleanSetting("Pause in danger",
			"Stand down on low health, in fire, over a drop, or while gliding", true));

	/** Where the player was when this started; the leash is measured from here. */
	private Vec3 origin;
	/** Hotbar slot to put back, and the yaw/pitch to restore. Only ever what we changed. */
	private int savedSlot = -1;
	private Float savedYaw;
	private Float savedPitch;

	/** Last look angle seen, for spotting mouse movement we did not cause. */
	private Float lastYaw;
	private Float lastPitch;

	private int idleTicks;
	private int untilNextAction;
	private int sinceMessage = Integer.MAX_VALUE;

	/** The action currently being held, and how much longer for. */
	private InputActionCoordinator.Key holding;
	private int holdTicks;

	private final Random rng = new Random();
	private final List<Runnable> pool = new ArrayList<>();

	public AntiAFK() {
		super("AntiAFK", "Bounded, reversible activity so you are not kicked", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		origin = mc().player == null ? null : mc().player.position();
		idleTicks = 0;
		untilNextAction = nextInterval();
		holding = null;
		holdTicks = 0;
		savedSlot = -1;
		savedYaw = null;
		savedPitch = null;
		lastYaw = null;
		lastPitch = null;
	}

	@Override
	protected void onDisable() {
		// Release before restore: a key still held while the slot is put back is a tick of the
		// module driving the game after it was switched off.
		InputActionCoordinator.release(this);
		InventoryActionCoordinator.release(this);
		restore();
		holding = null;
		holdTicks = 0;
		origin = null;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	/** Puts back only what this module changed, and only once. */
	private void restore() {
		LocalPlayer player = mc().player;
		if (player == null) {
			savedSlot = -1;
			savedYaw = null;
			savedPitch = null;
			return;
		}
		if (savedSlot >= 0) {
			player.getInventory().setSelectedSlot(savedSlot);
			savedSlot = -1;
		}
		if (savedYaw != null) {
			player.setYRot(savedYaw);
			savedYaw = null;
		}
		if (savedPitch != null) {
			player.setXRot(savedPitch);
			savedPitch = null;
		}
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			onDisable();
			return;
		}
		if (origin == null) {
			origin = player.position();
		}
		if (sinceMessage < Integer.MAX_VALUE) {
			sinceMessage++;
		}

		// Keys and the mouse both count as "you are back". The mouse has no coordinator to ask,
		// so it is inferred from the head moving when we were not the ones moving it — which is
		// exactly what a look means and costs two floats to notice.
		boolean turned = lastYaw != null
				&& (player.getYRot() != lastYaw || player.getXRot() != lastPitch);
		lastYaw = player.getYRot();
		lastPitch = player.getXRot();
		boolean realInput = InputActionCoordinator.anyUserInput() || turned;
		if (realInput) {
			idleTicks = 0;
			if (stopOnInput.get()) {
				// Everything, now. The next tick re-evaluates from scratch; there is no
				// half-finished action to preserve and nothing that a delay would improve.
				stopActing(player);
				origin = player.position();
				return;
			}
		} else {
			idleTicks++;
		}

		if (!allowed(player)) {
			stopActing(player);
			return;
		}
		if (holding != null && holdTicks > 0) {
			holdTicks--;
			InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_IDLE, holding);
			return;
		}
		if (holding != null) {
			InputActionCoordinator.release(this, holding);
			holding = null;
		}
		if (walkHome(player)) {
			return;
		}
		if (--untilNextAction > 0) {
			return;
		}
		untilNextAction = nextInterval();
		act(player);
		maybeMessage();
	}

	/** Releases every hold and puts our own changes back. Idempotent. */
	private void stopActing(LocalPlayer player) {
		InputActionCoordinator.release(this);
		InventoryActionCoordinator.release(this);
		holding = null;
		holdTicks = 0;
		restore();
	}

	/**
	 * Everything that means "not now".
	 *
	 * <p>The danger checks are the ones worth having. Being kicked costs a reconnect; walking off
	 * a ledge or standing in a fire while nobody is watching costs the run, and the whole point of
	 * an AFK module is that nobody is watching.
	 */
	private boolean allowed(LocalPlayer player) {
		if (activation.is("After idle") && idleTicks < idleTime.getInt() * 60 * 20) {
			return false;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return false;
		}
		if (player.isPassenger() || player.isSpectator()) {
			return false;
		}
		if (pauseInCombat.get() && player.hurtTime > 0) {
			return false;
		}
		if (!pauseInDanger.get()) {
			return true;
		}
		if (player.getHealth() + player.getAbsorptionAmount() < 8.0f || player.isOnFire()
				|| player.isFallFlying() || player.isInLava()) {
			return false;
		}
		// Standing over nothing is the state a nudge turns into a fall.
		double drop = DamageForecast.distanceToGround(player, player.getBoundingBox());
		return drop >= 0.0 && drop <= 3.0;
	}

	/**
	 * Straight-line drift correction, one held key at a time.
	 *
	 * <p><b>Not a pathfinder, and it must never become one.</b> If the way back is blocked it
	 * simply keeps trying the direct line and gets nowhere, which is the correct failure: an AFK
	 * module that starts routing around obstacles is an AFK module that has left the area.
	 *
	 * @return whether it took the tick
	 */
	private boolean walkHome(LocalPlayer player) {
		if (!returnToOrigin.get() || origin == null) {
			return false;
		}
		double dx = origin.x - player.getX();
		double dz = origin.z - player.getZ();
		if (dx * dx + dz * dz <= HOME_TOLERANCE * HOME_TOLERANCE) {
			return false;
		}
		InputActionCoordinator.Key key = towards(player, dx, dz);
		if (key == null || !stepIsSafe(player, key)) {
			return false;
		}
		holding = key;
		holdTicks = HOLD_TICKS;
		InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_IDLE, key);
		return true;
	}

	/** Picks one action and starts it. */
	private void act(LocalPlayer player) {
		pool.clear();
		if (enabled(swing)) {
			pool.add(() -> player.swing(net.minecraft.world.InteractionHand.MAIN_HAND));
		}
		if (enabled(yaw)) {
			pool.add(() -> turn(player, true));
		}
		if (enabled(pitch)) {
			pool.add(() -> turn(player, false));
		}
		if (enabled(jump)) {
			pool.add(() -> hold(InputActionCoordinator.Key.JUMP, 2));
		}
		if (enabled(sneak)) {
			pool.add(() -> hold(InputActionCoordinator.Key.SNEAK, HOLD_TICKS));
		}
		if (enabled(strafe)) {
			pool.add(() -> strafe(player));
		}
		if (enabled(changeSlot)) {
			pool.add(() -> switchSlot(player));
		}
		if (pool.isEmpty()) {
			return;
		}
		// Random and Custom differ only in how many of the pool run: Custom does everything
		// ticked, Random picks one. Both read the same switches, so there is one list to reason
		// about rather than two that can disagree.
		if (mode.is("Custom")) {
			pool.forEach(Runnable::run);
		} else {
			pool.get(rng.nextInt(pool.size())).run();
		}
	}

	private boolean enabled(BooleanSetting setting) {
		return setting.get();
	}

	/**
	 * Turns the player, for real.
	 *
	 * <p>Not through {@link unlucky.utility.client.util.RotationManager}, and that is the point:
	 * a spoofed rotation is one the server sees and the client does not, which is the opposite of
	 * what this needs. An idle check is satisfied by the head actually moving, so the head actually
	 * moves — and the original angle is remembered once, so a hundred turns still restore to where
	 * you left it rather than to wherever the last one landed.
	 */
	private void turn(LocalPlayer player, boolean horizontal) {
		if (savedYaw == null) {
			savedYaw = player.getYRot();
			savedPitch = player.getXRot();
		}
		float amount = (rng.nextFloat() - 0.5f) * 2.0f * rotationAmount.getFloat();
		if (horizontal) {
			player.setYRot(player.getYRot() + amount);
		} else {
			player.setXRot(Mth.clamp(player.getXRot() + amount, -90.0f, 90.0f));
		}
		lastYaw = player.getYRot();
		lastPitch = player.getXRot();
	}

	private void hold(InputActionCoordinator.Key key, int ticks) {
		holding = key;
		holdTicks = ticks;
		InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_IDLE, key);
	}

	/** A sideways step, refused if it would leave the leash or land somewhere unpleasant. */
	private void strafe(LocalPlayer player) {
		if (movementRadius.getInt() <= 0) {
			return;
		}
		InputActionCoordinator.Key key = rng.nextBoolean()
				? InputActionCoordinator.Key.LEFT
				: InputActionCoordinator.Key.RIGHT;
		if (stepIsSafe(player, key)) {
			hold(key, HOLD_TICKS);
		}
	}

	/**
	 * Switches hotbar slot through the shared click owner, remembering the first slot only.
	 *
	 * <p>Through the coordinator rather than by writing the selection: the selected slot is the
	 * same thing AutoTool, AutoEat and the placement modules fight over, and an AFK filler is the
	 * lowest-priority claim on it there could be.
	 */
	private void switchSlot(LocalPlayer player) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_AUTOMATION)) {
			return;
		}
		if (savedSlot < 0) {
			savedSlot = player.getInventory().getSelectedSlot();
		}
		InventoryActionCoordinator.selectHotbar(this, rng.nextInt(9));
		InventoryActionCoordinator.release(this);
	}

	/**
	 * Whether a step in {@code key}'s direction stays on the leash and lands on real ground.
	 *
	 * <p>Checked before the key goes down, not after the step: "stop before, not after, crossing
	 * the edge" is the whole difference between a safety check and a post-mortem.
	 */
	private boolean stepIsSafe(LocalPlayer player, InputActionCoordinator.Key key) {
		Vec3 direction = directionOf(player, key);
		Vec3 destination = player.position().add(direction.scale(1.0));
		if (origin != null && movementRadius.getInt() > 0) {
			double dx = destination.x - origin.x;
			double dz = destination.z - origin.z;
			double radius = movementRadius.getInt();
			if (dx * dx + dz * dz > radius * radius) {
				return false;
			}
		}
		if (!safeMovement.get()) {
			return true;
		}
		net.minecraft.world.phys.AABB box = player.getBoundingBox()
				.move(direction.x, 0.0, direction.z);
		if (!mc().level.noCollision(player, box)) {
			return false;
		}
		net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(
				destination.x, box.minY - 0.5, destination.z);
		return MoveUtil.solidSupport(below) && !MoveUtil.hazardous(below)
				&& !MoveUtil.hazardous(below.above());
	}

	/** Unit direction for a movement key, relative to where the player is facing. */
	private static Vec3 directionOf(LocalPlayer player, InputActionCoordinator.Key key) {
		float radians = (float) Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
		Vec3 left = new Vec3(Math.cos(radians), 0.0, Math.sin(radians));
		return switch (key) {
			case FORWARD -> forward;
			case BACK -> forward.scale(-1.0);
			case LEFT -> left;
			case RIGHT -> left.scale(-1.0);
			default -> Vec3.ZERO;
		};
	}

	/** The movement key that points most nearly at the origin. */
	private static InputActionCoordinator.Key towards(LocalPlayer player, double dx, double dz) {
		InputActionCoordinator.Key best = null;
		double bestDot = 0.1; // ignore directions that barely help
		Vec3 wanted = new Vec3(dx, 0.0, dz).normalize();
		for (InputActionCoordinator.Key key : new InputActionCoordinator.Key[]{
				InputActionCoordinator.Key.FORWARD, InputActionCoordinator.Key.BACK,
				InputActionCoordinator.Key.LEFT, InputActionCoordinator.Key.RIGHT}) {
			double dot = directionOf(player, key).dot(wanted);
			if (dot > bestDot) {
				bestDot = dot;
				best = key;
			}
		}
		return best;
	}

	private void maybeMessage() {
		if (!messages.get() || messageList.get().isEmpty()
				|| sinceMessage < messageInterval.getInt() * 60 * 20) {
			return;
		}
		List<String> pool = messageList.get();
		String text = pool.get(rng.nextInt(pool.size()));
		if (text == null || text.isBlank() || text.startsWith("/")) {
			// No commands. Not a filter that can be turned off — see the class note.
			return;
		}
		sinceMessage = 0;
		mc().player.connection.sendChat(text);
	}

	private int nextInterval() {
		int min = Math.min(minimumInterval.getInt(), maximumInterval.getInt());
		int max = Math.max(minimumInterval.getInt(), maximumInterval.getInt());
		return (min + (max > min ? rng.nextInt(max - min + 1) : 0)) * 20;
	}

	/** Whether it is currently filling in for you, for the debug read-out. */
	public boolean acting() {
		return isEnabled() && mc().player != null && allowed(mc().player);
	}
}
