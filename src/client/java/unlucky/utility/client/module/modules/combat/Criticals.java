package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.util.HeldAttack;
import unlucky.utility.client.util.MovePacketLimiter;

/**
 * Makes your hits critical hits.
 *
 * <p>26.2 keeps the whole condition in {@code Player.canCriticalAttack}:
 * <pre>fallDistance &gt; 0 &amp;&amp; !onGround() &amp;&amp; !onClimbable() &amp;&amp; !isInWater()
 * &amp;&amp; !isMobilityRestricted() &amp;&amp; !isPassenger() &amp;&amp; target instanceof LivingEntity
 * &amp;&amp; !isSprinting()</pre>
 * gated behind an attack-strength scale above 0.9. Only the first two are things
 * we can manufacture, so the module has two ways to manufacture them and simply
 * refuses when any of the rest is false.
 *
 * <p><b>Jump</b> swallows the attack, hops, and replays it once the fall has
 * started — real state, nothing faked, safe anywhere. <b>Packet</b> sends a hop
 * up and back down (both flagged airborne) so the <i>server</i> accumulates the
 * fall distance while we never leave the ground; it's a movement lie and
 * Grim-class anticheats read it as one.
 *
 * <p><b>Packet takes two ticks since 26.3.</b> The server accepts one position per
 * client tick ({@link MovePacketLimiter}), and only the way down banks a fall — send
 * both in one tick and the second is dropped, leaving an upward hop that crits
 * nothing. So the attack is held ({@link HeldAttack}): up now, down in the next
 * window with the hit right behind it, one tick later than it used to land.
 *
 * <p>When you're <b>already falling</b> — bunny-hopping, or just off a ledge —
 * vanilla would crit the hit on its own and neither mode is needed. All that's in
 * the way then is the sprint.
 *
 * <p><b>Sprint reset.</b> Sprinting cancels crits, which is why PvP players
 * w-tap. Rather than actually dropping the sprint (which costs you the speed),
 * this brackets the hit in packets: {@code STOP_SPRINTING} before, the attack,
 * {@code START_SPRINTING} after. The client flag is never touched, so you keep
 * full speed; the server toggles off and back on inside a single tick, with no
 * position packet in between, so its view of us never disagrees with ours. This
 * is what makes crits work while bhopping or with AutoSprint on — without it,
 * either one cancels every crit you throw.
 *
 * <p>Reference: MeteorDevelopment/meteor-client Criticals (the 0.0625 hop and
 * the wait-for-descent replay are theirs).
 */
public class Criticals extends Module {
	/** Meteor's offset: enough for the server to bank a fall, too small to desync. */
	private static final double PACKET_HOP = 0.0625;
	/** Ticks to wait for a jump to start falling before giving the hit up. */
	private static final int PEAK_TIMEOUT = 20;
	/** Beyond this the replay would just be a rejected out-of-reach packet. */
	private static final double GIVE_UP_RANGE_SQR = 36.0;

	public final ModeSetting mode = add(new ModeSetting("Mode", "How the crit is set up", "Jump", "Jump", "Packet"));
	public final BooleanSetting sprintReset = add(new BooleanSetting("Sprint reset",
			"Packet-spoof a sprint stop around the hit — vanilla won't crit while you sprint. Costs you no speed", true));

	/** The target of a crit we're holding until the fall starts, or the hop comes down. */
	private Entity pending;
	private int waited;
	/** Packet mode's hold: the up hop is out, the down hop waits for the next window. */
	private boolean packetHop;
	private long hopWindow;
	/** Where the up hop left from, so the way down is never shorter than the way up. */
	private double hopFromY;
	/** We've sent STOP_SPRINTING and owe the matching START. */
	private boolean sprintStopped;
	/** Target and timeout for an exact thorns retaliation caused by our crit. */
	private int retaliationTargetId = -1;
	private int retaliationTicks;
	private boolean thornsMotionPending;

