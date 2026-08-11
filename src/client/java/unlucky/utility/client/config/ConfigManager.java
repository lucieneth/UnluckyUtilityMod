package unlucky.utility.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import unlucky.utility.client.gui.clickgui.FutureClickGuiScreen;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.Setting;

public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Shipped in the jar; see {@link #applyBundledDefaults}. */
	private static final String DEFAULT_CONFIG = "/assets/unlucky/default_config.json";

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
			if (module.persistsEnabled()) {
				moduleJson.addProperty("enabled", module.isEnabled());
			}
			moduleJson.addProperty("bind", module.getKeyBind());
			JsonObject settings = new JsonObject();
			for (Setting<?> setting : module.getSettings()) {
				if (setting instanceof ActionSetting) {
					continue;
				}
				settings.add(setting.getName(), serialize(setting));
			}
			moduleJson.add("settings", settings);
			modules.add(module.getName(), moduleJson);
		}
		root.add("modules", modules);

		JsonObject hud = new JsonObject();
		for (HudWidget widget : client.hud.widgets()) {
			JsonObject widgetJson = new JsonObject();
			if (!widget.isPrimaryInstance()) {
				widgetJson.addProperty("duplicate", true);
				widgetJson.addProperty("instanceId", widget.getInstanceId());
				widgetJson.addProperty("type", widget.getWidgetTypeId());
				widgetJson.addProperty("displayName", widget.getDisplayName());
			}
			widgetJson.addProperty("fx", widget.getFracX());
			widgetJson.addProperty("fy", widget.getFracY());
			JsonObject widgetSettings = new JsonObject();
			for (Setting<?> setting : widget.settings()) {
				widgetSettings.add(setting.getName(), serialize(setting));
			}
			widgetJson.add("settings", widgetSettings);
			// Primary widgets deliberately retain their legacy name keys. A copy's
			// stable ID is its key, so multiple instances never overwrite each other.
			hud.add(widget.getConfigKey(), widgetJson);
		}
		root.add("hud", hud);
		root.add("futureClickGui", FutureClickGuiScreen.positionsJson());
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
			applyBundledDefaults();
			return;
		}
		try {
			apply(JsonParser.parseString(Files.readString(file())).getAsJsonObject());
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to load config", e);
		}
	}

	/**
	 * The first-run baseline: theme colors, the modules a new install starts with, and the HUD
	 * layout. Applied only when there is no {@code config.json} at all, so it can never overwrite
	 * a returning user's settings.
	 *
	 * <p>It is the ordinary {@link #apply} contract doing the work, which is what keeps the file
	 * maintainable: unknown keys are ignored and absent keys leave the code default alone, so a
	 * baseline written against an older build still applies cleanly and simply says nothing about
	 * modules added since. That is also why the shipped copy carries <b>no {@code Hidden}
	 * entries</b> — hiding is a code default ({@code Module.hiddenByDefault()}), and a stale
	 * baseline listing every module as visible would silently undo it.
	 *
	 * <p>A copy is dropped into {@code configs/basic.json} so the baseline is also a profile you
	 * can return to from the Configs screen. Never overwritten — if one is already there it is
	 * the user's, whatever it now contains.
	 */
	private void applyBundledDefaults() {
		try (InputStream in = ConfigManager.class.getResourceAsStream(DEFAULT_CONFIG)) {
			if (in == null) {
				UnluckyClientMod.LOGGER.warn("No bundled default config at {}", DEFAULT_CONFIG);
				return;
			}
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			apply(JsonParser.parseString(json).getAsJsonObject());
			Path profile = configsDir().resolve("basic.json");
			if (!Files.exists(profile)) {
				Files.createDirectories(configsDir());
				Files.writeString(profile, json);
			}
			UnluckyClientMod.LOGGER.info("First run: applied the bundled default config");
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to apply the bundled default config", e);
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
		if (root.has("futureClickGui") && root.get("futureClickGui").isJsonObject()) {
			FutureClickGuiScreen.loadPositions(root.getAsJsonObject("futureClickGui"));
		}

		if (root.has("modules")) {
			JsonObject modules = root.getAsJsonObject("modules");
			// 2026-07-10 rename: carry old "Cape" entries into "Capes" (self-heals
			// on the next save, drop this once configs in the wild have cycled)
			if (modules.has("Cape") && !modules.has("Capes")) {
				modules.add("Capes", modules.get("Cape"));
			}
			migrateWeather(modules);
			migrateNoSlow(modules);
			migrateSpeed(modules);
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
				// The persists check is repeated on load, not just on save: configs written
				// before a module opted out still carry the key, and Panic must not come back
				// spent from one of them.
				if (module.persistsEnabled() && moduleJson.has("enabled")) {
					module.setEnabledSilently(moduleJson.get("enabled").getAsBoolean());
				}
			}
		}

		// 2026-07-30: widget settings moved out of the HUD module and onto the widgets.
		// Names did not change, so an older config's HUD settings still apply — read as
		// a fallback for any widget the new section does not carry yet.
		JsonObject legacyHud = root.has("modules")
				&& root.getAsJsonObject("modules").has("HUD")
				&& root.getAsJsonObject("modules").getAsJsonObject("HUD").has("settings")
						? root.getAsJsonObject("modules").getAsJsonObject("HUD").getAsJsonObject("settings")
						: null;
		client.hud.clearDuplicates();
		if (root.has("hud") && root.get("hud").isJsonObject()) {
			JsonObject hud = root.getAsJsonObject("hud");
			// Copies must exist before the ordinary settings pass below. Only types
			// already registered as primary widgets can be reconstructed by HudManager.
			for (var entry : hud.entrySet()) {
				if (!entry.getValue().isJsonObject()) continue;
				JsonObject json = entry.getValue().getAsJsonObject();
				if (!json.has("duplicate") || !json.get("duplicate").getAsBoolean() || !json.has("type")) continue;
				String storedId = json.has("instanceId") ? json.get("instanceId").getAsString() : entry.getKey();
				// The object key is authoritative. Reject mismatched metadata rather than
				// creating an instance whose settings could never be found in this file.
				if (!entry.getKey().equals(storedId)) continue;
				String label = json.has("displayName") ? json.get("displayName").getAsString() : null;
				client.hud.restoreDuplicate(json.get("type").getAsString(), storedId, label);
			}
			for (HudWidget widget : client.hud.widgets()) {
				String configKey = widget.getConfigKey();
				if (!hud.has(configKey) || !hud.get(configKey).isJsonObject()) {
					if (widget.isPrimaryInstance()) applyLegacyWidgetSettings(widget, legacyHud);
					continue;
				}
				JsonObject widgetJson = hud.getAsJsonObject(configKey);
				if (widgetJson.has("fx") && widgetJson.has("fy")) {
					widget.setFractions(widgetJson.get("fx").getAsDouble(), widgetJson.get("fy").getAsDouble());
				}
				if (widgetJson.has("settings")) {
					JsonObject settings = widgetJson.getAsJsonObject("settings");
					for (Setting<?> setting : widget.settings()) {
						if (settings.has(setting.getName())) {
							deserialize(setting, settings.get(setting.getName()));
						}
					}
				} else if (widget.isPrimaryInstance()) {
					applyLegacyWidgetSettings(widget, legacyHud);
				}
			}
		} else {
			// Very old configs had widget controls only in the HUD module block.
			for (HudWidget widget : client.hud.widgets()) {
				applyLegacyWidgetSettings(widget, legacyHud);
			}
		}
	}

	/**
	 * 2026-08-10: Weather supersedes the old clear-only NoWeather module. Keep the legacy
	 * instance registered for old binds, but move its enabled state into Weather and hide it so
	 * a migrated profile cannot leave two weather owners competing.
	 */
	private static void migrateWeather(JsonObject modules) {
		JsonObject noWeather = object(modules, "NoWeather");
		JsonObject weather = object(modules, "Weather");
		if (noWeather != null) {
			boolean legacyEnabled = noWeather.has("enabled") && noWeather.get("enabled").getAsBoolean();
			boolean weatherEnabled = weather != null && weather.has("enabled")
					&& weather.get("enabled").getAsBoolean();
			if (legacyEnabled && !weatherEnabled) {
				if (weather == null) {
					weather = new JsonObject();
					modules.add("Weather", weather);
					if (noWeather.has("bind")) {
						weather.add("bind", noWeather.get("bind").deepCopy());
					}
				}
				weather.addProperty("enabled", true);
				JsonObject settings = object(weather, "settings");
				if (settings == null) {
					settings = new JsonObject();
					weather.add("settings", settings);
				}
				JsonObject mode = new JsonObject();
				mode.addProperty("value", "Clear");
				settings.add("Mode", mode);
			}
			// A legacy row must never become a second owner after the Weather handoff.
			noWeather.addProperty("enabled", false);
			JsonObject settings = object(noWeather, "settings");
			if (settings == null) {
				settings = new JsonObject();
				noWeather.add("settings", settings);
			}
			JsonObject hidden = new JsonObject();
			hidden.addProperty("value", true);
			settings.add("Hidden", hidden);
		}

		if (weather == null) return;
		JsonObject settings = object(weather, "settings");
		if (settings == null || !settings.has("Particles and sound")) return;
		JsonElement legacyEffects = settings.remove("Particles and sound");
		if (!settings.has("Particles")) settings.add("Particles", legacyEffects.deepCopy());
		if (!settings.has("Ambient weather sound")) {
			settings.add("Ambient weather sound", legacyEffects.deepCopy());
		}
	}

	/**
	 * 2026-08-10: NoSlow's three broad switches became individual source toggles. Existing
	 * profiles must keep their previous all-or-nothing choice rather than unexpectedly enabling
	 * or disabling part of the feature set after upgrade.
	 */
	private static void migrateNoSlow(JsonObject modules) {
		JsonObject noSlow = object(modules, "NoSlow");
		JsonObject settings = noSlow == null ? null : object(noSlow, "settings");
		if (settings == null) return;
		copySetting(settings, "Items", "Consumables", "Bows", "Crossbows", "Shields",
				"Tridents / Spears", "Spyglass", "Other use items");
		copySetting(settings, "Webs", "Cobweb", "Sweet berry bush", "Powder snow");
		copySetting(settings, "Blocks", "Honey", "Soul sand / soul soil");
	}

	/** 2026-08-10: Speed's one value split into matching grounded and airborne speeds. */
	private static void migrateSpeed(JsonObject modules) {
		JsonObject speed = object(modules, "Speed");
		JsonObject settings = speed == null ? null : object(speed, "settings");
		if (settings != null) copySetting(settings, "Speed", "Ground speed", "Air speed");
	}

	/** Copies a legacy value only when the new setting does not already have an explicit answer. */
	private static void copySetting(JsonObject settings, String oldName, String... newNames) {
		if (!settings.has(oldName)) return;
		for (String newName : newNames) {
			if (!settings.has(newName)) settings.add(newName, settings.get(oldName).deepCopy());
		}
	}

	private static JsonObject object(JsonObject parent, String name) {
		return parent.has(name) && parent.get(name).isJsonObject()
				? parent.getAsJsonObject(name) : null;
	}

	/** Copies settings that share both a name and a concrete setting type. */
	public static void copyCompatibleWidgetSettings(HudWidget source, HudWidget target) {
		java.util.Map<String, Setting<?>> sourceSettings = new java.util.HashMap<>();
		for (Setting<?> setting : source.settings()) {
			sourceSettings.put(setting.getName(), setting);
		}
		for (Setting<?> targetSetting : target.settings()) {
			Setting<?> sourceSetting = sourceSettings.get(targetSetting.getName());
			if (sourceSetting == null || sourceSetting.getClass() != targetSetting.getClass()) continue;
			deserialize(targetSetting, serialize(sourceSetting));
		}
	}

	/** Pulls a widget's values out of a pre-move config's HUD module block, by name. */
	private static void applyLegacyWidgetSettings(HudWidget widget, JsonObject legacyHud) {
		if (legacyHud == null) {
			return;
		}
		for (Setting<?> setting : widget.settings()) {
			if (legacyHud.has(setting.getName())) {
				deserialize(setting, legacyHud.get(setting.getName()));
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
			case unlucky.utility.client.settings.StringListSetting s -> {
				com.google.gson.JsonArray array = new com.google.gson.JsonArray();
				s.get().forEach(array::add);
				json.add("value", array);
			}
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
			case unlucky.utility.client.settings.StringListSetting s -> {
				java.util.List<String> entries = new java.util.ArrayList<>();
				if (value.isJsonArray()) value.getAsJsonArray().forEach(entry -> entries.add(entry.getAsString()));
				if (value.isJsonArray()) s.setAll(entries);
				else if (value.isJsonPrimitive()) s.setLegacyCommaSeparated(value.getAsString());
			}
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
