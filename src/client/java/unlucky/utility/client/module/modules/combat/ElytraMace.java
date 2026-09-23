package unlucky.utility.client.module.modules.combat;

import java.util.function.Predicate;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.DamageForecast;
import unlucky.utility.client.util.HeldAttack;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.OffhandManager;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.SwingUtil;
import unlucky.utility.client.util.TargetingUtil;

/**
 * The elytra mace dive, assisted: you fly and aim, this takes the wings off at the last moment,
 * lands the smash and puts them back on.
 *
 * <p><b>Why the wings have to come off at all.</b> A smash is {@code MaceItem.canSmashAttack}:
 * more than 1.5 blocks of fall and <em>not</em> {@code isFallFlying()}. Jumping does not end a
 * Java glide; only losing the glider does, when the server's {@code updateFallFlying} finds
 * {@code canGlide()} false on its next tick. So the technique is a right-click (or a click) that
 * swaps the elytra for a chestplate a few ticks before reach, a falling hit, and the elytra back on.
 * A steep dive is worth it because the fall inside a glide counts once the descent is at least half
 * a block a tick ({@code checkFallDistanceAccumulation}), and the smash bonus is never scaled by
 * the attack charge.
 *
 * <p><b>The swap goes on the last tick that still works.</b> Every glide tick the planner runs the
 * dive forward twice — wings off now, wings off next tick — through vanilla's glide and free-fall
 * physics, and commits only when "next tick" no longer reaches. Later is better: the glide is the
 * only part of the dive the player can steer, and a short free fall is a short prediction. It also
 * refuses a dive whose miss would hurt more than {@code Max fall damage} unless a rescue can cover
 * it, which is what makes a steep dive onto a ground target (thirty blocks of banked fall) need a
 * wind charge in the hotbar.
 *
 * <p><b>The strike waits for the server, not for a timer.</b> The client keeps gliding until the
 * server's flag comes back down, so {@code !isFallFlying()} is proof the swap was processed.
 * Nothing in the changelogs or the wiki says how many ticks that takes; the flag is the measurement.
 *
 * <p><b>Crits, as 26.3 actually judges them.</b> A crit needs the server's attack charge above
 * 0.9 and no sprint ({@code Player.canCriticalAttack}). The charge resets when the main-hand item
 * changes <em>and</em> on every successful use ({@code ServerPlayer.swingAndResetAttackStrength}
 * from {@code handleUseItem}), so the right-click swaps — Offhand and Hotbar — land the smash
 * without the crit; only Inventory's container click leaves the charge alone. A sprint running
 * when the glide began survives it (26.3 only refuses to <em>start</em> one mid-glide), so the
 * swap drops it and a strike that still finds one brackets the hit in STOP/START the way
 * Criticals does.
 *
 * <p><b>Legit limits.</b> At most one slot change, one use and one attack per tick; the reach is
 * the weapon's own ({@code attack_range}, else the {@code entity_interaction_range} attribute);
 * the strike sends the punch packet vanilla's click sends after it. No position is ever sent and
 * no fall is spoofed — amplifying one is LegitMaceKill's and BlatantMaceKill's job, and the attack
 * hook keeps both (and Criticals, and MaceCombo) away from this module's hit.
 *
 * <p><b>ElytraSwap's "never a chestplate while gliding" is unchanged.</b> That rule protects a
 * convenience key from ever meaning a fall. This is a separate module the player turns on for
 * exactly this, and it only takes the wings off when the planner has already decided the dive
 * ends in a hit or a survivable miss.
 */
public class ElytraMace extends Module {
	/** How far ahead the planner looks. Two seconds of fall covers any dive a reach of three can end. */
	private static final int PLAN_TICKS = 40;
	/** Ticks past the planned hit before the strike is called a miss. */
	private static final int STRIKE_GRACE = 3;
	/** Ticks past the lead before a swap the server never answered is given up on. */
	private static final int CONFIRM_SLACK = 10;
	/** Chest armour's index in the player's own inventory menu, as ElytraSwap uses it. */
	private static final int CHEST_MENU_SLOT = 6;
	/** A planned miss must leave at least this much health standing. */
	private static final float MISS_RESERVE = 1.0f;
	/** Ticks a re-glide spends before it can arrest anything: equip, start, the server's answer. */
	private static final int REGLIDE_TICKS = 3;
	/** Height a started glide needs to bend a dive into level flight. */
	private static final double REGLIDE_ARREST = 10.0;
	/** Glide starts tried before recovery stops asking. */
	private static final int GLIDE_ATTEMPTS = 3;
	/** How far along the look the way has to be clear before a rocket is fired down it. */
	private static final double ROCKET_CLEARANCE = 8.0;
	/** Ticks the rocket waits for the player to pull up before it is skipped. */
	private static final int ROCKET_WAIT = 40;
	/** WindChargeItem.PROJECTILE_SHOOT_POWER — the charge's speed relative to the thrower. */
	private static final double WIND_CHARGE_SPEED = 1.5;
	/** Highest the burst can catch the thrower: inside the 2.4-block blast of a 1.2 explosion. */
	private static final double WIND_CHARGE_CATCH = 2.0;
	/** Ticks after a finished dive before the planner may commit to another. */
	private static final int REARM_TICKS = 20;
	/** Rescue stops after this long; by then the player has landed or is flying again. */
	private static final int RESCUE_TIMEOUT = 100;