	public Criticals() {
		super("Criticals", "Turns your hits into critical hits", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		pending = null;
		HeldAttack.release(this);
		retaliationTargetId = -1;
		retaliationTicks = 0;
		thornsMotionPending = false;
		// never leave the server believing we stopped sprinting
		startSprint();
	}

	/**
	 * MultiPlayerGameModeMixin, at attack HEAD. True means swallow the vanilla
	 * attack — we're holding it until the jump peaks or the hop comes down.
	 *
	 * <p>Never reached for a replay, or while any attack is held: the mixin drops
	 * those, which is what keeps Aura's extra hits from spending the swing mid-rise.
	 */
	public boolean onAttack(Entity target) {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null || mc().level == null || !(target instanceof LivingEntity)) {
			return false;
		}
		if (blocked(player)) {
			return false;
		}
		// an uncharged swing can't crit wherever we put it — don't waste the jump
		if (player.getAttackStrengthScale(0.5f) <= 0.9f) {
			return false;
		}
		// bhop, a ledge, the back half of any jump: vanilla crits this by itself and
		// the sprint is the only thing in the way
		if (!player.onGround() && player.fallDistance > 0.0) {
			armRetaliation(target);
			stopSprint();
			return false;
		}
		if (mode.is("Packet")) {
			// the slot can already be spent this tick (a Blink drain, another module):
			// then there is no hop to be had, and the hit goes out as a plain one
			if (!MovePacketLimiter.isPositionFree() || !HeldAttack.hold(this)) {
				return false;
			}
			hopFromY = player.getY();
			player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), hopFromY + PACKET_HOP,
					player.getZ(), false, player.horizontalCollision));
			pending = target;
			packetHop = true;
			hopWindow = MovePacketLimiter.window();
			waited = 0;
			return true;
		}
		// Jump needs ground to push off, and a cobweb won't give us any
		if (!player.onGround() || mc().level.getBlockState(player.blockPosition()).is(Blocks.COBWEB)
				|| !HeldAttack.hold(this)) {
			return false;
		}
		player.jumpFromGround();
		pending = target;
		packetHop = false;
		waited = 0;
		return true;
	}

	/** MultiPlayerGameModeMixin, at attack RETURN: close the bracket we opened. */
	public void onAttackEnd() {
		startSprint();
	}

	/** The parts of {@code canCriticalAttack} that no mode of ours can fix. */
	private boolean blocked(LocalPlayer player) {
		if (player.onClimbable() || player.isInWater() || player.isInLava()
				|| player.isMobilityRestricted() || player.isPassenger() || player.isFallFlying()) {
			return true;
		}
		// sprinting cancels the crit, and we're only allowed to work around it if asked
		return player.isSprinting() && !sprintReset.get();
	}

	/**
	 * Half of the sprint reset. The server only learns from this packet —
	 * LocalPlayer's own sync wouldn't send it until next tick, long after the
	 * attack. The client flag is deliberately left alone: dropping it is what
	 * would cost us the speed, and we hand the sprint straight back in
	 * {@link #startSprint()} before any position packet can go out.
	 */
	private void stopSprint() {
		LocalPlayer player = mc().player;
		if (!sprintReset.get() || sprintStopped || player == null || !player.isSprinting()) {
			return;
		}
		player.connection.send(new ServerboundPlayerCommandPacket(player,
				ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
		sprintStopped = true;
	}

	/** The other half — hands the sprint back once the attack packet is behind us. */
	private void startSprint() {
		if (!sprintStopped) {
			return;
		}
		sprintStopped = false;
		LocalPlayer player = mc().player;
		// if the sprint genuinely ended in between, the server already agrees
		if (player != null && player.isSprinting()) {
			player.connection.send(new ServerboundPlayerCommandPacket(player,
					ServerboundPlayerCommandPacket.Action.START_SPRINTING));
		}
	}

	/**
	 * Lets the held hit go: once the jump is falling, or once Packet's down hop has a
	 * window of its own to land in.
	 */
	@Override
	public void onTick() {
		// safety net: an unclosed bracket would leave the server thinking we walk
		// while we sprint. Nothing should reach here with one open, but the cost of
		// being wrong is a desync that lasts until the next attack
		startSprint();
		if (retaliationTicks > 0 && --retaliationTicks == 0) {
			retaliationTargetId = -1;
			thornsMotionPending = false;
		}
		if (pending == null) {
			return;
		}
		LocalPlayer player = mc().player;
		// a hop left up is harmless: vanilla's next position brings the server back down
		if (player == null || mc().gameMode == null || !HeldAttack.isHeldBy(this) || !pending.isAlive()
				|| pending.isRemoved() || player.distanceToSqr(pending) > GIVE_UP_RANGE_SQR
				|| ++waited > PEAK_TIMEOUT) {
			pending = null;
			HeldAttack.release(this);
			return;
		}
		if (packetHop) {
			// the way down needs a window of its own, or the server never hears it
			if (MovePacketLimiter.window() == hopWindow || !MovePacketLimiter.isPositionFree()) {
				return;
			}
			player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(),
					Math.min(player.getY(), hopFromY), player.getZ(), false, player.horizontalCollision));
		} else if (player.onGround() || player.fallDistance <= 0.0) {
			// exactly what vanilla is about to check for itself
			return;
		}
		Entity target = pending;
		pending = null;
		// bracket the replay ourselves: the inner HEAD skips us while it replays, but
		// the inner RETURN still runs onAttackEnd and closes what we open here
		stopSprint();
		armRetaliation(target);
		HeldAttack.replay(this, target);
	}

	/**
	 * Damage events precede their motion packet. Arm one correction only when this
	 * is thorns from the exact entity our critical attack just hit.
	 */
	public void onDamage(ClientboundDamageEventPacket packet) {
		LocalPlayer player = mc().player;
		if (!isEnabled() || player == null || retaliationTicks <= 0
				|| packet.entityId() != player.getId() || !packet.sourceType().is(DamageTypes.THORNS)) {
			return;
		}
		if (packet.sourceCauseId() == retaliationTargetId || packet.sourceDirectId() == retaliationTargetId) {
			thornsMotionPending = true;
		}
	}

	/**
	 * While the spoof says airborne, the server can echo a stale vertical velocity
	 * for thorns. Rebuild only that Y component using vanilla's grounded knockback
	 * formula; horizontal knockback and every unrelated motion packet stay intact.
	 */
	public Vec3 correctThornsMotion(Entity entity, Vec3 incoming) {
		LocalPlayer player = mc().player;
		if (!thornsMotionPending || entity != player || player == null) {
			return incoming;
		}
		thornsMotionPending = false;
		retaliationTicks = 0;
		retaliationTargetId = -1;
		double normalY = Math.min(0.4, player.getDeltaMovement().y / 2.0 + 0.4);
		return new Vec3(incoming.x, Math.min(incoming.y, normalY), incoming.z);
	}

	private void armRetaliation(Entity target) {
		retaliationTargetId = target.getId();
		retaliationTicks = 40; // enough for a high-latency round trip, still target-specific
		thornsMotionPending = false;
	}
}
