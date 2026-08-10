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
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
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
						|| packet instanceof ServerboundSwingPacket
						|| packet instanceof ServerboundUseItemPacket
						|| packet instanceof ServerboundUseItemOnPacket
						|| packet instanceof ServerboundSetCarriedItemPacket);
	}

	/** Sends the captured objects in order, without running outgoing transforms a second time. */
	public static void flush(Object requester) {
		List<Packet<?>> packets;
		synchronized (PacketQueueManager.class) {
			if (owner != requester) {
				return;
			}
			packets = new ArrayList<>(queue);
			clearLease();
		}
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener listener = mc.getConnection();
		if (listener == null || !listener.getConnection().isConnected()) {
			return;
		}
		for (Packet<?> packet : packets) {
			listener.getConnection().send(packet);
		}
	}

	public static synchronized void discard(Object requester) {
		if (owner == requester) {
			clearLease();
		}
	}

	/** Panic/disconnect backstop. Never flushes. */
	public static synchronized void discardAll() {
		clearLease();
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
