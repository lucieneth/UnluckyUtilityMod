package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Two ways to fly an elytra.
 *
 * <p><b>Boost</b> is the original: hold jump while gliding and you accelerate
 * along your look vector, capped at a top speed. Vanilla's glide physics still
 * run underneath, so you keep the dive-and-climb feel — pitch changes speed and
 * you sink whenever you stop pushing.
 *
 * <p><b>Static</b> replaces the glide physics outright (see
 * {@code LivingEntityMixin#unlucky$elytraFlyStatic}). Horizontal movement comes
 * only from WASD relative to your <i>yaw</i>, so looking up or down doesn't
 * change where you go; vertical comes only from jump and sneak. Nothing
 * accumulates — release the keys and you stop exactly there, hovering, which is
 * what makes it precise enough to fly a build with. Sink controls how much of
 * the natural fall is kept, and at 0 you don't drop at all.
 */
public class ElytraFly extends Module {
	/**
	 * Horizontal blocks per tick above which hitting a wall actually hurts.
	 *
	 * <p>Vanilla charges {@code lost * 10 - 3} for a fall-flying collision and a wall takes all of
	 * your horizontal speed, so 0.3 is exactly where that expression crosses zero. Below it a
	 * crash is free — see {@link #crashAhead}.
	 */
	private static final double DAMAGING_SPEED = 0.3;

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Boost accelerates along your look while you hold jump, on top of vanilla gliding. "
					+ "Static ignores vanilla gliding entirely: WASD moves you flat, jump and sneak "
					+ "move you up and down, and letting go stops you dead in the air.",
			"Boost", "Boost", "Static"));

	public final NumberSetting maxSpeed = add(new NumberSetting("Max speed",
			"Top speed in blocks per tick", 1.7, 0.5, 3.0, 0.1), () -> mode.is("Boost"));
	public final NumberSetting acceleration = add(new NumberSetting("Acceleration",
			"Strength added per tick", 0.08, 0.02, 0.3, 0.02), () -> mode.is("Boost"));

	public final NumberSetting horizontalSpeed = add(new NumberSetting("Horizontal speed",
			"Blocks per tick under WASD", 1.0, 0.1, 3.0, 0.05), () -> mode.is("Static"));
	public final NumberSetting verticalSpeed = add(new NumberSetting("Vertical speed",
			"Blocks per tick under jump and sneak", 0.5, 0.05, 2.0, 0.05), () -> mode.is("Static"));
	public final NumberSetting sink = add(new NumberSetting("Sink",
			"How much of the natural fall is kept. 0 hovers in place with no keys held.",
			0.0, 0.0, 1.0, 0.01), () -> mode.is("Static"));
	public final BooleanSetting brake = add(new BooleanSetting("Brake",
			"Gradually shed horizontal momentum after releasing jump in Boost mode", true), () -> mode.is("Boost"));
	public final NumberSetting brakeFactor = add(new NumberSetting("Brake factor",
			"Horizontal momentum retained each tick while braking", 0.85, 0.1, 1.0, 0.01),
			() -> mode.is("Boost") && brake.get());
	public final BooleanSetting preserveVertical = add(new BooleanSetting("Preserve vertical",
			"Do not let Boost acceleration alter climb or descent speed", false), () -> mode.is("Boost"));
	public final BooleanSetting autoTakeoff = add(new BooleanSetting("Auto takeoff",
			"Start gliding automatically after walking off a ledge", false));
	public final BooleanSetting autoBoost = add(new BooleanSetting("Auto boost held rockets",
			"Use a rocket already held in either hand when glide speed gets low", false), () -> mode.is("Boost"));
	public final NumberSetting boostBelowSpeed = add(new NumberSetting("Boost below speed",
			"Horizontal speed threshold that triggers Auto boost", 0.7, 0.1, 2.0, 0.05),
			() -> mode.is("Boost") && autoBoost.get());
	public final NumberSetting boostCooldown = add(new NumberSetting("Boost cooldown",
			"Minimum ticks between automatic rockets", 20, 1, 100, 1), () -> mode.is("Boost") && autoBoost.get());
	public final NumberSetting takeoffFallDistance = add(new NumberSetting("Takeoff fall distance",
			"Blocks fallen before Auto takeoff starts a glide", 0.8, 0.0, 5.0, 0.1), autoTakeoff::get);
	public final BooleanSetting noCrash = add(new BooleanSetting("No crash",
			"Brake before your flight path reaches a solid block, but only when you are moving "
					+ "fast enough horizontally for the impact to actually damage you", true));
	public final NumberSetting crashLookAhead = add(new NumberSetting("Crash look-ahead",
			"Blocks ahead along your horizontal velocity checked for an upcoming collision",
			4, 1, 16, 1), noCrash::get);
	public final NumberSetting crashBrake = add(new NumberSetting("Crash brake",
			"Horizontal velocity kept after a collision warning", 0.25, 0.0, 1.0, 0.05), noCrash::get);
	public final BooleanSetting durabilitySafety = add(new BooleanSetting("Durability safety",
			"Protect a nearly broken elytra", true));
	public final NumberSetting minimumDurability = add(new NumberSetting("Minimum durability %",
			"Threshold at which durability safety acts", 5, 1, 50, 1), durabilitySafety::get);
	public final ModeSetting lowDurabilityAction = add(new ModeSetting("On low durability",
			"Warn, slow the glide, or disable ElytraFly", "Warn", "Warn", "Slow", "Disable"), durabilitySafety::get);

	private boolean warnedLowDurability;
	private int rocketCooldown;

	public ElytraFly() {
		super("ElytraFly", "Boost or fly flat while gliding", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** True while Static owns the glide, i.e. the mixin should replace vanilla's movement. */
	public boolean staticFlight() {
		return isEnabled() && mode.is("Static");
	}

	@Override
	protected void onEnable() {
		warnedLowDurability = false;
		rocketCooldown = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return;
		}
		if (autoTakeoff.get() && !player.isFallFlying() && !player.onGround()
				&& player.fallDistance >= takeoffFallDistance.get() && player.getDeltaMovement().y < -0.03) {
			player.tryToStartFallFlying();
		}
		if (!player.isFallFlying()) return;
		if (lowDurability(player)) {
			handleLowDurability(player);
			if (!isEnabled()) return;
		} else {
			warnedLowDurability = false;
		}
		if (noCrash.get() && crashAhead(player)) {
			Vec3 velocity = player.getDeltaMovement();
			player.setDeltaMovement(velocity.x * crashBrake.get(), velocity.y, velocity.z * crashBrake.get());
			if (mode.is("Boost")) return;
		}
		if (!mode.is("Boost")) return;
		if (autoBoost.get()) autoBoost(player);
		Vec3 before = player.getDeltaMovement();
		if (!mc().options.keyJump.isDown()) {
			if (brake.get()) player.setDeltaMovement(before.x * brakeFactor.get(), before.y, before.z * brakeFactor.get());
			return;
		}
		Vec3 velocity = before
				.add(mc().player.getLookAngle().scale(acceleration.get()));
		if (preserveVertical.get()) velocity = new Vec3(velocity.x, before.y, velocity.z);
		double speed = velocity.length();
		if (speed > maxSpeed.get()) {
			velocity = velocity.scale(maxSpeed.get() / speed);
		}
		player.setDeltaMovement(velocity);
	}

	/**
	 * The whole of Static's movement for one tick, replacing whatever vanilla's
	 * glide physics worked out.
	 *
	 * @param vanilla what vanilla would have moved us by — only its downward Y is
	 *                reused, scaled by Sink
	 */
	public Vec3 staticMovement(Vec3 vanilla) {
		LocalPlayer player = mc().player;
		if (player == null) {
			return vanilla;
		}
		if (durabilitySafety.get() && lowDurability(player) && lowDurabilityAction.is("Disable")) {
			setEnabled(false);
			return vanilla;
		}
		// only a fall is carried over: an upward kick (a rocket, a bounce) would
		// otherwise fight the hover for several ticks
		double y = vanilla.y < 0.0 ? vanilla.y * sink.get() : 0.0;
		double x = 0.0;
		double z = 0.0;

		// a screen eats the keys anyway; the hover still applies, so opening your
		// inventory mid-flight parks you in the air instead of dropping you
		if (mc().gui.screen() == null) {
			double forwardInput = axis(mc().options.keyUp.isDown(), mc().options.keyDown.isDown());
			double strafeInput = axis(mc().options.keyRight.isDown(), mc().options.keyLeft.isDown());
			if (forwardInput != 0.0 || strafeInput != 0.0) {
				// yaw only — pitch deliberately does nothing, that's what makes it precise.
				// Normalising the sum keeps diagonals the same speed as the cardinals.
				Vec3 forward = Vec3.directionFromRotation(0.0f, player.getYRot());
				Vec3 right = Vec3.directionFromRotation(0.0f, player.getYRot() + 90.0f);
				Vec3 heading = forward.scale(forwardInput).add(right.scale(strafeInput)).normalize();
				x = heading.x * horizontalSpeed.get();
				z = heading.z * horizontalSpeed.get();
			}
			if (mc().options.keyJump.isDown()) {
				y += verticalSpeed.get();
			}
			if (mc().options.keyShift.isDown()) {
				y -= verticalSpeed.get();
			}
		}
		if (lowDurability(player) && lowDurabilityAction.is("Slow")) {
			x *= 0.5;
			z *= 0.5;
		}
		if (noCrash.get() && crashAhead(player)) return new Vec3(0.0, y, 0.0);
		return new Vec3(x, y, z);
	}

	/** Uses only a rocket the player deliberately keeps in hand; inventory selection stays untouched. */
	private void autoBoost(LocalPlayer player) {
		if (rocketCooldown > 0) {
			rocketCooldown--;
			return;
		}
		Vec3 velocity = player.getDeltaMovement();
		if (Math.hypot(velocity.x, velocity.z) > boostBelowSpeed.get()) return;
		InteractionHand hand = player.getMainHandItem().is(Items.FIREWORK_ROCKET)
				? InteractionHand.MAIN_HAND
				: player.getOffhandItem().is(Items.FIREWORK_ROCKET) ? InteractionHand.OFF_HAND : null;
		if (hand == null || mc().gameMode == null) return;
		mc().gameMode.useItem(player, hand);
		player.swing(hand);
		rocketCooldown = boostCooldown.getInt();
	}

	private boolean lowDurability(LocalPlayer player) {
		if (!durabilitySafety.get()) return false;
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		return chest.is(Items.ELYTRA) && chest.isDamageableItem()
				&& (chest.getMaxDamage() - chest.getDamageValue()) * 100.0 / chest.getMaxDamage()
						<= minimumDurability.get();
	}

	private void handleLowDurability(LocalPlayer player) {
		if (!warnedLowDurability) {
			ChatUtil.info("§eElytraFly: elytra durability is below " + minimumDurability.getInt() + "%");
			warnedLowDurability = true;
		}
		if (lowDurabilityAction.is("Disable")) {
			setEnabled(false);
		} else if (lowDurabilityAction.is("Slow")) {
			Vec3 velocity = player.getDeltaMovement();
			player.setDeltaMovement(velocity.x * 0.5, velocity.y, velocity.z * 0.5);
		}
	}

	/**
	 * Whether the flight path is heading into a wall <em>hard enough for it to matter</em>.
	 *
	 * <p>Both halves of that come straight from the only code that punishes a crash,
	 * {@code LivingEntity.handleFallFlyingCollisions}:
	 *
	 * <pre>{@code
	 * if (this.horizontalCollision) {
	 *     float damage = (float)((before - after) * 10.0 - 3.0);
	 *     if (damage > 0.0F) { ... hurt(flyIntoWall(), damage); }
	 * }
	 * }</pre>
	 *
	 * <p><b>Horizontal only.</b> {@code before} and {@code after} are {@code horizontalDistance()},
	 * and the whole thing is behind {@code horizontalCollision} — so flying straight up into a
	 * ceiling costs nothing however fast you do it. Tracing the full velocity vector was what made
	 * this fire on every climb: the ray went up, found the ceiling or an overhang, and braked for
	 * an impact vanilla would have waved through.
	 *
	 * <p><b>And only above {@link #DAMAGING_SPEED}.</b> A wall stops you dead, so {@code after} is
	 * zero and the loss is the whole of your horizontal speed; below the threshold the arithmetic
	 * lands at or under zero damage and nothing happens to you at all. Braking there was pure
	 * interference — most of it while manoeuvring slowly in exactly the tight spaces that need it
	 * least.
	 */
	private boolean crashAhead(LocalPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		Vec3 heading = new Vec3(velocity.x, 0.0, velocity.z);
		if (heading.length() <= DAMAGING_SPEED) return false;
		Vec3 start = player.getEyePosition();
		Vec3 end = start.add(heading.normalize().scale(crashLookAhead.get()));
		return mc().level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS;
	}

	private static double axis(boolean positive, boolean negative) {
		return (positive ? 1.0 : 0.0) - (negative ? 1.0 : 0.0);
	}
}
