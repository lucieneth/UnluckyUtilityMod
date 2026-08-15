package unlucky.utility.client.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.Level;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Things found in a world, remembered across sessions, keyed by which world they were found in.
 *
 * <p><b>Not in the module config.</b> Config is settings — small, hand-editable, rewritten in
 * full on every save and loaded before a world exists. A list of every stash you have ever
 * walked past is none of those things, and putting it there means a corrupt entry takes your
 * whole client configuration with it. It lives in its own file, per server, and a file that
 * fails to parse costs you that server's records and nothing else.
 *
 * <p><b>Servers and saves never mix.</b> Chunk coordinates are meaningless across worlds — the
 * same numbers resolve to whatever happens to be at them somewhere else — so the server identity
 * is part of the key rather than a field in the record, and a singleplayer save is as separate
 * from a server as two servers are from each other. Nothing here uploads or combines anything.
 *
 * <p><b>Writes are atomic.</b> The write goes to a temporary file beside the real one and is
 * then moved over it, so a crash mid-save leaves the previous file intact rather than a
 * half-written one. A truncated JSON file is exactly the failure that would otherwise be
 * discovered on the next launch, when it is far too late to do anything about it.
 */
public final class WorldRecordStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Bumped when the on-disk shape changes; an older file is skipped rather than guessed at. */
	private static final int FORMAT = 1;

	/**
	 * One remembered position.
	 *
	 * @param dimension  the dimension's registry id, as text — a record is worthless without it
	 * @param count      how many of whatever was counted; the meaning is the caller's
	 * @param lastSeen   epoch millis, for the "last seen" read-out and for pruning
	 * @param details    free-form extras the caller wants back, kept as a flat string map so an
	 *                   older file with fields we no longer read still loads
	 */
	public record Record(String dimension, int chunkX, int chunkZ, int count, long lastSeen,
			Map<String, String> details) {
		public Record withCount(int newCount) {
			return new Record(dimension, chunkX, chunkZ, newCount, System.currentTimeMillis(), details);
		}

		/** The key this record occupies within its store — dimension plus chunk. */
		public String key() {
			return dimension + "@" + chunkX + "," + chunkZ;
		}
	}

	private final String name;
	private final Map<String, Map<String, Record>> byServer = new LinkedHashMap<>();
	/** Which servers have unsaved changes, so a save writes only what moved. */
	private final List<String> dirty = new ArrayList<>();
	private boolean loaded;

	/**
	 * @param name file-name stem, e.g. {@code "stashes"} → {@code config/unlucky/stashes/*.json}
	 */
	public WorldRecordStore(String name) {
		this.name = name;
	}

	private Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky").resolve(name);
	}

	// ---- identity ----------------------------------------------------------

	/**
	 * A stable, filename-safe identity for the world the player is currently in.
	 *
	 * <p>Address for a server, save name for singleplayer, and a distinct prefix for each so a
	 * save called {@code play.example.com} cannot collide with the server of that name. Returns
	 * null when there is no world, which callers must treat as "do not record anything" rather
	 * than as a default bucket — a bucket everything falls into when identity is unknown is how
	 * two worlds end up merged.
	 */
	public static String currentServerId() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			return sanitise("sp_" + mc.getSingleplayerServer().getWorldData().getLevelName());
		}
		ServerData server = mc.getCurrentServer();
		return server == null ? null : sanitise("mp_" + server.ip);
	}

	/** The current dimension's registry id as text, or null. */
	public static String currentDimension() {
		Level level = Minecraft.getInstance().level;
		return level == null ? null : level.dimension().identifier().toString();
	}

	/**
	 * Filename-safe and case-stable.
	 *
	 * <p>Lowercased on purpose: Windows filesystems are case-insensitive, so {@code Play.Example}
	 * and {@code play.example} are one file there and two everywhere else. Deciding it here means
	 * the same player gets the same records on either.
	 */
	private static String sanitise(String raw) {
		StringBuilder safe = new StringBuilder(raw.length());
		for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
			safe.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
		}
		return safe.toString();
	}

	// ---- reading -----------------------------------------------------------

	/** Every record for one server, in insertion order. Empty rather than null. */
	public List<Record> records(String serverId) {
		ensureLoaded(serverId);
		Map<String, Record> map = byServer.get(serverId);
		return map == null ? List.of() : new ArrayList<>(map.values());
	}

	/** Every record for one server in one dimension. */
	public List<Record> records(String serverId, String dimension) {
		List<Record> out = new ArrayList<>();
		for (Record record : records(serverId)) {
			if (record.dimension().equals(dimension)) {
				out.add(record);
			}
		}
		return out;
	}

	public Record get(String serverId, String dimension, int chunkX, int chunkZ) {
		ensureLoaded(serverId);
		Map<String, Record> map = byServer.get(serverId);
		return map == null ? null : map.get(dimension + "@" + chunkX + "," + chunkZ);
	}

	// ---- writing -----------------------------------------------------------

	/**
	 * Stores or replaces a record.
	 *
	 * @return whether this changed anything worth telling the player about — a new position, or
	 *         an existing one whose count went up. Revisiting an unchanged chunk answers false,
	 *         which is what stops a notification firing every time you walk past
	 */
	public boolean put(String serverId, Record record) {
		if (serverId == null || record == null) {
			return false;
		}
		ensureLoaded(serverId);
		Map<String, Record> map = byServer.computeIfAbsent(serverId, key -> new LinkedHashMap<>());
		Record existing = map.get(record.key());
		map.put(record.key(), record);
		markDirty(serverId);
		return existing == null || record.count() > existing.count();
	}

	public boolean remove(String serverId, String dimension, int chunkX, int chunkZ) {
		ensureLoaded(serverId);
		Map<String, Record> map = byServer.get(serverId);
		if (map == null || map.remove(dimension + "@" + chunkX + "," + chunkZ) == null) {
			return false;
		}
		markDirty(serverId);
		return true;
	}

	/** Forgets one dimension of one server. */
	public int clearDimension(String serverId, String dimension) {
		ensureLoaded(serverId);
		Map<String, Record> map = byServer.get(serverId);
		if (map == null) {
			return 0;
		}
		int before = map.size();
		map.values().removeIf(record -> record.dimension().equals(dimension));
		if (map.size() != before) {
			markDirty(serverId);
		}
		return before - map.size();
	}

	/** Forgets one server entirely, file and all. */
	public int clearServer(String serverId) {
		ensureLoaded(serverId);
		Map<String, Record> map = byServer.remove(serverId);
		markDirty(serverId);
		save(serverId);
		return map == null ? 0 : map.size();
	}

	private void markDirty(String serverId) {
		if (!dirty.contains(serverId)) {
			dirty.add(serverId);
		}
	}

	/** Writes every server whose records changed. Safe to call every tick; usually does nothing. */
	public void saveDirty() {
		if (dirty.isEmpty()) {
			return;
		}
		for (String serverId : new ArrayList<>(dirty)) {
			save(serverId);
		}
		dirty.clear();
	}

	// ---- files -------------------------------------------------------------

	private Path fileFor(String serverId) {
		return directory().resolve(serverId + ".json");
	}

	/**
	 * Loads one server's file, once.
	 *
	 * <p>Every failure mode ends in an empty map and a log line rather than an exception:
	 * a missing file is the normal first-visit case, an unreadable one is a disk problem, and a
	 * malformed one is a file somebody edited or a crash truncated. None of those is a reason
	 * to stop the client, and a records file that blocks startup is a far worse bug than a
	 * records file that is empty.
	 */
	private void ensureLoaded(String serverId) {
		if (serverId == null || byServer.containsKey(serverId)) {
			return;
		}
		byServer.put(serverId, new LinkedHashMap<>());
		Path file = fileFor(serverId);
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file));
			if (!root.isJsonObject()) {
				quarantine(file, "not an object");
				return;
			}
			JsonObject object = root.getAsJsonObject();
			int format = object.has("format") ? object.get("format").getAsInt() : 0;
			if (format != FORMAT) {
				// Deliberately not migrated. These are records the client can rebuild simply by
				// being played, so guessing at an older shape risks worse than losing them.
				quarantine(file, "format " + format + ", expected " + FORMAT);
				return;
			}
			Map<String, Record> map = byServer.get(serverId);
			for (JsonElement element : object.getAsJsonArray("records")) {
				Record record = readRecord(element);
				if (record != null) {
					map.put(record.key(), record);
				}
				// A single bad entry is skipped, not fatal: one unreadable stash should not
				// cost the other four hundred.
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to read {} records for {}", name, serverId, e);
			quarantine(file, e.getClass().getSimpleName());
		}
	}

	private static Record readRecord(JsonElement element) {
		try {
			JsonObject object = element.getAsJsonObject();
			Map<String, String> details = new LinkedHashMap<>();
			if (object.has("details")) {
				for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("details").entrySet()) {
					details.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
			return new Record(object.get("dimension").getAsString(),
					object.get("x").getAsInt(), object.get("z").getAsInt(),
					object.get("count").getAsInt(),
					object.has("lastSeen") ? object.get("lastSeen").getAsLong() : 0L,
					details);
		} catch (Exception ignored) {
			return null;
		}
	}

	/**
	 * Moves a file we could not read out of the way, so the next save does not silently
	 * overwrite whatever was in it. The player keeps the chance to look at it; the client stops
	 * failing to parse it on every launch.
	 */
	private void quarantine(Path file, String why) {
		UnluckyClientMod.LOGGER.warn("Quarantining unreadable {} records ({}): {}", name, why, file);
		try {
			Files.move(file, file.resolveSibling(file.getFileName() + ".broken"),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.error("Could not quarantine {}", file, e);
		}
	}

	/** Atomic write: temp file beside the target, then moved over it. */
	private void save(String serverId) {
		Map<String, Record> map = byServer.get(serverId);
		Path file = fileFor(serverId);
		try {
			Files.createDirectories(directory());
			if (map == null || map.isEmpty()) {
				Files.deleteIfExists(file);
				return;
			}
			JsonObject root = new JsonObject();
			root.addProperty("format", FORMAT);
			com.google.gson.JsonArray records = new com.google.gson.JsonArray();
			for (Record record : map.values()) {
				JsonObject object = new JsonObject();
				object.addProperty("dimension", record.dimension());
				object.addProperty("x", record.chunkX());
				object.addProperty("z", record.chunkZ());
				object.addProperty("count", record.count());
				object.addProperty("lastSeen", record.lastSeen());
				if (!record.details().isEmpty()) {
					JsonObject details = new JsonObject();
					record.details().forEach(details::addProperty);
					object.add("details", details);
				}
				records.add(object);
			}
			root.add("records", records);

			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root));
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to save {} records for {}", name, serverId, e);
		}
	}
}
