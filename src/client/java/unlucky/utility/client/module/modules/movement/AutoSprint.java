package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.InputActionCoordinator;
import unlucky.utility.client.util.SprintProbe;

/**
 * Keeps you sprinting whenever you move. Omni-directional mode also sprints
 * sideways and backwards.
 *
 * <p><b>It holds the sprint key; it does not write the sprint flag.</b> That is
 * the whole design, and it is not a stylistic choice — 26.2's server keeps its
 * own opinion of whether you are sprinting and syncs it back down. Writing the
 * flag ourselves produced a two-tick war: we set it, {@code aiStep} kept it
 * (nothing was wrong), {@code sendIsSprintingIfNeeded} sent START_SPRINTING,
 * and then the shared-flags byte came back from the server with sprint cleared,
 * because the input record it judges us by never had the sprint bit set. One
 * packet a tick for as long as you were moving, and the sprint itself flickering
 * underneath it. The {@code .sprint} probe caught the server's write in the act
 * ({@code SynchedEntityData.assignValues} clearing a flag we had just set).
 *
 * <p>Holding {@code keySprint} instead puts the bit in the input record the
 * server reads, so client and server agree, and vanilla's own {@code aiStep}
 * does the starting, the cancelling at a wall and the re-taking afterwards — on
 * its own schedule, in the same tick, exactly as for a player whose finger is on
 * the key. All that leaves us is policy: when to ask, and when to let go.
 *
 * <p>The one thing a key cannot say is "sprint sideways": vanilla will not start
 * a sprint without forward impulse. Omni-directional mode still writes the flag
 * for that case only, and {@link #vanillaWouldCancel} keeps it quiet on the
 * ticks vanilla would undo it anyway.
 */
public class AutoSprint extends Module {
	public final BooleanSetting omniDirectional = add(new BooleanSetting("Omni-directional", "Sprint in any direction", true));
	public final BooleanSetting keepSprinting = add(new BooleanSetting("Keep sprinting",
			"Re-take sprint the moment an attack or item use drops it, instead of waiting for you to move off and back on", true));

	/** Whether we were moving <i>and</i> allowed to sprint last tick, to spot the moment that starts. */
	private boolean wasEligible;
	/** Something other than us dropped the flag; set by the {@code setSprinting} hook. */
	private boolean externalClear;
	/**
	 * With "Keep sprinting" off, the sprint an attack took away is allowed to stay
	 * away — which, now that a held key would have vanilla hand it straight back,
	 * means letting go of the key until you move off and on again.
	 */
	private boolean surrendered;
	public final BooleanSetting stopUsing = add(new BooleanSetting("Stop while using item", "Do not start sprinting while using an item", true));
	public final BooleanSetting stopSneaking = add(new BooleanSetting("Stop while sneaking", "Do not start sprinting while sneaking", true));
	public final BooleanSetting stopInGui = add(new BooleanSetting("Stop in GUI", "Do not start sprinting while a screen is open", true));

