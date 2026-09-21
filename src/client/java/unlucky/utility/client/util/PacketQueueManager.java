package unlucky.utility.client.util;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

/**
 * The single owner and safety boundary for outgoing gameplay-packet buffering.
 *
 * <p>The allowlist is deliberately concrete. It is safer for a new packet to pass through
 * than for a broad package/name rule to accidentally retain keepalive, teleport confirm,
 * chat signing, configuration or resource-pack traffic. Queued packets are immutable packet
 * objects and flush through the underlying {@code Connection}, bypassing the listener mixin:
 * rotation and sequence fields must be exactly the values captured when the action happened.
 */
public final class PacketQueueManager {
	public enum QueueMode {
		MOVEMENT_ONLY,
		MOVEMENT_AND_ACTIONS
	}

	private static final ArrayDeque<Packet<?>> queue = new ArrayDeque<>();
	/** What a flush has handed over but the tick pacing has not yet sent. */
	private static final ArrayDeque<Packet<?>> draining = new ArrayDeque<>();
	private static Object owner;
	private static QueueMode mode = QueueMode.MOVEMENT_ONLY;
	private static int maxTicks;
	private static int maxPackets;
	private static int ticks;
	private static Runnable onLimit;
	private static Runnable onCorrection;
	private static boolean limitPending;
	private static boolean correctionPending;
	private static Vec3 serverPosition;
	private static WeakReference<ClientLevel> levelRef = new WeakReference<>(null);
	private static WeakReference<ClientPacketListener> connectionRef = new WeakReference<>(null);

	private PacketQueueManager() {
	}

