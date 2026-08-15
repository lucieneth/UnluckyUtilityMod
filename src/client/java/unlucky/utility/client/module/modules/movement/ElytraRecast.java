package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.EquipmentScorer;

/**
 * Puts a glide back on after something took it off you.
 *
 * <p><b>It is a retry, not a takeoff.</b> The only action here is vanilla's own
 * {@code tryToStartFallFlying()} — the same call the double-tap makes — and it only ever runs
 * after a glide that was already happening stopped happening. A cobweb, a bump on a ledge, a
 * server-side correction: all of those drop you out of flight while you are still hundreds of
 * blocks up and still holding jump, and re-arming by hand at that moment is the difference between
 * a flight and a crater. Starting a glide you never had is {@link ElytraFly}'s auto takeoff, and it
 * stays there.
 *
 * <p><b>The attempt budget is what stops it becoming a spammer.</b> Vanilla refuses a glide for
 * good reasons — you are in water, you are on a ladder, you have no wings — and the honest response
 * to a refusal is to try a few more times and then stop, not to send the packet every tick for the
 * rest of the fall.
 *
 * <p><b>It will not recast a nearly dead elytra by default.</b> Wings that are about to break are
 * how a flight ends badly; the retry that keeps them airborne for another twenty blocks is not
 * doing the player a favour. That is ElytraSwap's problem to solve, and this stands aside for it.
 */
public class ElytraRecast extends Module {
	public final ModeSetting trigger = add(new ModeSetting("Trigger",
			"Retry after anything that interrupts a glide, or only while jump is held",
			"Any interruption", "Any interruption", "Jump held"));
	public final NumberSetting retryDelay = add(new NumberSetting("Retry delay",
			"Ticks between attempts", 2, 0, 20, 1));
	public final NumberSetting maximumAttempts = add(new NumberSetting("Maximum attempts",
			"Give up after this many refusals", 5, 1, 20, 1));
	public final BooleanSetting autoJump = add(new BooleanSetting("Auto jump",
			"Jump first if the interruption left you standing on a ledge", false));
	public final NumberSetting minimumFall = add(new NumberSetting("Minimum fall distance",
			"Blocks of real fall required before retrying", 0.2, 0.0, 3.0, 0.1));

	public final NumberSetting minimumDurability = add(new NumberSetting("Minimum durability %",
			"Do not put worn-out wings back on", 5, 1, 100, 1));
	public final BooleanSetting allowDamaged = add(new BooleanSetting("Allow damaged elytra",
			"Ignore the durability threshold entirely", false));

	public final BooleanSetting pauseInWater = add(new BooleanSetting("Pause in water",
			"No retry while in a fluid — vanilla would refuse anyway", true));
	public final BooleanSetting pauseLevitation = add(new BooleanSetting("Pause with levitation",
			"No retry under levitation", true));
	public final BooleanSetting pauseOnCollision = add(new BooleanSetting("Pause on collision",
			"No retry while you are scraping along a wall", true));

	/** Whether a glide was running as of the last tick — the thing an interruption interrupts. */
	private boolean wasFlying;
	/** Attempts spent in the current window. */
	private int attempts;
	private int delayTicks;
	/** Whether the retry window is open, which is the whole of this module's visibility. */
	private boolean window;

	public ElytraRecast() {
		super("ElytraRecast", "Restarts a glide that was interrupted", Category.MOVEMENT,
				ServerVisibility.CONDITIONAL);
	}

	/**
	 * Only inside the retry window.
	 *
	 * <p>Outside it this module sends nothing at all, and inside it the only thing it sends is a
	 * start-fall-flying the player could have sent themselves — which is still a packet they did
	 * not press a key for, so the window counts.
	 */
	@Override
	public boolean isServerObservableNow() {
		return window;
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		reset();
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	private void reset() {
		wasFlying = false;
		attempts = 0;
		delayTicks = 0;
		window = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			reset();
			return;
		}
		if (player.isFallFlying()) {
			// Flying again — by our hand or the player's, it makes no difference.
			wasFlying = true;
			attempts = 0;
			delayTicks = 0;
			window = false;
			return;
		}
		if (!wasFlying) {
			return; // nothing was interrupted, so there is nothing to recast
		}
		if (player.onGround()) {
			handleGrounded(player);
			return;
		}
		if (!eligible(player)) {
			window = false;
			return;
		}
		window = true;
		if (delayTicks > 0) {
			delayTicks--;
			return;
		}
		if (attempts >= maximumAttempts.getInt()) {
			window = false;
			return;
		}
		attempts++;
		delayTicks = retryDelay.getInt();
		if (player.tryToStartFallFlying()) {
			window = false;
		}
	}

	/**
	 * On the ground the window is over: either we jump off it once because the player asked for
	 * that, or the interruption is simply finished and this module goes back to sleep.
	 */
	private void handleGrounded(LocalPlayer player) {
		window = false;
		if (autoJump.get() && attempts < maximumAttempts.getInt() && delayTicks <= 0
				&& hasUsableElytra(player)) {
			attempts++;
			delayTicks = Math.max(1, retryDelay.getInt());
			player.jumpFromGround();
			return;
		}
		if (!autoJump.get() || attempts >= maximumAttempts.getInt()) {
			reset();
		} else if (delayTicks > 0) {
			delayTicks--;
		}
	}

	/** Everything that has to hold before a retry is worth sending. */
	private boolean eligible(LocalPlayer player) {
		if (player.isPassenger() || player.onClimbable() || player.isSpectator()) {
			return false;
		}
		if (trigger.is("Jump held") && !mc().options.keyJump.isDown()) {
			return false;
		}
		if (pauseInWater.get() && (player.isInWater() || player.isInLava())) {
			return false;
		}
		if (pauseLevitation.get() && player.hasEffect(MobEffects.LEVITATION)) {
			return false;
		}
		if (pauseOnCollision.get() && player.horizontalCollision) {
			return false;
		}
		if (player.fallDistance < minimumFall.get()) {
			return false;
		}
		return hasUsableElytra(player) && !elytraFlyIsAboutToDoIt(player);
	}

	private boolean hasUsableElytra(LocalPlayer player) {
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		if (!chest.is(Items.ELYTRA)) {
			return false;
		}
		return allowDamaged.get()
				|| EquipmentScorer.durabilityPercent(chest) > minimumDurability.get();
	}

	/**
	 * ElytraFly's auto takeoff fires on exactly this state, and whichever of the two ticks first
	 * would leave the other sending a second start-fall-flying into the same fall.
	 *
	 * <p>Ordinarily the {@code isFallFlying()} check at the top of the tick is enough, because
	 * vanilla sets the flag synchronously. This covers the other registration order, and it does it
	 * by naming ElytraFly's own condition rather than by guessing at one.
	 */
	private boolean elytraFlyIsAboutToDoIt(LocalPlayer player) {
		ElytraFly elytraFly = UnluckyClient.INSTANCE.modules.get(ElytraFly.class);
		return elytraFly.isEnabled() && elytraFly.autoTakeoff.get()
				&& player.fallDistance >= elytraFly.takeoffFallDistance.get()
				&& player.getDeltaMovement().y < -0.03;
	}
}
