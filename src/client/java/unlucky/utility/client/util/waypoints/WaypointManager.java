package unlucky.utility.client.util.waypoints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Saved waypoints, persisted to {@code config/unlucky/waypoints.json} next to
 * the friends list and the config.
 *
 * <p>Dimensions are stored as the {@code ResourceKey} path ("overworld",
 * "the_nether", "the_end"), and {@link #displayPos} does the 8:1 nether
 * conversion so an overworld waypoint shows up at the right place while you're
 * navigating the nether (and vice versa) — which is the entire point of having
 * waypoints on an anarchy server.
 */
public final class WaypointManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Waypoint> WAYPOINTS = new ArrayList<>();
	private static boolean loaded;

	private WaypointManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky/waypoints.json");
	}

	/** Serialized form — plain fields so the json stays hand-editable. */
	private static final class Entry {
		String id;
		long createdAt;
		String name;
		int x;
		int y;
		int z;
		String dimension;
		int color;
		boolean visible = true;
		boolean death;
		String nearAction;
		int nearDistance;
		int maxVisible;
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
		try (var reader = Files.newBufferedReader(file)) {
			List<Entry> entries = GSON.fromJson(reader, new TypeToken<List<Entry>>() { }.getType());
			if (entries == null) {
				return;
			}
			for (Entry e : entries) {
				Waypoint waypoint = new Waypoint(UUID.fromString(e.id), e.createdAt, e.name,
						new BlockPos(e.x, e.y, e.z), e.dimension, e.color);
				waypoint.visible = e.visible;
				waypoint.death = e.death;
				waypoint.nearAction = e.nearAction == null
						? Waypoint.NearAction.KEEP : Waypoint.NearAction.valueOf(e.nearAction);
				waypoint.nearDistance = Math.max(e.nearDistance, 1);
				waypoint.maxVisible = e.maxVisible <= 0 ? 5000 : e.maxVisible;
				WAYPOINTS.add(waypoint);
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("Could not read waypoints.json", e);
		}
	}

	public static void save() {
		List<Entry> entries = new ArrayList<>(WAYPOINTS.size());
		for (Waypoint w : WAYPOINTS) {
			Entry e = new Entry();
			e.id = w.id.toString();
			e.createdAt = w.createdAt;
			e.name = w.name;
			e.x = w.pos.getX();
			e.y = w.pos.getY();
			e.z = w.pos.getZ();
			e.dimension = w.dimension;
			e.color = w.color;
			e.visible = w.visible;
			e.death = w.death;
			e.nearAction = w.nearAction.name();
			e.nearDistance = w.nearDistance;
			e.maxVisible = w.maxVisible;
			entries.add(e);
		}
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(entries));
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("Could not write waypoints.json", e);
		}
	}

	public static List<Waypoint> all() {
		ensureLoaded();
		return WAYPOINTS;
	}

	public static void add(Waypoint waypoint) {
		ensureLoaded();
		WAYPOINTS.add(waypoint);
		save();
	}

	public static void remove(Waypoint waypoint) {
		ensureLoaded();
		WAYPOINTS.remove(waypoint);
		save();
	}

	/** The dimension key path of the level you're in, or null with no world. */
	public static String currentDimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.dimension().identifier().getPath();
	}

	/**
	 * Where a waypoint should be drawn <em>right now</em>: its own position when
	 * you're in its dimension, the 8:1 converted position when you're on the other
	 * side of an overworld/nether pair, or null when it doesn't belong here at all
	 * (an End waypoint while you're in the overworld).
	 */
	public static BlockPos displayPos(Waypoint waypoint, String dimension) {
		if (dimension == null) {
			return null;
		}
		if (waypoint.dimension.equals(dimension)) {
			return waypoint.pos;
		}
		if ("overworld".equals(waypoint.dimension) && "the_nether".equals(dimension)) {
			return new BlockPos(waypoint.pos.getX() / 8, waypoint.pos.getY(), waypoint.pos.getZ() / 8);
		}
		if ("the_nether".equals(waypoint.dimension) && "overworld".equals(dimension)) {
			return new BlockPos(waypoint.pos.getX() * 8, waypoint.pos.getY(), waypoint.pos.getZ() * 8);
		}
		return null;
	}

	/** True when the waypoint is shown in a dimension other than its own (8:1 projection). */
	public static boolean isProjected(Waypoint waypoint, String dimension) {
		return !waypoint.dimension.equals(dimension);
	}

	/** Drops the newest death marker, trimming the oldest once {@code max} is exceeded. */
	public static void addDeath(BlockPos pos, String dimension, int color, int max, String name) {
		ensureLoaded();
		Waypoint waypoint = new Waypoint(name, pos, dimension, color);
		waypoint.death = true;
		waypoint.nearAction = Waypoint.NearAction.DELETE;
		waypoint.nearDistance = 4;
		WAYPOINTS.add(waypoint);

		List<Waypoint> deaths = new ArrayList<>();
		for (Waypoint w : WAYPOINTS) {
			if (w.death) {
				deaths.add(w);
			}
		}
		deaths.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
		for (int i = 0; i < deaths.size() - max; i++) {
			WAYPOINTS.remove(deaths.get(i));
		}
		save();
	}

	/** Dimension path for a level key, for callers holding one (e.g. the respawn packet). */
	public static String dimensionOf(net.minecraft.resources.ResourceKey<Level> key) {
		return key.identifier().getPath();
	}
}
