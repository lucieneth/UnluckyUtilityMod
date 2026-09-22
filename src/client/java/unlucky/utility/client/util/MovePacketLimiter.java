package unlucky.utility.client.util;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jspecify.annotations.Nullable;

/**
 * One positional move packet per client tick, because 26.3 disconnects you for two.
 *
 * <p>{@code ServerGamePacketListenerImpl.handleMovePlayer} gained a
 * {@code receivedPositionThisTick} flag: the second packet carrying a position inside
 * one tick is not clamped or ignored, it is a
 * {@code multiplayer.disconnect.invalid_player_movement} kick. Vanilla itself only ever
 * sends one — {@code LocalPlayer.sendPosition} runs once per tick — so this was always
 * the contract; up to 26.2 the server simply tolerated extra ones, and several modules
 * (Criticals' micro-hops, Phase, EventlessFly, the mace packets) relied on that.
 *
 * <p><b>The tick is the client's, and the boundary is a packet.</b> The server clears the
 * flag in {@code handleClientTickEnd}, i.e. when it reads the
 * {@link ServerboundClientTickEndPacket} that {@code Minecraft.tick()} sends near the end
 * of every tick. So the window reopens here when that packet goes out, not at
 * {@code END_CLIENT_TICK}: that event fires <em>after</em> the tick-end packet, and a window
 * that reopened there counted everything sent in between — a Blink drain, any module's
 * {@code onTick} — against the tick the server had already closed. The two disagreed by
 * exactly that sliver, and a drain followed by vanilla's next position was a kick.
 * {@link #window()} numbers the windows for callers that must not put two of their own
 * positions in one.
 *
 * <p><b>There are two ways out of this client, and both must be counted here.</b>
 * Ordinary sends go through {@code ClientCommonPacketListenerImpl.send}; a
 * {@link PacketQueueManager} flush writes straight to the {@code Connection} to keep
 * captured packets byte-identical. Counting only the first is what a first attempt at
 * this did, and it still got kicked: a drained position and vanilla's own position
 * landed in the same tick, each believing it was the only one. So the slot is claimed
 * through {@link #tryClaimPosition()} rather than tracked by either caller.
 *
 * <p>The cost: a module that wants two positions in a tick now lands only the first. The
 * ones that need a sequence (Criticals' hop, the mace fall) spread it over consecutive
 * windows instead — see {@link HeldAttack}.
 */
public final class MovePacketLimiter {
	private static boolean positionSentThisTick;
	private static long window;

	/**
	 * Claims this tick's one positional-move slot.
	 *
	 * @return whether the caller may send a packet carrying a position
	 */
	public static synchronized boolean tryClaimPosition() {
		if (positionSentThisTick) {
			return false;
		}
		positionSentThisTick = true;
		return true;
	}

	/**
	 * Whether a position sent now would reach the server, without claiming the slot.
	 *
	 * <p>For a module about to send through the ordinary path, which {@link #filter}
	 * claims on its behalf — claiming here as well would make it drop its own packet.
	 */
	public static synchronized boolean isPositionFree() {
		return !positionSentThisTick;
	}

	/** Counts the server's tick windows; it advances every time one closes. */
	public static synchronized long window() {
		return window;
	}

	/**
	 * Filters one outgoing packet. Returns the packet to send, or {@code null} to drop it.
	 *
	 * <p>A surplus packet is not dropped outright when it can be salvaged: a PosRot still
	 * carries a look the sender wanted applied, so it is downgraded to the Rot the server
	 * does accept. A position-only surplus has nothing left to say and goes.
	 *
	 * <p>The tick-end packet closes the window as it passes. Anything else that is not a
	 * positional move passes straight through.
	 */
	public static @Nullable Packet<?> filter(Packet<?> packet) {
		if (packet instanceof ServerboundClientTickEndPacket) {
			reset();
			return packet;
		}
		if (!(packet instanceof ServerboundMovePlayerPacket move) || !move.hasPosition()) {
			return packet;
		}
		if (tryClaimPosition()) {
			return packet;
		}
		if (move.hasRotation()) {
			return new ServerboundMovePlayerPacket.Rot(move.getYRot(0.0F), move.getXRot(0.0F),
					move.isOnGround(), move.horizontalCollision());
		}
		return null;
	}

	/**
	 * Opens the next window. Called as the tick-end packet goes out, and on joining a
	 * server, whose fresh listener starts with the flag clear.
	 */
	public static synchronized void reset() {
		positionSentThisTick = false;
		window++;
	}

	private MovePacketLimiter() {
	}
}
