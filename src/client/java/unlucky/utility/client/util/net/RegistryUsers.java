package unlucky.utility.client.util.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import unlucky.utility.client.UnluckyClientMod;

/**
 * "Who else here is an Unlucky user, and what cape are they wearing?"
 *
 * <p>The OptiFine-capes model: a public, batched, cached lookup — not a realtime
 * connection. Everyone in the tab list is asked about in <b>one</b> request.
 *
 * <p>The two TTLs matter, and they're very different on purpose:
 * <ul>
 *   <li><b>Users</b> refresh every {@link #USER_TTL_MS} — they can swap capes at
 *       any moment, and a cape that takes minutes to appear feels broken.</li>
 *   <li><b>Non-users</b> are cached for {@link #MISS_TTL_MS}. On a full server
 *       almost everyone is a miss, they are never going to spontaneously become a
 *       user, and re-asking about 100 strangers every few seconds would be the
 *       single most wasteful thing this client does.</li>
 * </ul>
 * That asymmetry is the whole reason the lookup stays cheap while capes stay live.
 */
public final class RegistryUsers {
	private static final long USER_TTL_MS = 20 * 1000;
	/** First recheck of an unknown player — they may simply not have signed in yet. */
	private static final long MISS_BASE_MS = 15 * 1000;
	/** Where the backoff settles for someone who is plainly just a stranger. */
	private static final long MISS_MAX_MS = 5 * 60 * 1000;
	/** How long to sit on a failed fetch, so an outage doesn't spin. */
	private static final long BACKOFF_MS = 60 * 1000;
	private static final int BATCH_LIMIT = 100;

	private static final Set<UUID> USERS = new HashSet<>();
	/** uuid -> cape id. A user with no entry here is a user wearing no cape. */
	private static final Map<UUID, String> CAPES = new HashMap<>();
	/** uuid -> the marker color they chose for themselves (0xRRGGBB). */
	private static final Map<UUID, Integer> COLORS = new HashMap<>();
	private static final Map<UUID, Long> CHECKED = new HashMap<>();
	/** Consecutive "not a user" answers, which is what the miss backoff is built on. */
	private static final Map<UUID, Integer> MISSES = new HashMap<>();
	private static final Set<UUID> IN_FLIGHT = new HashSet<>();
	private static long backoffUntil;
	/** The connection the cache belongs to — a server hop invalidates everything. */
	private static ClientPacketListener connection;

	private RegistryUsers() {
	}

	public static synchronized boolean isUser(UUID uuid) {
		return USERS.contains(uuid);
	}

	/** The registry cape id ({@code group:name}) for a player, or null. */
	public static synchronized String capeOf(UUID uuid) {
		return CAPES.get(uuid);
	}

	/** The marker color this player picked (0xRRGGBB), or null if they never set one. */
	public static synchronized Integer colorOf(UUID uuid) {
		return COLORS.get(uuid);
	}

	public static synchronized void clear() {
		USERS.clear();
		CAPES.clear();
		COLORS.clear();
		CHECKED.clear();
		MISSES.clear();
		IN_FLIGHT.clear();
	}

	/** Finds everyone we haven't asked about recently and asks about them at once. */
	public static void poll() {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener current = mc.getConnection();
		if (current == null || System.currentTimeMillis() < backoffUntil) {
			return;
		}
		List<UUID> wanted = new ArrayList<>();
		synchronized (RegistryUsers.class) {
			if (current != connection) {
				// different server (or a reconnect): none of the cached answers are ours
				connection = current;
				clear();
			}
			long now = System.currentTimeMillis();
			Set<UUID> online = new HashSet<>();
			for (PlayerInfo info : current.getOnlinePlayers()) {
				online.add(info.getProfile().id());
			}
			// drop everyone who left: a rejoin then reads them fresh rather than
			// resurrecting a cape they may have changed while they were away
			CHECKED.keySet().retainAll(online);
			USERS.retainAll(online);
			CAPES.keySet().retainAll(online);
			COLORS.keySet().retainAll(online);
			MISSES.keySet().retainAll(online);

			for (UUID uuid : online) {
				if (IN_FLIGHT.contains(uuid)) {
					continue;
				}
				Long checked = CHECKED.get(uuid);
				if (checked == null || now - checked > ttlFor(uuid)) {
					wanted.add(uuid);
					if (wanted.size() >= BATCH_LIMIT) {
						break;
					}
				}
			}
			if (wanted.isEmpty()) {
				return;
			}
			IN_FLIGHT.addAll(wanted);
		}
		fetch(wanted);
	}

	/**
	 * How long an answer about this player stays good.
	 *
	 * <p>Users: {@link #USER_TTL_MS}, because they can swap cape or color any moment.
	 *
	 * <p>Non-users: a <b>backoff</b>, not a flat TTL, and that distinction is the whole
	 * fix. A player who just joined may simply not have finished signing in yet — cache
	 * that as "stranger" for five minutes and they stay invisible for five minutes, on
	 * every screen, which is exactly the bug this replaces. So we recheck quickly at
	 * first (15s, 30s, 60s …) and only settle into the cheap 5-minute cadence once
	 * someone has repeatedly proven to be nobody we know.
	 */
	private static synchronized long ttlFor(UUID uuid) {
		if (USERS.contains(uuid)) {
			return USER_TTL_MS;
		}
		int misses = MISSES.getOrDefault(uuid, 0);
		long ttl = MISS_BASE_MS << Math.min(misses, 5); // 15s, 30s, 1m, 2m, 4m, cap
		return Math.min(ttl, MISS_MAX_MS);
	}

	/**
	 * Forgets what we knew about a player, so the next poll re-reads them. Called
	 * after our own sign-in: we've just become a user, and our own cached "not a
	 * user" answer from a second ago would otherwise hide our own marker from us.
	 */
	public static synchronized void invalidate(UUID uuid) {
		CHECKED.remove(uuid);
		MISSES.remove(uuid);
	}

	private static void fetch(List<UUID> uuids) {
		new Thread(() -> {
			try {
				JsonObject result = UnluckyApi.users(uuids);
				JsonObject users = result.getAsJsonObject("users");
				synchronized (RegistryUsers.class) {
					long now = System.currentTimeMillis();
					for (UUID uuid : uuids) {
						String key = uuid.toString().replace("-", "");
						JsonObject entry = users != null && users.has(key)
								? users.getAsJsonObject(key) : null;
						if (entry == null) {
							// "not a user" is a real answer — cached, but on a backoff
							USERS.remove(uuid);
							CAPES.remove(uuid);
							COLORS.remove(uuid);
							MISSES.merge(uuid, 1, Integer::sum);
						} else {
							USERS.add(uuid);
							MISSES.remove(uuid);
							if (entry.has("cape")) {
								CAPES.put(uuid, entry.get("cape").getAsString());
							} else {
								CAPES.remove(uuid); // a user who took their cape off
							}
							if (entry.has("color")) {
								COLORS.put(uuid, entry.get("color").getAsInt());
							} else {
								COLORS.remove(uuid);
							}
						}
						CHECKED.put(uuid, now);
						IN_FLIGHT.remove(uuid);
					}
				}
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.warn("Registry user lookup failed", e);
				synchronized (RegistryUsers.class) {
					IN_FLIGHT.removeAll(uuids);
					backoffUntil = System.currentTimeMillis() + BACKOFF_MS;
				}
			}
		}, "unlucky-registry-users").start();
	}
}
