package unlucky.utility.client.util;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jspecify.annotations.Nullable;

/**
 * One positional move packet per tick, because 26.3 disconnects you for two.
 *
 * <p>{@code ServerGamePacketListenerImpl.handleMovePlayer} gained a
 * {@code receivedPositionThisTick} flag: the second packet carrying a position inside
 * one server tick is not clamped or ignored, it is a
 * {@code multiplayer.disconnect.invalid_player_movement} kick. Vanilla itself only ever
 * sends one — {@code LocalPlayer.sendPosition} runs once per tick — so this was always
 * the contract; up to 26.2 the server simply tolerated extra ones, and several modules
 * (Criticals' micro-hops, Phase, EventlessFly, the mace packets) relied on that.
 *
 * <p><b>There are two ways out of this client, and both must be counted here.</b>
 * Ordinary sends go through {@code ClientCommonPacketListenerImpl.send}; a
 * {@link PacketQueueManager} flush writes straight to the {@code Connection} to keep
 * captured packets byte-identical. Counting only the first is what a first attempt at
 * this did, and it still got kicked: a drained position and vanilla's own position
 * landed in the same tick, each believing it was the only one. So the slot is claimed
 * through {@link #tryClaimPosition()} rather than tracked by either caller.
 *
 * <p>The cost is honest and worth stating: a module that wants two positions in a tick
 * now lands only the first. That is a behaviour change from 26.2 for those modules, and
 * the alternative is being kicked.
 */
public final class MovePacketLimiter {
	private static boolean positionSentThisTick;

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
	 * Filters one outgoing packet. Returns the packet to send, or {@code null} to drop it.
	 *
	 * <p>A surplus packet is not dropped outright when it can be salvaged: a PosRot still
	 * carries a look the sender wanted applied, so it is downgraded to the Rot the server
	 * does accept. A position-only surplus has nothing left to say and goes.
	 *
	 * <p>Anything that is not a positional move passes straight through.
	 */
	public static @Nullable Packet<?> filter(Packet<?> packet) {
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

	/** Opens the next window. Called once per client tick from {@code UnluckyClient.tick()}. */
	public static synchronized void onTickEnd() {
		positionSentThisTick = false;
	}

	private MovePacketLimiter() {
	}
}
