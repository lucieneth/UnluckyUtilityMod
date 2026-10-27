package unlucky.utility.client.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Key → chat line binds, persisted to {@code config/unlucky/macros.json} next to the
 * friends list. A line starting with {@code /} goes out as a server command, anything else
 * as chat — which means a {@code .}-line runs one of our own commands, since chat is where
 * those are caught anyway.
 *
 * <p>Macros fire from the same raw key path as module binds, so they share its rules: never
 * while a screen is open, and never for the unbound sentinel.
 */
public final class MacroManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<Integer, String> MACROS = new LinkedHashMap<>();
	private static boolean loaded;

	private MacroManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky/macros.json");
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
			Map<Integer, String> raw = GSON.fromJson(Files.readString(file),
					new TypeToken<LinkedHashMap<Integer, String>>() {
					}.getType());
			if (raw != null) {
				MACROS.putAll(raw);
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to load macros", e);
		}
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(MACROS));
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to save macros", e);
		}
	}

	public static Map<Integer, String> all() {
		ensureLoaded();
		return Collections.unmodifiableMap(MACROS);
	}

	/** Binds (or rebinds) a key; returns the line it replaced, or null. */
	public static String set(int key, String line) {
		ensureLoaded();
		String previous = MACROS.put(key, line);
		save();
		return previous;
	}

	/** Returns the removed line, or null when the key had none. */
	public static String remove(int key) {
		ensureLoaded();
		String removed = MACROS.remove(key);
		if (removed != null) {
			save();
		}
		return removed;
	}

	/** UnluckyClient, on a raw key press with no screen open. */
	public static void onKeyPress(int key) {
		ensureLoaded();
		String line = MACROS.get(key);
		Minecraft mc = Minecraft.getInstance();
		if (line == null || mc.player == null) {
			return;
		}
		if (line.startsWith("/")) {
			mc.player.connection.sendCommand(line.substring(1));
		} else {
			mc.player.connection.sendChat(line);
		}
	}
}
