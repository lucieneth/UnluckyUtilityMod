package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;

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
 * up and straight back down (both flagged airborne) so the <i>server</i>
 * accumulates the fall distance while we never leave the ground; instant, but
 * it's a movement lie and Grim-class anticheats read it as one.
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

	/** The target of a jump-crit we're holding until the fall starts. */
	private Entity pending;
	private int waited;
	/** Set while we re-enter attack() ourselves, so we don't intercept our own hit. */
	private boolean replaying;
	/** We've sent STOP_SPRINTING and owe the matching START. */
	private boolean sprintStopped;

	public Criticals() {
		super("Criticals", "Turns your hits into critical hits", Category.COMBAT);
	}

	@Override
	protected void onDisable() {
		pending = null;
		// never leave the server believing we stopped sprinting
		startSprint();
	}

	/**
	 * MultiPlayerGameModeMixin, at attack HEAD. True means swallow the vanilla
	 * attack — we're holding it until the jump peaks.
	 */
	public boolean onAttack(Entity target) {
		if (replaying) {
			return false;
		}
		// a crit is already in flight: drop the extra hits Aura would land mid-rise,
		// which would otherwise spend the swing before the fall ever starts
		if (pending != null) {
			return true;
		}
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
			stopSprint();
			return false;
		}
		if (mode.is("Packet")) {
			stopSprint();
			hop(player);
			// vanilla's interact packet goes out right behind ours, now crit-flagged
			return false;
		}
		// Jump needs ground to push off, and a cobweb won't give us any
		if (!player.onGround() || mc().level.getBlockState(player.blockPosition()).is(Blocks.COBWEB)) {
			return false;
		}
		player.jumpFromGround();
		pending = target;
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

	/** Up and straight back down, both airborne: a fall the server sees and we don't take. */
	private void hop(LocalPlayer player) {
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		boolean collided = player.horizontalCollision;
		player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + PACKET_HOP, z, false, collided));
		player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, collided));
	}

	/** Jump mode: hold the hit until the fall actually starts, then let it go. */
	@Override
	public void onTick() {
		// safety net: an unclosed bracket would leave the server thinking we walk
		// while we sprint. Nothing should reach here with one open, but the cost of
		// being wrong is a desync that lasts until the next attack
		startSprint();
		if (pending == null) {
			return;
		}
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null || !pending.isAlive() || pending.isRemoved()
				|| player.distanceToSqr(pending) > GIVE_UP_RANGE_SQR || ++waited > PEAK_TIMEOUT) {
			pending = null;
			return;
		}
		// exactly what vanilla is about to check for itself
		if (player.onGround() || player.fallDistance <= 0.0) {
			return;
		}
		Entity target = pending;
		pending = null;
		// bracket the replay ourselves: the inner HEAD is a no-op while replaying, but
		// the inner RETURN still runs onAttackEnd and closes what we open here
		stopSprint();
		replaying = true;
		try {
			mc().gameMode.attack(player, target);
			// swing with the hit so the pair lands together; the swing from the
			// swallowed click already read as a miss
			player.swing(InteractionHand.MAIN_HAND);
		} finally {
			replaying = false;
		}
	}
}