	public AutoSprint() {
		super("AutoSprint", "Sprints for you", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		wasEligible = false;
		surrendered = false;
		externalClear = false;
		InputActionCoordinator.release(this);
	}

	/**
	 * Called from the {@code setSprinting} hook for every drop of the local player's
	 * flag. We never clear it ourselves, so every one of these is somebody else's —
	 * vanilla at a wall, or an attack's sprint reset — which is exactly the event
	 * "Keep sprinting" is a preference about.
	 */
	public void noteSprintCleared() {
		externalClear = true;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			return;
		}

		boolean cleared = externalClear;
		externalClear = false;

		boolean moving = omniDirectional.get()
				? player.input.getMoveVector().lengthSquared() > 0.0f
				: player.input.hasForwardImpulse();
		// Standing still, or told not to: stop asking, and the coordinator gives the
		// key back to your hand at tick end. Dropping the latch here is also what
		// makes moving off and on again read as a fresh start.
		if (!moving) {
			wasEligible = false;
			SprintProbe.decision("skip:still", false);
			return;
		}
		String policy = policyReason(player);
		if (policy != null) {
			wasEligible = false;
			SprintProbe.decision("skip:" + policy, false);
			return;
		}
		boolean freshStart = !wasEligible;
		wasEligible = true;
		if (freshStart) {
			surrendered = false;
		} else if (!keepSprinting.get() && cleared) {
			// Off, we behave like a double-tap: the sprint begins when you begin
			// moving, and whatever ends it (an attack's knockback reset) is allowed
			// to stick. On, the held key takes it back on vanilla's next pass.
			surrendered = true;
		}
		if (surrendered) {
			SprintProbe.decision("skip:surrendered", false);
			return;
		}

		// The mechanism, in one line. Everything vanilla knows about sprinting —
		// walls, hunger, shallow water, the tick to re-take it on — applies to a held
		// key already, and the server sees the same key in the input record we do.
		InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_TRAVEL,
				InputActionCoordinator.Key.SPRINT);

		if (player.isSprinting()) {
			SprintProbe.decision("already", false);
			return;
		}
		if (player.input.hasForwardImpulse()) {
			SprintProbe.decision("holding", false); // vanilla starts it on the next aiStep
			return;
		}
		// Sideways or backwards, where the key alone says nothing. The flag is ours to
		// write, and only on the ticks vanilla would leave it alone.
		String blocked = vanillaWouldCancel(player);
		if (blocked != null) {
			SprintProbe.decision("skip:" + blocked, false);
			return;
		}
		player.setSprinting(true);
		SprintProbe.decision("SET(omni)", true);
	}

	/** Ours, not vanilla's: the three settings that decide when not to ask at all. */
	private String policyReason(LocalPlayer player) {
		if (stopSneaking.get() && player.isShiftKeyDown()) {
			return "sneaking";
		}
		if (stopUsing.get() && player.isUsingItem()) {
			return "using";
		}
		if (stopInGui.get() && mc().gui.screen() != null) {
			return "gui";
		}
		return null;
	}

	/**
	 * Vanilla's {@code isSprintingPossible} and {@code shouldStopRunSprinting},
	 * rebuilt from the public API — the conditions under which LocalPlayer would
	 * drop the flag again within the same tick. Only the omni-directional flag
	 * write consults this; a held key needs none of it, because vanilla applies
	 * every one of these itself before it ever sets the flag.
	 *
	 * <p>The answer is a reason rather than a boolean: the probe log is only
	 * readable if it says <i>which</i> of them stopped us on a given tick.
	 *
	 * @return null when we may sprint, otherwise the condition that says no
	 */
	private String vanillaWouldCancel(LocalPlayer player) {
		// Swim-sprinting is what makes you swim at all, and vanilla ends it on
		// its own terms (leaving water, letting go of forward). Not ours to hold.
		if (player.isSwimming()) {
			return "swimming";
		}
		// elytra: vanilla only allows the sprint flag up while underwater
		if (player.isFallFlying() && !player.isUnderWater()) {
			return "elytra";
		}
		if (player.isMobilityRestricted()) {
			return "blind";
		}
		if (player.isPassenger()) {
			Entity vehicle = player.getVehicle();
			if (vehicle == null || !vehicle.canSprint() || !vehicle.isLocalInstanceAuthoritative()) {
				return "vehicle";
			}
		} else if (!player.getFoodData().hasEnoughFood() && !player.getAbilities().mayfly) {
			// the server rejects sprinting when too hungry, don't fight it
			return "hunger";
		}
		if (!player.getAbilities().flying && player.isInShallowWater()) {
			return "shallow";
		}
		// The one that caused the loop. A graze along a wall sets
		// minorHorizontalCollision and vanilla lets that keep sprinting; a
		// head-on bump doesn't, so neither do we.
		if (player.horizontalCollision && !player.minorHorizontalCollision) {
			return "collision";
		}
		return null;
	}
}
