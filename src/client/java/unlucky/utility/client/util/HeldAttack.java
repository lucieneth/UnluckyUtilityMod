package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * One attack held back while the movement packets it depends on go out, one position per
 * client tick.
 *
 * <p>Up to 26.2 a module that needed the server to see a fall before a hit — Criticals'
 * hop, the mace smash — sent the whole sequence and then let the attack through, all
 * inside one call. 26.3 takes one position per client tick ({@link MovePacketLimiter}),
 * so the sequence now spans windows and the attack has to wait for its last step.
 * {@code MultiPlayerGameModeMixin} swallows the vanilla attack while a module holds it,
 * and the module replays it through {@link #replay} once its packets are down.
 *
 * <p><b>One held attack at a time.</b> Aura would otherwise fire again mid-sequence and
 * start a second one on top. While an attack is held every other attack is dropped, the
 * same way Criticals' jump always dropped the hits Aura threw mid-rise.
 *
 * <p><b>A replay is not asked again.</b> It passes back through the same HEAD hook, and
 * while it does no holder is consulted — otherwise a mace spoof and Criticals could keep
 * re-holding one hit between them.
 */
public final class HeldAttack {
	/** A sequence is two or three windows long; anything past this has lost its owner. */
	private static final int MAX_AGE = 20;

	private static Object owner;
	private static int age;
	private static boolean replaying;

	private HeldAttack() {
	}

	/** Takes the hold. Fails if another attack is already held. */
	public static boolean hold(Object requester) {
		if (owner != null) {
			return false;
		}
		owner = requester;
		age = 0;
		return true;
	}

	public static boolean isHeld() {
		return owner != null;
	}

	public static boolean isHeldBy(Object requester) {
		return owner != null && owner == requester;
	}

	public static void release(Object requester) {
		if (owner == requester) {
			owner = null;
		}
	}

	/** True while a held attack is passing back through the attack hook. */
	public static boolean isReplaying() {
		return replaying;
	}

	/**
	 * Lets the held attack go: re-issues it, then swings with it. The swing from the
	 * swallowed click already went out on its own and read as a miss.
	 */
	public static void replay(Object requester, Entity target) {
		if (owner != requester) {
			return;
		}
		owner = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) {
			return;
		}
		replaying = true;
		try {
			mc.gameMode.attack(mc.player, target);
			SwingUtil.attack(mc.player, InteractionHand.MAIN_HAND);
		} finally {
			replaying = false;
		}
	}

	/** Backstop: a holder that neither replays nor releases must not eat every later attack. */
	public static void onTickEnd() {
		if (owner != null && ++age > MAX_AGE) {
			owner = null;
		}
	}
}
