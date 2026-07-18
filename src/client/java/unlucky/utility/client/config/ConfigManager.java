package unlucky.utility.client.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.Setting;

public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Everything client-side lives under config/unlucky/: config, friends, cape cache. */
	private Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky/config.json");
	}

	/** Pre-2026-07 location; moved into the unlucky folder on first load, then unused. */
	private Path legacyFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky.json");
	}

	/** Where named profiles live; the active config stays {@code config.json} beside it. */
	public Path configsDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky/configs");
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(toJson()));
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.error("Failed to save config", e);
		}
	}

	/** The full client state as one JsonObject — the active config and every profile share this shape. */
	public JsonObject toJson() {
		UnluckyClient client = UnluckyClient.INSTANCE;
		JsonObject root = new JsonObject();
		root.addProperty("clickGuiKey", client.clickGuiKey);
		root.addProperty("hudEditorKey", client.hudEditorKey);
		root.addProperty("consoleKey", client.consoleKey);

		JsonObject modules = new JsonObject();
		for (Module module : client.modules.all()) {
			JsonObject moduleJson = new JsonObject();
			moduleJson.addProperty("enabled", module.isEnabled());
			moduleJson.addProperty("bind", module.getKeyBind());
			JsonObject settings = new JsonObject();
			for (Setting<?> setting : module.getSettings()) {
				settings.add(setting.getName(), serialize(setting));
			}
			moduleJson.add("settings", settings);
			modules.add(module.getName(), moduleJson);
		}
		root.add("modules", modules);

		JsonObject hud = new JsonObject();
		for (HudWidget widget : client.hud.widgets()) {
			JsonObject widgetJson = new JsonObject();
			widgetJson.addProperty("fx", widget.getFracX());
			widgetJson.addProperty("fy", widget.getFracY());
			hud.add(widget.getName(), widgetJson);
		}
		root.add("hud", hud);
		return root;
	}

	public void load() {
		if (!Files.exists(file()) && Files.exists(legacyFile())) {
			try {
				Files.createDirectories(file().getParent());
				Files.move(legacyFile(), file());
			} catch (IOException e) {
				UnluckyClientMod.LOGGER.error("Failed to migrate config into the unlucky folder", e);
			}
		}
		if (!Files.exists(file())) {
			return;
		}
		try {
			apply(JsonParser.parseString(Files.readString(file())).getAsJsonObject());
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to load config", e);
		}
	}

	// ---- named profiles (config/unlucky/configs/<name>.json) ----------------

	/** Profile names (no extension), newest first — what the Configs screen lists. */
	public java.util.List<Path> listProfiles() {
		try {
			if (!Files.isDirectory(configsDir())) {
				return java.util.List.of();
			}
			try (var stream = Files.list(configsDir())) {
				return stream.filter(p -> p.getFileName().toString().endsWith(".json"))
						.sorted(java.util.Comparator.comparing((Path p) -> {
							try {
								return Files.getLastModifiedTime(p);
							} catch (IOException e) {
								return java.nio.file.attribute.FileTime.fromMillis(0);
							}
						}).reversed())
						.toList();
			}
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.error("Failed to list configs", e);
			return java.util.List.of();
		}
	}

	/**
	 * Saves the live settings as a named profile. The name becomes the file name,
	 * so it's sanitised down to safe characters here rather than validated at
	 * every caller. Returns the message for the screen's status line.
	 */
	public String saveProfile(String name) {
		String safe = name.trim().replaceAll("[^\\w \\-]", "");
		if (safe.isEmpty()) {
			return "§cName needed";
		}
		try {
			Files.createDirectories(configsDir());
			Path target = configsDir().resolve(safe + ".json");
			boolean existed = Files.exists(target);
			Files.writeString(target, GSON.toJson(toJson()));
			return safe + (existed ? " overwritten" : " saved");
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.error("Failed to save profile {}", safe, e);
			return "§cSave failed: " + e.getMessage();
		}
	}

	/**
	 * Loads a profile into the live client and makes it the active config (so a
	 * relaunch keeps it — loading that doesn't survive a restart would read as
	 * the load having silently failed). Works for any JSON in our shape, which is
	 * what makes Import "copy the file in, then load it".
	 */
	public String loadProfile(Path profile) {
		try {
			apply(JsonParser.parseString(Files.readString(profile)).getAsJsonObject());
			save();
			return profileName(profile) + " loaded";
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to load profile {}", profile, e);
			return "§cNot a valid config: " + profileName(profile);
		}
	}

	public static String profileName(Path profile) {
		String file = profile.getFileName().toString();
		return file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
	}

	/**
	 * Applies a config JsonObject to the live client — the other half of
	 * {@link #toJson()}. Unknown keys are ignored and missing keys leave current
	 * values alone, so configs from older versions apply cleanly.
	 */
	public void apply(JsonObject root) {
		UnluckyClient client = UnluckyClient.INSTANCE;
		if (root.has("clickGuiKey")) {
			client.clickGuiKey = root.get("clickGuiKey").getAsInt();
		}
		if (root.has("hudEditorKey")) {
			client.hudEditorKey = root.get("hudEditorKey").getAsInt();
		}
		if (root.has("consoleKey")) {
			client.consoleKey = root.get("consoleKey").getAsInt();
		}

		if (root.has("modules")) {
			JsonObject modules = root.getAsJsonObject("modules");
			// 2026-07-10 rename: carry old "Cape" entries into "Capes" (self-heals
			// on the next save, drop this once configs in the wild have cycled)
			if (modules.has("Cape") && !modules.has("Capes")) {
				modules.add("Capes", modules.get("Cape"));
			}
			for (Module module : client.modules.all()) {
				if (!modules.has(module.getName())) {
					continue;
				}
				JsonObject moduleJson = modules.getAsJsonObject(module.getName());
				if (moduleJson.has("bind")) {
					module.setKeyBind(moduleJson.get("bind").getAsInt());
				}
				if (moduleJson.has("settings")) {
					JsonObject settings = moduleJson.getAsJsonObject("settings");
					for (Setting<?> setting : module.getSettings()) {
						if (settings.has(setting.getName())) {
							deserialize(setting, settings.get(setting.getName()));
						}
					}
				}
				if (moduleJson.has("enabled")) {
					module.setEnabledSilently(moduleJson.get("enabled").getAsBoolean());
				}
			}
		}

		if (root.has("hud")) {
			JsonObject hud = root.getAsJsonObject("hud");
			for (HudWidget widget : client.hud.widgets()) {
				if (hud.has(widget.getName())) {
					JsonObject widgetJson = hud.getAsJsonObject(widget.getName());
					if (widgetJson.has("fx")) {
						widget.setFractions(widgetJson.get("fx").getAsDouble(), widgetJson.get("fy").getAsDouble());
					}
				}
			}
		}
	}

	private static JsonElement serialize(Setting<?> setting) {
		JsonObject json = new JsonObject();
		switch (setting) {
			case BooleanSetting s -> json.addProperty("value", s.get());
			case NumberSetting s -> json.addProperty("value", s.get());
			case ModeSetting s -> json.addProperty("value", s.get());
			case ColorSetting s -> json.addProperty("value", s.get());
			case KeybindSetting s -> json.addProperty("value", s.get());
			case unlucky.utility.client.settings.StringSetting s -> json.addProperty("value", s.get());
			case BlockListSetting s -> {
				com.google.gson.JsonArray array = new com.google.gson.JsonArray();
				s.get().forEach(array::add);
				json.add("value", array);
			}
			case unlucky.utility.client.settings.EntityListSetting s -> {
				com.google.gson.JsonArray array = new com.google.gson.JsonArray();
				s.get().forEach(array::add);
				json.add("value", array);
			}
			case unlucky.utility.client.settings.ItemListSetting s -> {
				com.google.gson.JsonArray array = new com.google.gson.JsonArray();
				s.get().forEach(array::add);
				json.add("value", array);
			}
			case unlucky.utility.client.settings.BrewQueueSetting s -> {
				com.google.gson.JsonArray array = new com.google.gson.JsonArray();
				s.get().forEach(array::add);
				json.add("value", array);
			}
			default -> {
			}
		}
		return json;
	}

	private static void deserialize(Setting<?> setting, JsonElement element) {
		if (!element.isJsonObject() || !element.getAsJsonObject().has("value")) {
			return;
		}
		JsonElement value = element.getAsJsonObject().get("value");
		switch (setting) {
			case BooleanSetting s -> s.set(value.getAsBoolean());
			case NumberSetting s -> s.set(value.getAsDouble());
			case ModeSetting s -> s.set(value.getAsString());
			case ColorSetting s -> s.set(value.getAsInt());
			case KeybindSetting s -> s.set(value.getAsInt());
			case unlucky.utility.client.settings.StringSetting s -> s.set(value.getAsString());
			case BlockListSetting s -> {
				java.util.Set<String> ids = new java.util.TreeSet<>();
				value.getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
				s.setAll(ids);
			}
			case unlucky.utility.client.settings.EntityListSetting s -> {
				java.util.Set<String> ids = new java.util.TreeSet<>();
				value.getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
				s.setAll(ids);
			}
			case unlucky.utility.client.settings.ItemListSetting s -> {
				java.util.Set<String> ids = new java.util.TreeSet<>();
				value.getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
				s.setAll(ids);
			}
			// a List, not a sorted Set like the others: this queue is worked in order
			case unlucky.utility.client.settings.BrewQueueSetting s -> {
				java.util.List<String> entries = new java.util.ArrayList<>();
				value.getAsJsonArray().forEach(entry -> entries.add(entry.getAsString()));
				s.setAll(entries);
			}
			default -> {
			}
		}
	}
}