	public final BooleanSetting players = add(new BooleanSetting("Players", "Target players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostile", "Target hostile mobs", false));
	public final BooleanSetting passives = add(new BooleanSetting("Passive", "Target passive mobs", false));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never dive on a friend", true));
	public final NumberSetting range = add(new NumberSetting("Range",
			"Maximum target range", 40, 5, 120, 1));
	public final NumberSetting fov = add(new NumberSetting("FOV",
			"Full targeting cone", 120, 10, 360, 5));

	public final ModeSetting chestplateSource = add(new ModeSetting("Chestplate source",
			"Where the chestplate comes from. Offhand: a right-click, the mace stays in hand, but 26.3 "
					+ "resets the attack charge on any use, so no crit. Inventory: a container click, "
					+ "keeps the charge and the crit. Hotbar: a slot switch and a right-click, no crit",
			"Offhand", "Offhand", "Inventory", "Hotbar"));
	public final ModeSetting confirm = add(new ModeSetting("Confirm",
			"Strike once the server has ended the glide, or after a fixed wait", "Server", "Server", "Fixed"));
	public final NumberSetting fixedTicks = add(new NumberSetting("Fixed ticks",
			"Ticks after the swap before the strike may go", 2, 1, 10, 1), () -> confirm.is("Fixed"));
	public final NumberSetting reachMargin = add(new NumberSetting("Reach margin",
			"How far inside your reach the strike has to be", 0.3, 0, 1, 0.05));
	public final BooleanSetting requireCrosshair = add(new BooleanSetting("Require crosshair",
			"Only strike while your crosshair is on the target", true));
	public final NumberSetting minSmashFall = add(new NumberSetting("Min smash fall",
			"Blocks of fall the planned strike has to carry", 3, 1.5, 40, 0.5));
	public final BooleanSetting rocketAfter = add(new BooleanSetting("Rocket after re-glide",
			"Fire a rocket once the new glide is confirmed and the way ahead is clear", true));
	public final ModeSetting rescue = add(new ModeSetting("Rescue",
			"After a miss: glide again, then a wind charge under your feet if the landing would still hurt",
			"Re-glide → Wind charge", "Re-glide → Wind charge", "Off"));
	public final NumberSetting maxFallDamage = add(new NumberSetting("Max fall damage",
			"Most a missed strike may cost you, in health points, before the dive needs a rescue to commit",
			6, 0, 20, 0.5));

	/** Where the dive is. Public for the gametest and for read-outs; nothing outside acts on it. */
	public enum State {
		IDLE,
		ARMED,
		UNGLIDE,
		CONFIRM,
		STRIKE,
		RECOVER,
		RESCUE
	}

	/** RECOVER's own steps, one tick apart where the protocol needs them to be. */
	private enum Recovery {
		WINGS,
		GLIDE,
		CONFIRM_GLIDE,
		ROCKET
	}

	/** A dive that ends in a strike, {@code hitTick} ticks after the swap. */
	private record Plan(int hitTick) {
	}

	private State state = State.IDLE;
	private Recovery recovery = Recovery.WINGS;
	/** The target a committed dive is flying at; re-selected every tick only while ARMED. */
	private LivingEntity target;
	/** Glide ticks the planner assumed after the swap, from the round trip at commit time. */
	private int lead;
	/** Ticks since the swap, and when the plan put the hit. */
	private int sinceSwap;
	private int hitTick;
	/** When STRIKE was entered — a late confirm moves the deadline, not the plan. */
	private int strikeFrom;
	/** Ticks in the current state or recovery step. */
	private int stateTicks;
	private int glideAttempts;
	private int rearm;
	/** ARMED ticks without a chestplate at the source, for the one warning that says why. */
	private int unreadyTicks;
	private boolean unreadyWarned;
	private boolean windChargeUsed;
	private String rescueReason = "";
	/** Set around this module's own attack, for the attack hook. */
	private boolean striking;
	/** A hotbar slot was borrowed for a use; next tick puts the player's slot back. */
	private boolean restorePending;
	/** This tick's budget: one slot change, one use, one attack. */
	private boolean slotChanged;
	private boolean used;
	private boolean attacked;

