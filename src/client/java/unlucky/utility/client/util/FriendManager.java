package unlucky.utility.client.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import unlucky.utility.client.UnluckyClientMod;

/**
 * The client-side friends list: UUID → last-known name, persisted to
 * {@code config/unlucky/friends.json} (next to the config and the cape cache).
 * Keyed by UUID so friendships survive name changes; the stored name is only a
 * display convenience and refreshes whenever the friend is toggled while online.
 *
 * <p>Purely local for now — the networking phase (see plan.md) will sync
 * presence/capes through api.unlucky.life, but the source of truth for who is
 * a friend stays this file.
 */
public final class FriendManager {
	/** The accent used everywhere a friend is marked (tablist dot, nametag dot). */
	public static final int COLOR = 0xFF4A9BFF;
	/** Your own dot (Friends "Self dot"), green wherever friend dots appear. */
	public static final int SELF_COLOR = 0xFF43D96B;
	/** RGB-only variant for vanilla {@code Component.withColor} (no alpha channel). */
	public static final int TEXT_COLOR = COLOR & 0xFFFFFF;
	public static final String DOT = "•";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, String> FRIENDS = new LinkedHashMap<>();
	private static boolean loaded;

	private FriendManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky/friends.json");
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, String> raw = GSON.fromJson(Files.readString(file),
					new TypeToken<LinkedHashMap<String, String>>() {
					}.getType());
			if (raw != null) {
				for (Map.Entry<String, String> entry : raw.entrySet()) {
					FRIENDS.put(UUID.fromString(entry.getKey()), entry.getValue());
				}
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to load friends list", e);
		}
	}

	private static void save() {
		Map<String, String> raw = new LinkedHashMap<>();
		for (Map.Entry<UUID, String> entry : FRIENDS.entrySet()) {
			raw.put(entry.getKey().toString(), entry.getValue());
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(raw));
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to save friends list", e);
		}
	}

	public static boolean isFriend(UUID uuid) {
		ensureLoaded();
		return uuid != null && FRIENDS.containsKey(uuid);
	}

	/**
	 * Adds or removes a friend and saves immediately (the file is tiny).
	 * Re-adding refreshes the stored name. Returns true when now a friend.
	 */
	public static boolean toggle(UUID uuid, String name) {
		ensureLoaded();
		boolean added = FRIENDS.remove(uuid) == null;
		if (added) {
			FRIENDS.put(uuid, name);
		}
		save();
		return added;
	}

	/** Adds (or refreshes the name of) a friend; returns true when newly added. */
	public static boolean add(UUID uuid, String name) {
		ensureLoaded();
		boolean added = FRIENDS.put(uuid, name) == null;
		save();
		return added;
	}

	/** Removes a friend; returns true when they were on the list. */
	public static boolean remove(UUID uuid) {
		ensureLoaded();
		boolean removed = FRIENDS.remove(uuid) != null;
		if (removed) {
			save();
		}
		return removed;
	}

	/** Snapshot view for the Friends GUI; UUID → last-known name. */
	public static Map<UUID, String> all() {
		ensureLoaded();
		return java.util.Collections.unmodifiableMap(FRIENDS);
	}
}
