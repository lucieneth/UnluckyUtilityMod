package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Keeps you sprinting whenever you move. Omni-directional mode also sprints
 * sideways and backwards.
 *
 * <p>The rule the module lives by: never assert a sprint that vanilla is about
 * to take straight back. {@code LocalPlayer.aiStep} cancels sprinting for a
 * list of reasons (walking into a wall, empty hunger bar, shallow water), and
 * we run at END_CLIENT_TICK — after that cancel. Blindly setting the flag back
 * to true meant the next tick's {@code sendIsSprintingIfNeeded} saw a flip and
 * sent START_SPRINTING, aiStep cancelled again, the tick after sent
 * STOP_SPRINTING: two packets a tick, forever, for as long as you leaned on a
 * block. {@link #shouldSprint} mirrors vanilla's own cancel conditions so we
 * simply stop asking during the ticks it would say no.
 */
public class AutoSprint extends Module {
	public final BooleanSetting omniDirectional = add(new BooleanSetting("Omni-directional", "Sprint in any direction", true));
	public final BooleanSetting keepSprinting = add(new BooleanSetting("Keep sprinting",
			"Re-take sprint the moment an attack or item use drops it, instead of waiting for you to move off and back on", true));

	/** Whether we were moving <i>and</i> allowed to sprint last tick, to spot the moment that starts. */
	private boolean wasEligible;

	public AutoSprint() {
		super("AutoSprint", "Sprints for you", Category.MOVEMENT);
	}

	@Override
	protected void onDisable() {
		wasEligible = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			return;
		}

		boolean moving = omniDirectional.get()
				? player.input.getMoveVector().lengthSquared() > 0.0f
				: player.input.hasForwardImpulse();
		// Dropping the latch on either count means coming back out of a wall (or
		// off a boat, or out of the shallows) reads as a fresh start rather than
		// a sprint we already had our turn at.
		if (!moving || !shouldSprint(player)) {
			wasEligible = false;
			return;
		}
		boolean freshStart = !wasEligible;
		wasEligible = true;

		if (player.isSprinting()) {
			return;
		}
		// Off, we behave like a double-tap: the sprint begins when you begin
		// moving, and whatever ends it (an attack's knockback reset) is allowed
		// to stick. On, we take it straight back.
		if (!keepSprinting.get() && !freshStart) {
			return;
		}
		player.setSprinting(true);
	}

	/**
	 * Vanilla's {@code isSprintingPossible} and {@code shouldStopRunSprinting},
	 * rebuilt from the public API. Everything here is a condition under which
	 * LocalPlayer would drop the flag again within the same tick.
	 */
	private boolean shouldSprint(LocalPlayer player) {
		if (player.isShiftKeyDown() || player.isUsingItem()) {
			return false;
		}
		// Swim-sprinting is what makes you swim at all, and vanilla ends it on
		// its own terms (leaving water, letting go of forward). Not ours to hold.
		if (player.isSwimming()) {
			return false;
		}
		// elytra: vanilla only allows the sprint flag up while underwater
		if (player.isFallFlying() && !player.isUnderWater()) {
			return false;
		}
		if (player.isMobilityRestricted()) {
			return false;
		}
		if (player.isPassenger()) {
			Entity vehicle = player.getVehicle();
			if (vehicle == null || !vehicle.canSprint() || !vehicle.isLocalInstanceAuthoritative()) {
				return false;
			}
		} else if (!player.getFoodData().hasEnoughFood() && !player.getAbilities().mayfly) {
			// the server rejects sprinting when too hungry, don't fight it
			return false;
		}
		if (!player.getAbilities().flying && player.isInShallowWater()) {
			return false;
		}
		// The one that caused the loop. A graze along a wall sets
		// minorHorizontalCollision and vanilla lets that keep sprinting; a
		// head-on bump doesn't, so neither do we.
		return !player.horizontalCollision || player.minorHorizontalCollision;
	}
}
