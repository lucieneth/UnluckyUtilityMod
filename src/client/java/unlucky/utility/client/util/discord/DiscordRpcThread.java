package unlucky.utility.client.util.discord;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import unlucky.utility.client.UnluckyClientMod;

/**
 * Owns the one {@link DiscordIpc} and every byte that goes down it, so the render
 * thread never touches a socket. The module hands it a {@link Presence} whenever
 * the state changes; this thread notices and pushes it.
 *
 * <p>Discord being closed is the normal case, not an error — this reconnects on a
 * slow retry forever and stays quiet about it, because someone who never opens
 * Discord shouldn't get log spam for it.
 */
public final class DiscordRpcThread {
	/** Discord rate-limits presence updates to one per ~15s; stay well clear. */
	private static final long PUSH_INTERVAL_MS = 5_000L;
	private static final long RETRY_INTERVAL_MS = 30_000L;
	private static final long IDLE_SLEEP_MS = 1_000L;

	/** What the module wants shown. Null means "nothing to show yet". */
	private final AtomicReference<Presence> wanted = new AtomicReference<>();
	private final String clientId;
	private final Thread thread;
	private volatile boolean running = true;

	/** The last presence we actually sent, so we only push real changes. */
	private Presence sent;
	private long lastPush;
	private long nextRetry;
	private DiscordIpc ipc;

	public DiscordRpcThread(String clientId) {
		this.clientId = clientId;
		this.thread = new Thread(this::run, "Unlucky Discord RPC");
		this.thread.setDaemon(true); // never hold the game open
		this.thread.start();
	}

	/** Called from the game thread; just parks the value for the IPC thread. */
	public void set(Presence presence) {
		wanted.set(presence);
	}

	/** Stops the thread and drops the presence from Discord. */
	public void shutdown() {
		running = false;
		thread.interrupt();
	}

	private void run() {
		try {
			while (running) {
				try {
					pump();
					Thread.sleep(IDLE_SLEEP_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (Exception e) {
					// a dropped pipe lands here: bin the connection and let the retry take over
					drop();
				}
			}
		} finally {
			drop();
		}
	}

	private void pump() throws Exception {
		Presence presence = wanted.get();
		if (presence == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (ipc == null) {
			if (now < nextRetry) {
				return;
			}
			nextRetry = now + RETRY_INTERVAL_MS;
			ipc = DiscordIpc.open();
			if (ipc == null) {
				return; // Discord isn't running; try again later
			}
			JsonObject handshake = new JsonObject();
			handshake.addProperty("v", 1);
			handshake.addProperty("client_id", clientId);
			ipc.send(DiscordIpc.OP_HANDSHAKE, handshake.toString());
			sent = null; // force the first push through
			UnluckyClientMod.LOGGER.info("Discord RPC connected");
		}
		if (presence.equals(sent) || now - lastPush < PUSH_INTERVAL_MS) {
			return;
		}
		ipc.send(DiscordIpc.OP_FRAME, activityFrame(presence));
		sent = presence;
		lastPush = now;
	}

	/** {@code SET_ACTIVITY} — the only command we ever send. */
	private String activityFrame(Presence presence) {
		JsonObject activity = new JsonObject();
		if (presence.details() != null) {
			activity.addProperty("details", presence.details());
		}
		if (presence.state() != null) {
			activity.addProperty("state", presence.state());
		}
		if (presence.startedAt() > 0L) {
			JsonObject timestamps = new JsonObject();
			timestamps.addProperty("start", presence.startedAt());
			activity.add("timestamps", timestamps);
		}
		JsonObject assets = new JsonObject();
		assets.addProperty("large_image", presence.largeImage());
		assets.addProperty("large_text", presence.largeText());
		activity.add("assets", assets);

		JsonObject args = new JsonObject();
		args.addProperty("pid", DiscordIpc.pid());
		args.add("activity", activity);

		JsonObject frame = new JsonObject();
		frame.addProperty("cmd", "SET_ACTIVITY");
		frame.add("args", args);
		frame.addProperty("nonce", UUID.randomUUID().toString());
		return frame.toString();
	}

	private void drop() {
		if (ipc != null) {
			ipc.close();
			ipc = null;
		}
		sent = null;
	}

	/**
	 * One rendered presence. A record so the "did anything actually change?"
	 * check is just equals, and so the game thread can hand it over immutably.
	 */
	public record Presence(String details, String state, long startedAt, String largeImage, String largeText) {
	}
}