	/** Starts a lease, or refreshes the same owner's limits and callbacks. */
	public static synchronized boolean begin(Object requester, QueueMode requestedMode,
			int requestedMaxTicks, int requestedMaxPackets, Runnable limitAction,
			Runnable correctionAction) {
		if (requester == null || requestedMode == null || (owner != null && owner != requester)) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.getConnection() == null) {
			return false;
		}
		if (owner == null) {
			owner = requester;
			queue.clear();
			ticks = 0;
			limitPending = false;
			correctionPending = false;
			serverPosition = mc.player == null ? null : mc.player.position();
			levelRef = new WeakReference<>(mc.level);
			connectionRef = new WeakReference<>(mc.getConnection());
		}
		mode = requestedMode;
		maxTicks = Math.max(1, requestedMaxTicks);
		maxPackets = Math.max(1, requestedMaxPackets);
		onLimit = limitAction;
		onCorrection = correctionAction;
		return true;
	}

	public static synchronized boolean owns(Object requester) {
		return requester != null && owner == requester;
	}

	public static synchronized Object owner() {
		return owner;
	}

	public static synchronized int queuedCount() {
		return queue.size();
	}

	public static synchronized Vec3 serverPosition() {
		return serverPosition;
	}

	/** Called by the existing outgoing-packet mixin after rotation rewriting. */
	public static synchronized boolean intercept(Packet<?> packet) {
		if (owner == null || !isQueueable(packet, mode)) {
			return false;
		}
		if (queue.size() >= maxPackets) {
			limitPending = true;
			return true; // hard cap: do not leak the overflow packet live
		}
		queue.addLast(packet);
		return true;
	}

	/**
	 * The complete allowlist. Anything not named here is protocol-critical by default and
	 * remains live, including every keepalive/chat/configuration/teleport-confirm packet.
	 */
	public static boolean isQueueable(Packet<?> packet, QueueMode requestedMode) {
		if (packet instanceof ServerboundMovePlayerPacket) {
			return true;
		}
		return requestedMode == QueueMode.MOVEMENT_AND_ACTIONS
				&& (packet instanceof ServerboundPlayerActionPacket
						|| packet instanceof ServerboundPlayerCommandPacket
						|| packet instanceof ServerboundInteractPacket
						|| packet instanceof ServerboundPunchPacket
						|| packet instanceof ServerboundUseItemPacket
						|| packet instanceof ServerboundUseItemOnPacket
						|| packet instanceof ServerboundSetCarriedItemPacket);
	}

	/**
	 * Releases the captured objects in order, without running outgoing transforms a
	 * second time.
	 *
	 * <p><b>Paced, not burst, since 26.3.</b> The server now disconnects on a second
	 * positional move packet inside one tick, so a buffered path can no longer be
	 * handed over all at once — that is the one shape of traffic this class exists to
	 * produce, and on 26.3 it is a kick rather than a catch-up. The queue therefore
	 * drains on the client tick instead: one positional packet per tick, with the
	 * non-positional ones that follow it going out in the same tick so an action still
	 * lands at the position it was performed from.
	 *
	 * <p>The packets, their order and their contents are unchanged. What changes is
	 * that a flush of n positions now takes n ticks to arrive rather than one, so a
	 * blink of 40 ticks takes 40 ticks to catch up. Pacing it is the only way to send
	 * the path at all; collapsing to the final position would arrive instantly and
	 * throw away the path, which is the thing being bought.
	 */
	public static void flush(Object requester) {
		synchronized (PacketQueueManager.class) {
			if (owner != requester) {
				return;
			}
			draining.addAll(queue);
			clearLease();
		}
		// No immediate drain: onTickEnd owns the pacing, and draining here as well put
		// two positions on the wire in the tick a flush happened.
	}

	/**
	 * Sends packets up to and including one that carries a position, then stops.
	 *
	 * <p>Leading non-positional packets go first — they were captured before the move
	 * and belong with it — and trailing ones are left for the next call, because a
	 * packet captured after a move belongs at that move's position, not the previous.
	 *
	 * <p>The position is claimed from {@link MovePacketLimiter} rather than assumed:
	 * vanilla sends its own position every tick through the ordinary path, so a drain
	 * that simply took one per tick of its own still put two on the wire and got the
	 * player kicked. When the slot is already gone the whole batch waits, because the
	 * actions in front of the move are the ones that must land at the old position.
	 */
	private static void drainOnePosition() {
		List<Packet<?>> batch = new ArrayList<>();
		synchronized (PacketQueueManager.class) {
			if (draining.isEmpty()) {
				return;
			}
			if (!MovePacketLimiter.tryClaimPosition()) {
				return;
			}
			while (!draining.isEmpty()) {
				Packet<?> packet = draining.peek();
				boolean carriesPosition = packet instanceof ServerboundMovePlayerPacket move
						&& move.hasPosition();
				batch.add(draining.poll());
				if (carriesPosition) {
					break;
				}
			}
		}
		if (batch.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener listener = mc.getConnection();
		if (listener == null || !listener.getConnection().isConnected()) {
			synchronized (PacketQueueManager.class) {
				draining.clear();
			}
			return;
		}
		for (Packet<?> packet : batch) {
			listener.getConnection().send(packet);
		}
	}

	/** Whether a flush is still handing packets over. */
	public static synchronized boolean isDraining() {
		return !draining.isEmpty();
	}

	public static synchronized void discard(Object requester) {
		if (owner == requester) {
			clearLease();
		}
	}

	/** Panic/disconnect backstop. Never flushes, and abandons any drain in progress. */
	public static synchronized void discardAll() {
		clearLease();
		draining.clear();
		serverPosition = null;
	}

	/** TAIL of the vanilla correction handler: the player's position is now authoritative. */
	public static synchronized void recordServerPosition(Vec3 position) {
		serverPosition = position;
		if (owner != null) {
			correctionPending = true;
		}
	}

	/** Identity cleanup, hard tick cap and callbacks all run on the client tick thread. */
	public static void onTickEnd() {
		// One positional packet per tick: see flush(). Runs before the lease bookkeeping
		// so a drain that outlives its owner still finishes.
		drainOnePosition();
		Runnable callback = null;
		Object callbackOwner = null;
		boolean mustResolve = false;
		synchronized (PacketQueueManager.class) {
			Minecraft mc = Minecraft.getInstance();
			if (owner == null) {
				return;
			}
			if (mc.level == null || mc.getConnection() == null
					|| levelRef.get() != mc.level || connectionRef.get() != mc.getConnection()) {
				clearLease();
				serverPosition = null;
				return;
			}
			ticks++;
			if (ticks >= maxTicks) {
				limitPending = true;
			}
			callbackOwner = owner;
			if (correctionPending) {
				correctionPending = false;
				callback = onCorrection;
				mustResolve = true;
			} else if (limitPending) {
				limitPending = false;
				callback = onLimit;
				mustResolve = true;
			}
		}
		try {
			if (callback != null) {
				callback.run();
			}
		} finally {
			// A missing or broken owner callback cannot leave protocol traffic buffered forever.
			synchronized (PacketQueueManager.class) {
				if (mustResolve && owner == callbackOwner) {
					clearLease();
				}
			}
		}
	}

	private static void clearLease() {
		queue.clear();
		owner = null;
		mode = QueueMode.MOVEMENT_ONLY;
		maxTicks = 0;
		maxPackets = 0;
		ticks = 0;
		onLimit = null;
		onCorrection = null;
		limitPending = false;
		correctionPending = false;
		levelRef = new WeakReference<>(null);
		connectionRef = new WeakReference<>(null);
	}
}