	public ElytraMace() {
		super("ElytraMace", "Takes the elytra off for a mace smash at the end of your dive, then puts it back on",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Where the dive is right now. */
	public State state() {
		return state;
	}

	/** The dive's target, or null. */
	public LivingEntity target() {
		return target;
	}

	/** Anything past IDLE: a mace glide is armed or a dive is under way. */
	public boolean isRunning() {
		return isEnabled() && state != State.IDLE;
	}

	/**
	 * Whether AutoArmor must leave the chest slot alone. For the whole run, not only while the
	 * chestplate is on: the piece in that slot is the one the next swap takes back off, and a
	 * "better" one arriving mid-dive is a swap nobody planned.
	 */
	public boolean guardsChestSlot() {
		return isRunning();
	}

	/** True only while this module's own attack is passing through the attack hook. */
	public boolean isStriking() {
		return striking;
	}

	/** One line of internal state, for probes and the gametest's failure message. */
	public String debug() {
		return String.format("state=%s recovery=%s sinceSwap=%d hit=%d strikeFrom=%d lead=%d reason=%s",
				state, recovery, sinceSwap, hitTick, strikeFrom, lead, rescueReason);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	/**
	 * Off mid-dive still puts the wings back on — that is undoing what this module did, not a new
	 * action. Panic skips even that ({@link #onPanic} resets first), because a panic is the one
	 * moment nothing should click.
	 */
	@Override
	protected void onDisable() {
		LocalPlayer player = mc().player;
		if (player != null && mc().gameMode != null && state.compareTo(State.UNGLIDE) >= 0
				&& !wearingElytra(player)) {
			slotChanged = false;
			used = false;
			putWingsOn(player);
		}
		InventoryActionCoordinator.release(this);
		reset();
	}

	@Override
	protected void onPanic() {
		InventoryActionCoordinator.release(this);
		reset();
	}

	private void reset() {
		state = State.IDLE;
		recovery = Recovery.WINGS;
		target = null;
		sinceSwap = 0;
		stateTicks = 0;
		glideAttempts = 0;
		rearm = 0;
		unreadyTicks = 0;
		unreadyWarned = false;
		windChargeUsed = false;
		rescueReason = "";
		striking = false;
		restorePending = false;
	}

	@Override
	public void onTick() {
		slotChanged = false;
		used = false;
		attacked = false;
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null || player.isSpectator()) {
			InventoryActionCoordinator.release(this);
			reset();
			return;
		}
		if (rearm > 0) {
			rearm--;
		}
		restoreHotbar();
		if (state.compareTo(State.UNGLIDE) > 0) {
			sinceSwap++;
		}
		// A transition runs the new state in the same tick — a confirm that arrives is a strike
		// that can go now — and the budget is what stops one tick doing two of anything.
		for (int step = 0; step < 4; step++) {
			State before = state;
			switch (state) {
				case IDLE -> idle(player);
				case ARMED -> armed(player);
				case UNGLIDE -> unglide(player);
				case CONFIRM -> confirm(player);
				case STRIKE -> strike(player);
				case RECOVER -> recover(player);
				case RESCUE -> rescue(player);
			}
			if (state == before) {
				break;
			}
		}
		if (state != State.IDLE && chestplateSource.is("Offhand")) {
			// Wearing wings, the chestplate is what the offhand should hold, and the other way
			// round: the next swap's item, asked for every tick so AutoTotem can still outbid it.
			OffhandManager.request(this, OffhandManager.PRIORITY_COMBAT, this::nextSwapItem,
					"ElytraMace swap", true);
		}
	}

	// ---- states --------------------------------------------------------------

	private void idle(LocalPlayer player) {
		if (rearm <= 0 && armable(player)) {
			state = State.ARMED;
			unreadyTicks = 0;
			unreadyWarned = false;
		}
	}

	private void armed(LocalPlayer player) {
		if (!armable(player)) {
			state = State.IDLE;
			target = null;
			return;
		}
		if (!chestplateReady(player)) {
			// The offhand is filled at the end of the first armed tick, so one tick without is
			// normal; a run of them is AutoTotem holding it, or no chestplate at all.
			if (++unreadyTicks == 10 && !unreadyWarned) {
				unreadyWarned = true;
				warnUnready();
			}
			return;
		}
		unreadyTicks = 0;
		target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(players.get(), hostiles.get(), passives.get())
						.ignoreFriends(ignoreFriends.get()).range(range.get()).fov(fov.get())
						.priority(TargetingUtil.Priority.SMALLEST_ANGLE));
		if (target == null) {
			return;
		}
		int lead = lead(player);
		Plan now = plan(player, target, lead, 0);
		if (now == null || plan(player, target, lead, 1) != null) {
			return; // nothing reachable yet, or still reachable with one more tick of steering
		}
		this.lead = lead;
		hitTick = now.hitTick();
		sinceSwap = 0;
		state = State.UNGLIDE;
	}

	private void unglide(LocalPlayer player) {
		if (!takeWingsOff(player)) {
			state = State.ARMED; // the source went away between plan and click; nothing changed
			return;
		}
		if (player.isSprinting()) {
			player.setSprinting(false); // vanilla's own sync sends the stop before any strike can go
		}
		stateTicks = 0;
		state = State.CONFIRM;
	}

	private void confirm(LocalPlayer player) {
		if (confirm.is("Fixed")) {
			if (sinceSwap >= fixedTicks.getInt()) {
				enterStrike();
			}
			return;
		}
		if (!player.isFallFlying()) {
			enterStrike();
			return;
		}
		if (sinceSwap > lead + CONFIRM_SLACK) {
			if (wearingElytra(player)) {
				state = State.ARMED; // the server refused the swap and put the wings back itself
				rearm = REARM_TICKS;
			} else {
				enterRescue("the server never ended the glide");
			}
		}
	}

	private void enterStrike() {
		strikeFrom = sinceSwap;
		state = State.STRIKE;
	}

	private void strike(LocalPlayer player) {
		if (target == null || !target.isAlive() || target.isRemoved()) {
			enterRescue("target lost");
			return;
		}
		if (player.onGround() || player.isInLiquid()) {
			enterRescue("landed before the strike");
			return;
		}
		if (sinceSwap > Math.max(hitTick, strikeFrom) + STRIKE_GRACE) {
			enterRescue("missed");
			return;
		}
		if (!canStrike(player)) {
			if (!rescue.is("Off") && landingHurts(player) && hasWindCharge(player)
					&& windChargeLastChance(player)) {
				enterRescue("missed"); // the charge goes this tick or never
			}
			return;
		}
		hit(player);
		// Straight into recovery, this tick: the attack is already on the wire ahead of the swap,
		// and the server resolves the smash against the chestplate before the wings go back on.
		recovery = Recovery.WINGS;
		stateTicks = 0;
		glideAttempts = 0;
		state = State.RECOVER;
	}

	/** Everything a smash needs this tick, in reach and under the crosshair. */
	private boolean canStrike(LocalPlayer player) {
		// The hotbar source has a slot to put back first; the attack hook would drop a hit
		// while somebody else's attack is held.
		if (attacked || HeldAttack.isHeld() || !player.getMainHandItem().is(Items.MACE)) {
			return false;
		}
		if (player.fallDistance <= MaceItem.SMASH_ATTACK_FALL_THRESHOLD
				|| (confirm.is("Server") && player.isFallFlying())) {
			return false;
		}
		double reach = reach(player);
		double strikeReach = reach - reachMargin.get();
		if (target.getBoundingBox().distanceToSqr(player.getEyePosition()) > strikeReach * strikeReach) {
			return false;
		}
		return !requireCrosshair.get() || crosshairOn(player, target, reach);
	}

	/**
	 * The strike, as vanilla's click sends it: the attack, then the punch. Criticals' STOP/START
	 * bracket only when a sprint came back after the swap dropped it.
	 */
	private void hit(LocalPlayer player) {
		boolean sprinting = player.isSprinting();
		striking = true;
		try {
			if (sprinting) {
				player.connection.send(new ServerboundPlayerCommandPacket(player,
						ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
			}
			mc().gameMode.attack(player, target);
			player.connection.send(ServerboundPunchPacket.INSTANCE);
			SwingUtil.attack(player, InteractionHand.MAIN_HAND);
			if (sprinting) {
				player.connection.send(new ServerboundPlayerCommandPacket(player,
						ServerboundPlayerCommandPacket.Action.START_SPRINTING));
			}
		} finally {
			striking = false;
		}
		attacked = true;
	}

	/**
	 * Mandatory after every strike, hit or not: wings on, the glide started a tick later, the
	 * server given the lead to refuse it, then one rocket down a clear line.
	 */
	private void recover(LocalPlayer player) {
		stateTicks++;
		switch (recovery) {
			case WINGS -> {
				if (wearingElytra(player) || putWingsOn(player)) {
					recovery = Recovery.GLIDE; // the start waits for the next tick
					stateTicks = 0;
				} else if (stateTicks > 5) {
					enterRescue("could not put the elytra back on");
				}
			}
			case GLIDE -> {
				if (player.onGround() || player.isInLiquid()) {
					finish();
				} else if (!wearingElytra(player)) {
					recovery = Recovery.WINGS;
				} else if (startGlide(player)) {
					recovery = Recovery.CONFIRM_GLIDE;
					stateTicks = 0;
				} else if (++glideAttempts >= GLIDE_ATTEMPTS) {
					enterRescue("the glide would not start");
				}
			}
			case CONFIRM_GLIDE -> {
				if (player.onGround() || player.isInLiquid()) {
					finish();
				} else if (!player.isFallFlying()) {
					// Refused: the server's flag came back down. Retry within the same budget; past
					// it, the smash already reset the fall to the height of the hit.
					if (++glideAttempts >= GLIDE_ATTEMPTS) {
						finish();
					} else {
						recovery = Recovery.GLIDE;
					}
				} else if (stateTicks > lead) {
					if (rocketAfter.get()) {
						recovery = Recovery.ROCKET;
						stateTicks = 0;
					} else {
						finish();
					}
				}
			}
			case ROCKET -> {
				if (!player.isFallFlying() || stateTicks > ROCKET_WAIT) {
					finish();
				} else if (lookClear(player) && fireRocket(player)) {
					finish();
				}
			}
		}
	}

	/**
	 * After a miss, or anything else that left the dive without a strike. A wind charge whose
	 * window is open goes first, because its window is a tick wide and the wings can wait one; then
	 * the wings — only undoing the swap, so that happens even with Rescue off — then a glide, and a
	 * chat line if nothing can break the fall.
	 */
	private void rescue(LocalPlayer player) {
		stateTicks++;
		boolean rescuing = !rescue.is("Off");
		if (rescuing && !windChargeUsed && landingHurts(player) && hasWindCharge(player)
				&& windChargeWindow(player)) {
			windChargeUsed = throwWindCharge(player);
		}
		if (!wearingElytra(player) && putWingsOn(player)) {
			return; // on this tick; the glide start waits for the next
		}
		if (player.onGround() || player.isInLiquid() || stateTicks > RESCUE_TIMEOUT) {
			finish();
			return;
		}
		if (!rescuing) {
			ChatUtil.info("§7ElytraMace: " + rescueReason + " — rescue is off");
			finish();
			return;
		}
		if (player.isFallFlying() && player.getDeltaMovement().y > -0.5) {
			finish(); // gliding and no longer banking fall: the glide has it from here
			return;
		}
		if (wearingElytra(player) && !player.isFallFlying() && glideAttempts < GLIDE_ATTEMPTS) {
			glideAttempts++;
			startGlide(player);
		}
		if (windChargeUsed || !landingHurts(player) || hasWindCharge(player)) {
			return; // thrown, not needed, or waiting for the ground to come into the charge's window
		}
		if (!player.isFallFlying()) {
			ChatUtil.info("§cElytraMace: " + rescueReason + ", and nothing left to break the fall");
			finish();
		}
		// still gliding but still diving: the player can pull up, and the timeout ends the wait
	}

	private void enterRescue(String reason) {
		rescueReason = reason;
		stateTicks = 0;
		glideAttempts = 0;
		windChargeUsed = false;
		state = State.RESCUE;
	}

	/** The dive is over one way or another; the next one waits out {@link #REARM_TICKS}. */
	private void finish() {
		state = State.IDLE;
		target = null;
		rearm = REARM_TICKS;
	}

	// ---- the planner ---------------------------------------------------------

	/**
	 * The strike a dive would end in if the wings came off after {@code delay} more ticks of the
	 * current glide, or null when it would not end in one worth taking.
	 *
	 * <p>Vanilla physics, both halves: {@code lead} ticks of glide while the server's answer is on
	 * its way, then free fall. The strike is the <em>first</em> free-fall tick in reach, because
	 * that is the tick {@link #strike} will take; it has to carry {@code Min smash fall}, the path
	 * to it has to be clear, and a miss there has to be affordable.
	 *
	 * <p>The rotation is assumed held. The player is steering, and the next tick's plan picks the
	 * new heading up — which is also why committing late beats committing early.
	 */
	private Plan plan(LocalPlayer player, LivingEntity target, int lead, int delay) {
		double reach = reach(player) - reachMargin.get();
		if (reach <= 0.0) {
			return null;
		}
		EntityDimensions standing = player.getDimensions(Pose.STANDING);
		double glidingEye = player.getDimensions(Pose.FALL_FLYING).eyeHeight();
		double gravity = player.getGravity();
		float airDrag = (float) player.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
		double dragXZ = modifiedFriction(0.91f, airDrag);
		double dragY = modifiedFriction(0.98f, airDrag);
		float pitch = player.getXRot();
		Vec3 look = Vec3.directionFromRotation(pitch, player.getYRot());
		AABB targetBox = target.getBoundingBox();
		Vec3 targetStep = new Vec3(target.getX() - target.xo,
				target.onGround() ? 0.0 : target.getY() - target.yo, target.getZ() - target.zo);

		Vec3 pos = player.position();
		Vec3 velocity = player.getDeltaMovement();
		double fall = player.fallDistance;
		int glide = delay + lead;
		// The flag lands before tick lead + 1 travels; Fixed may not strike before its wait either.
		int first = delay + Math.max(lead + 1, confirm.is("Fixed") ? fixedTicks.getInt() : 0);
		Vec3[] path = new Vec3[PLAN_TICKS + 1];
		path[0] = pos;
		for (int tick = 1; tick <= PLAN_TICKS; tick++) {
			if (tick <= glide) {
				// updateFallFlying runs before travel and judges the velocity it arrives with
				if (velocity.y > -0.5 && fall > 1.0) {
					fall = 1.0;
				}
				velocity = glideStep(velocity, look, pitch, gravity);
				pos = pos.add(velocity);
				fall = banked(fall, velocity.y);
			} else {
				// travelInAir moves first and applies gravity and drag after
				pos = pos.add(velocity);
				fall = banked(fall, velocity.y);
				velocity = new Vec3(velocity.x * dragXZ, (velocity.y - gravity) * dragY, velocity.z * dragXZ);
			}
			path[tick] = pos;
			AABB box = targetBox.move(targetStep.scale(tick));
			Vec3 eye = pos.add(0.0, tick <= glide ? glidingEye : standing.eyeHeight(), 0.0);
			if (box.distanceToSqr(eye) > reach * reach) {
				continue;
			}
			// Reach arriving while the server still has us gliding means the swap is late: the
			// dive would pass through its only strike window unable to use it.
			if (tick < first || fall < minSmashFall.get() || !clearPath(player, path, tick)
					|| (requireCrosshair.get() && !clearLine(player, eye, box.getCenter()))
					|| !missAffordable(player, standing, pos, fall, velocity.y)) {
				return null;
			}
			return new Plan(tick - delay);
		}
		return null;
	}

	/**
	 * Whether a miss at the planned strike is one the player can take: the fall it banks, plus the
	 * drop below that point, under {@code Max fall damage} and short of dead — or else a rescue that
	 * can cover it. No ground at all counts as rescuable (the re-glide has the whole sky), never as
	 * survivable. A wind charge only counts if one thrown at the missed tick still bursts in time:
	 * a strike three blocks up leaves exactly that one tick.
	 */
	private boolean missAffordable(LocalPlayer player, EntityDimensions standing, Vec3 pos, double fall,
			double velocityY) {
		double gap = DamageForecast.distanceToGround(player, standing.makeBoundingBox(pos));
		if (gap >= 0.0) {
			float damage = DamageForecast.fallDamage(player, (float) (fall + gap));
			float health = player.getHealth() + player.getAbsorptionAmount();
			if (damage <= maxFallDamage.getFloat() && damage < health - MISS_RESERVE) {
				return true;
			}
		}
		if (rescue.is("Off")) {
			return false;
		}
		double sink = Math.max(0.0, -velocityY);
		return gap < 0.0 || gap >= sink * REGLIDE_TICKS + REGLIDE_ARREST
				|| (hasWindCharge(player) && burstHeight(gap, sink, standing.eyeHeight()) > 0.0);
	}

	/** Every segment of the path up to the strike, clipped against terrain at the feet. */
	private boolean clearPath(LocalPlayer player, Vec3[] path, int until) {
		for (int tick = 1; tick <= until; tick++) {
			Vec3 from = path[tick - 1].add(0.0, 0.1, 0.0);
			Vec3 to = path[tick].add(0.0, 0.1, 0.0);
			if (mc().level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) {
				return false;
			}
		}
		return true;
	}

	private boolean clearLine(LocalPlayer player, Vec3 from, Vec3 to) {
		return mc().level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
	}

	/** Server resets a fall on any upward move; only a descent banks it. */
	private static double banked(double fall, double dy) {
		return dy < 0.0 ? fall - dy : dy > 0.0 ? 0.0 : fall;
	}

	/** {@code LivingEntity.computeModifiedFriction}, which is private. */
	private static double modifiedFriction(float friction, float modifier) {
		return Mth.clamp(1.0f - (1.0f - friction) * modifier, 0.0f, 1.0f);
	}

	/** {@code LivingEntity.updateFallFlyingMovement}, line for line. */
	private static Vec3 glideStep(Vec3 movement, Vec3 look, float pitch, double gravity) {
		float lean = pitch * Mth.DEG_TO_RAD;
		double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
		double moveHorizontal = movement.horizontalDistance();
		double lift = Mth.square(Math.cos(lean));
		movement = movement.add(0.0, gravity * (-1.0 + lift * 0.75), 0.0);
		if (movement.y < 0.0 && lookHorizontal > 0.0) {
			double convert = movement.y * -0.1 * lift;
			movement = movement.add(look.x * convert / lookHorizontal, convert, look.z * convert / lookHorizontal);
		}
		if (lean < 0.0f && lookHorizontal > 0.0) {
			double convert = moveHorizontal * -Mth.sin(lean) * 0.04;
			movement = movement.add(-look.x * convert / lookHorizontal, convert * 3.2,
					-look.z * convert / lookHorizontal);
		}
		if (lookHorizontal > 0.0) {
			movement = movement.add((look.x / lookHorizontal * moveHorizontal - movement.x) * 0.1, 0.0,
					(look.z / lookHorizontal * moveHorizontal - movement.z) * 0.1);
		}
		return movement.multiply(0.99f, 0.98f, 0.99f);
	}

	/**
	 * Glide ticks until the server's answer lands: one per started 50 ms of round trip, plus two —
	 * the server's tick that runs the swap and the one it takes the flag to reach a travel. Measured,
	 * not derived: with no round trip at all the gametest sees the flag drop after the second glide
	 * tick, and a lead one short plans the strike inside the glide.
	 */
	private int lead(LocalPlayer player) {
		PlayerInfo info = mc().getConnection() == null ? null : mc().getConnection().getPlayerInfo(player.getUUID());
		int rtt = info == null ? 0 : Math.max(0, info.getLatency());
		return Mth.ceil(rtt / 50.0) + 2;
	}

	/** The weapon's own reach: {@code attack_range} when it has one, else the interaction attribute. */
	private static double reach(LocalPlayer player) {
		return player.getAttackRangeWith(player.getMainHandItem()).effectiveMaxRange(player);
	}

	/** The crosshair's ray, as far as the reach, on the target's box with no block in front of it. */
	private boolean crosshairOn(LocalPlayer player, LivingEntity target, double reach) {
		Vec3 eye = player.getEyePosition();
		AABB box = target.getBoundingBox();
		if (box.contains(eye)) {
			return true;
		}
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(reach));
		return box.clip(eye, end).map(point -> clearLine(player, eye, point)).orElse(false);
	}

	// ---- the swap ------------------------------------------------------------

	private boolean armable(LocalPlayer player) {
		return player.isFallFlying() && wearingElytra(player) && player.getMainHandItem().is(Items.MACE)
				&& mc().gui.screen() == null && player.containerMenu == player.inventoryMenu
				&& !player.hasEffect(MobEffects.SLOW_FALLING); // resets the fall every tick
	}

	/** The configured source can take the wings off right now. */
	private boolean chestplateReady(LocalPlayer player) {
		return switch (chestplateSource.get()) {
			case "Offhand" -> fitsChest(player.getOffhandItem());
			case "Hotbar" -> hotbarSlot(player, ElytraMace::fitsChest) >= 0;
			default -> menuSlot(player, ElytraMace::fitsChest) >= 0;
		};
	}

	/** What the offhand should hold for the next swap: the chestplate under wings, else the wings. */
	private boolean nextSwapItem(ItemStack stack) {
		LocalPlayer player = mc().player;
		return player != null && (wearingElytra(player) ? fitsChest(stack) : stack.is(Items.ELYTRA));
	}

	private boolean takeWingsOff(LocalPlayer player) {
		return equip(player, ElytraMace::fitsChest, false);
	}

	/** Recovery may reach anywhere in the bag: AutoTotem can have taken the offhand mid-dive. */
	private boolean putWingsOn(LocalPlayer player) {
		return equip(player, stack -> stack.is(Items.ELYTRA), true);
	}

	/**
	 * One swap into the chest slot. The configured source first; with {@code anywhere}, the bag
	 * as a fallback, the way ElytraSwap equips.
	 *
	 * @return whether the chest slot now holds what was wanted
	 */
	private boolean equip(LocalPlayer player, Predicate<ItemStack> wanted, boolean anywhere) {
		if (chestplateSource.is("Offhand") && wanted.test(player.getOffhandItem())) {
			use(player, InteractionHand.OFF_HAND);
		} else if (chestplateSource.is("Hotbar") && hotbarSlot(player, wanted) >= 0) {
			if (select(player, hotbarSlot(player, wanted))) {
				use(player, InteractionHand.MAIN_HAND);
			}
		} else if (chestplateSource.is("Inventory") || anywhere) {
			int slot = menuSlot(player, wanted);
			if (slot >= 0) {
				click(player, slot);
			}
		}
		return wanted.test(player.getItemBySlot(EquipmentSlot.CHEST));
	}

	/**
	 * Starts the glide the way a jump press does, packet included: {@code tryToStartFallFlying}
	 * only flips the local flag, and vanilla sends the command itself. It is sent once even when
	 * the flag is already up, since ElytraFly's takeoff and ElytraRecast flip it without telling
	 * the server.
	 */
	private boolean startGlide(LocalPlayer player) {
		if (player.onGround() || player.isInLiquid() || player.onClimbable() || !wearingElytra(player)) {
			return false;
		}
		if (!player.isFallFlying() && !player.tryToStartFallFlying()) {
			return false;
		}
		player.connection.send(new ServerboundPlayerCommandPacket(player,
				ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		return true;
	}

	/** Boosts only while gliding (1.21.6), which is why recovery confirms the glide before this. */
	private boolean fireRocket(LocalPlayer player) {
		if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
			return use(player, InteractionHand.OFF_HAND);
		}
		int slot = hotbarSlot(player, stack -> stack.is(Items.FIREWORK_ROCKET));
		return slot >= 0 && select(player, slot) && use(player, InteractionHand.MAIN_HAND);
	}

	/** A rocket goes where you look; this says whether that is into terrain. */
	private boolean lookClear(LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		return clearLine(player, eye, eye.add(player.getViewVector(1.0f).scale(ROCKET_CLEARANCE)));
	}

	// ---- the rescue ----------------------------------------------------------

	private boolean landingHurts(LocalPlayer player) {
		return DamageForecast.predictedFallDamage(player) > 0.0f;
	}

	/**
	 * Whether a charge thrown now bursts close enough to catch the thrower. It leaves the eye at
	 * 1.5 blocks a tick faster than the fall and explodes on the ground; the burst has to find
	 * the player still in the air and inside its reach. Caught, the fall counts only below the
	 * point the burst found you ({@code ServerPlayer.onExplosionHit}).
	 */
	private boolean windChargeWindow(LocalPlayer player) {
		double gap = DamageForecast.distanceToGround(player);
		if (gap < 0.0) {
			return false;
		}
		double height = burstHeight(gap, Math.max(0.0, -player.getDeltaMovement().y), player.getEyeHeight());
		return height > 0.0 && height <= WIND_CHARGE_CATCH;
	}

	/**
	 * The window above, and it will be shut a tick from now — the one throw that can still work.
	 * STRIKE hands over to the rescue on this, not on its deadline, because a strike planned three
	 * blocks up is on the ground by then.
	 */
	private boolean windChargeLastChance(LocalPlayer player) {
		if (!windChargeWindow(player)) {
			return false;
		}
		double gap = DamageForecast.distanceToGround(player);
		double sink = Math.max(0.0, -player.getDeltaMovement().y);
		return burstHeight(gap - sink, sink + player.getGravity(), player.getEyeHeight()) <= 0.0;
	}

	/**
	 * How high the thrower still is when a charge thrown now bursts on the ground below: it leaves
	 * the eye {@link #WIND_CHARGE_SPEED} blocks a tick faster than the fall. Zero or less means the
	 * player lands first.
	 */
	private static double burstHeight(double gap, double sink, double eyeHeight) {
		double ticks = (gap + eyeHeight) / (sink + WIND_CHARGE_SPEED);
		return gap - sink * ticks;
	}

	/** MaceCombo's throw, aimed straight down through the rotation owner rather than the camera. */
	private boolean throwWindCharge(LocalPlayer player) {
		InteractionHand hand = InteractionHand.OFF_HAND;
		if (!player.getOffhandItem().is(Items.WIND_CHARGE)) {
			int slot = hotbarSlot(player, stack -> stack.is(Items.WIND_CHARGE));
			if (slot < 0 || !select(player, slot)) {
				return false;
			}
			hand = InteractionHand.MAIN_HAND;
		}
		if (used || !RotationManager.rotateIfAllowed(player.getYRot(), 90.0f, RotationManager.PRIORITY_FUNCTIONAL)) {
			return false;
		}
		return use(player, hand);
	}

	private boolean hasWindCharge(LocalPlayer player) {
		return player.getOffhandItem().is(Items.WIND_CHARGE)
				|| hotbarSlot(player, stack -> stack.is(Items.WIND_CHARGE)) >= 0;
	}

	// ---- one of each per tick ------------------------------------------------

	/** A borrowed hotbar slot, under the click lease; the player's own comes back next tick. */
	private boolean select(LocalPlayer player, int slot) {
		if (player.getInventory().getSelectedSlot() == slot) {
			return true;
		}
		if (slotChanged || !InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)
				|| !InventoryActionCoordinator.selectHotbar(this, slot)) {
			return false;
		}
		slotChanged = true;
		restorePending = true;
		return true;
	}

	/** Releasing the lease is what puts the saved slot back; it is this tick's slot change. */
	private void restoreHotbar() {
		if (!restorePending) {
			return;
		}
		restorePending = false;
		if (InventoryActionCoordinator.owns(this)) {
			InventoryActionCoordinator.release(this);
			slotChanged = true;
		}
	}

	private boolean use(LocalPlayer player, InteractionHand hand) {
		if (used) {
			return false;
		}
		used = true;
		InteractionResult result = mc().gameMode.useItem(player, hand);
		if (result instanceof InteractionResult.Success success
				&& success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
			SwingUtil.interact(player, hand);
		}
		return result.consumesAction();
	}

	/** Inventory's swap: ElytraSwap's pickup move into the chest slot, under the click lease. */
	private boolean click(LocalPlayer player, int menuSlot) {
		AbstractContainerMenu menu = player.inventoryMenu;
		if (player.containerMenu != menu
				|| !InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)) {
			return false;
		}
		boolean done = InventoryActionCoordinator.pickupMove(this, menu, menuSlot, CHEST_MENU_SLOT);
		if (!restorePending) {
			InventoryActionCoordinator.release(this); // a borrowed slot keeps the lease until next tick
		}
		return done;
	}

	// ---- lookups -------------------------------------------------------------

	/** Once per armed glide: a dive that never commits should say why rather than look broken. */
	private void warnUnready() {
		Object owner = OffhandManager.owner();
		if (chestplateSource.is("Offhand") && owner != null && owner != this) {
			ChatUtil.info("§7ElytraMace: the offhand is held by " + owner.getClass().getSimpleName()
					+ ", so there is no chestplate to swap — use the Inventory source, or let it go while gliding");
		} else {
			ChatUtil.info("§7ElytraMace: no chestplate in the " + chestplateSource.get().toLowerCase()
					+ " to swap to");
		}
	}

	private static boolean wearingElytra(LocalPlayer player) {
		return player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
	}

	/** Chest-equippable, swappable and not itself a pair of wings — asked of the item, as ElytraSwap does. */
	private static boolean fitsChest(ItemStack stack) {
		if (stack.isEmpty() || stack.is(Items.ELYTRA)) {
			return false;
		}
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot() == EquipmentSlot.CHEST && equippable.swappable();
	}

	private static int hotbarSlot(LocalPlayer player, Predicate<ItemStack> wanted) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (wanted.test(player.getInventory().getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/** Slots 9..44 of the player's own menu — the bag and the hotbar, never the armour itself. */
	private static int menuSlot(LocalPlayer player, Predicate<ItemStack> wanted) {
		AbstractContainerMenu menu = player.inventoryMenu;
		int last = Math.min(44, menu.slots.size() - 1);
		for (int index = 9; index <= last; index++) {
			if (wanted.test(menu.getSlot(index).getItem())) {
				return index;
			}
		}
		return -1;
	}
}
