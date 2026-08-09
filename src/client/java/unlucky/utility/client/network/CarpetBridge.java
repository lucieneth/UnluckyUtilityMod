package unlucky.utility.client.network;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Carpet-side inventory writes, reached over a negotiated play channel.
 *
 * <p>The write lands where {@code inventory_set} would put it, without the command
 * system in the path — so there is no 32767 cap, no chunking and no spam throttle.
 * Whether the channel is there at all is decided during Fabric's registry handshake,
 * which is why every caller checks {@link #available()} first and keeps a command
 * route for when it is not.
 */
public final class CarpetBridge {

	private CarpetBridge() {
	}

	/** Register the payload type once during client initialization. */
	public static void register() {
		// A single saved container can exceed Fabric's normal payload cap.
		PayloadTypeRegistry.serverboundPlay().registerLarge(
				PlacePayload.TYPE, PlacePayload.STREAM_CODEC, 8 * 1024 * 1024);
	}

	/** True when the channel came back from the handshake. */
	public static boolean available() {
		return ClientPlayNetworking.canSend(PlacePayload.TYPE);
	}

	/** Send a batch of writes after the caller has checked availability. */
	public static void place(List<PlacePayload.Entry> entries) {
		ClientPlayNetworking.send(new PlacePayload(entries));
	}

	/** Send one write in its own packet, the safe unit for a large stack. */
	public static void place(PlacePayload.Entry entry) {
		ClientPlayNetworking.send(new PlacePayload(List.of(entry)));
	}
}
