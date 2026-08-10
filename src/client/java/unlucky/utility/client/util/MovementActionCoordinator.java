package unlucky.utility.client.util;

import java.util.function.UnaryOperator;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Resolves synthetic local-player movement once, after every module has ticked.
 *
 * <p>Movement modules used to win by registration order: whichever one happened to call
 * {@code setDeltaMovement} last silently erased the earlier decision. That is especially
 * dangerous for a rescue — a strafe or long-jump tick must not put the downward velocity
 * back after AntiVoid removed it. Callers request every tick; the highest priority transforms
 * the velocity that remains after ordinary module ticks, and equal priority keeps the first
 * owner so peers cannot alternate control every tick.
 */
public final class MovementActionCoordinator {
	public static final int PRIORITY_TRAVEL = 40;
	public static final int PRIORITY_DODGE = 70;
	public static final int PRIORITY_ANTI_VOID = 100;

	private static Object owner;
	private static int priority = Integer.MIN_VALUE;
	private static UnaryOperator<Vec3> action;

	private MovementActionCoordinator() {
	}

	/** Requests this tick's final velocity transform. Returns whether the request currently wins. */
	public static boolean request(Object requester, int requestedPriority, UnaryOperator<Vec3> requestedAction) {
		if (requester == null || requestedAction == null) {
			return false;
		}
		if (owner == null || requester == owner || requestedPriority > priority) {
			owner = requester;
			priority = requestedPriority;
			action = requestedAction;
		}
		return owner == requester;
	}

	public static boolean owns(Object requester) {
		return requester != null && owner == requester;
	}

	/** Drops a pending request without disturbing a higher-priority owner. */
	public static void release(Object requester) {
		if (owner == requester) {
			reset();
		}
	}

	/** Applies the winner to the latest velocity, then expires every request. */
	public static void onTickEnd() {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (action == null || mc.player == null) {
				return;
			}
			Vec3 resolved = action.apply(mc.player.getDeltaMovement());
			if (resolved != null && Double.isFinite(resolved.x)
					&& Double.isFinite(resolved.y) && Double.isFinite(resolved.z)) {
				mc.player.setDeltaMovement(resolved);
			}
		} finally {
			reset();
		}
	}

	/** Panic/disconnect backstop: a request must never survive into another tick or world. */
	public static void reset() {
		owner = null;
		priority = Integer.MIN_VALUE;
		action = null;
	}
}
