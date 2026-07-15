package unlucky.utility.client.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Supplies cape textures for the Cape module from three sources:
 *
 * <ul>
 *   <li><b>mojang</b> — every official cape, streamed straight from Mojang's own
 *       texture server ({@code textures.minecraft.net}) via {@link SkinTextureDownloader}
 *       (the same path vanilla uses for real capes) and cached under the client
 *       config dir. Nothing is bundled or redistributed.</li>
 *   <li><b>unlucky</b> — the client's own cape pack, listed live from a public GitHub
 *       repo (contents API) and streamed from the raw file URLs. Drop a PNG in the repo
 *       and it appears in the picker on the next launch, no client update. The listing is
 *       cached to config so it still works offline.</li>
 *   <li><b>custom</b> (and any other subfolder) — PNGs discovered in
 *       {@code assets/unlucky/textures/capes/}.</li>
 * </ul>
 *
 * The same texture is reused for the elytra, matching how official capes carry onto it.
 */
public final class CapeManager {
	private static final String FOLDER = "textures/capes";
	private static final String ROOT_GROUP = "custom";
	private static final String STREAM_GROUP = "mojang";
	private static final String TEX_BASE = "https://textures.minecraft.net/texture/";

	/** Live cape pack: a public GitHub repo, listed via the contents API. */
	private static final String GITHUB_GROUP = "unlucky";
	private static final String GITHUB_API = "https://api.github.com/repos/lucieneth/Capes/contents";

	/** Official capes: display name, then the texture hash from textures.minecraft.net. */
	private static final String[][] OFFICIAL = {
			{"Common", "5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0"},
			{"Copper", "5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca"},
			{"Migrator", "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"},
			{"Pan", "28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"},
			{"Vanilla", "f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"},
			{"Crafter", "479eacefa3cdd7aca94207f36c0dd449653ddf259daf40544a5866baf05eee22"},
			{"MineCon 2011", "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"},
			{"MineCon 2012", "a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6"},
			{"MineCon 2013", "153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da"},
			{"MineCon 2015", "b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635"},
			{"MineCon 2016", "e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980"},
			{"Minecraft Experience", "7658c5025c77cfac7574aab3af94a46a8886e3b7722a895255fbf22ab8652434"},
			{"Moonlight Trail", "fe8a02dfe9e390e44ff33d69feef9d3943f76d3901015bbd50f0b67722d288bd"},
			{"15th Anniversary", "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"},
			{"Builder", "2c579968c64c1719740fd8c2a451461879b238002574fce48f7d1a7c36a1c7d4"},
			{"Followers", "569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350"},
			{"Founders", "99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1"},
			{"Cherry Blossom", "afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"},
			{"MCC 15th Year", "56c35628fe1c4d59dd52561a3d03bfa4e1a76d397c8b9c476c2f77cb6aebb1df"},
			{"Mojang Office", "5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26"},
			{"Purple Heart", "cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde"},
			{"TikTok Menace", "dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1"},
			{"Twitch Home", "1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd"},
			{"Yearn", "308b32a9e303155a0b4262f9e5483ad4a22e3412e84fe8385a0bdd73dc41fa89"},
			{"Zombie Horse", "a3f6e4f14801f3ea55e3d95b9b4ef3b5e8802d947f669de93d6ec4b9354a436b"},
			{"Cobalt", "ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81"},
			{"Mojang", "5786fe99be377dfb6858859f926c4dbc995751e91cee373468c5fbf4865e7151"},
			{"Mojang Classic", "8f120319222a9f4a104e2f5cb97b2cda93199a2ee9e1585cb8d09d6f687cb761"},
			{"Mojang Studios", "9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7"},
			{"Mojira Moderator", "ae677f7d98ac70a533713518416df4452fe5700365c09cf45d0d156ea9396551"},
			{"Realms Mapmaker", "17912790ff164b93196f08ba71d0e62129304776d0f347334f8a6eae509f8a56"},
			{"Scrolls", "3efadf6510961830f9fcc077f19b4daf286d502b5f5aafbd807c7bbffcaca245"},
			{"Translator", "1bf91499701404e21bd46b0191d63239a4ef76ebde88d27e4d430ac211df681e"},
			{"Translator Chinese", "2262fb1d24912209490586ecae98aca8500df3eff91f2a07da37ee524e7e3cb6"},
			{"Birthday", "2056f2eebd759cce93460907186ef44e9192954ae12b227d817eb4b55627a7fc"},
			{"dB", "bcfbe84c6542a4a5c213c1cacf8979b5e913dcb4ad783a8b80e3c4a7d5c8bdac"},
			{"Document", "fdcf48f01ec480d1d7cbec27f7ddce48c9da2be6724641109444dae58d4cd013"},
			{"Millionth Customer", "70efffaf86fe5bc089608d3cb297d3e276b9eb7a8f9f2fe6659c23a2d8b18edf"},
			{"Oxeye", "7706b5f5fc90329691e59277dcc66ba20572219fa8e5da472afd5235fad12cc8"},
			{"Prismarine", "d8f8d13a1adf9636a16c31d47f3ecc9bb8d8533108aa5ad2a01b13b1a0c55eac"},
			{"Snowman", "23ec737f18bfe4b547c95935fc297dd767bb84ee55bfd855144d279ac9bfd9fe"},
			{"Spade", "2e002d5e1758e79ba51d08d92a0f3a95119f2f435ae7704916507b6c565a7da8"},
			{"Translator Japanese", "ca29f5dd9e94fb1748203b92e36b66fda80750c87ebc18d6eafdb0e28cc1d05f"},
			{"Turtle", "5048ea61566353397247d2b7d946034de926b997d5e66c86483dfb1e031aee95"},
			{"Valentine", "e578ef995fabcf0a94768f9651ac3aaba30c59ef85d2438e9b3e0cc1d810652b"},
			{"Bacon", "fd14214cd8073059e93d9c626260f5df85e5a959181537119df56cadaf5002cc"},
			{"Christmas 2010", "2ada7acf3e0ef436f350e21af91a774b7cd95309c53668a441eeacec88ca4211"},
			{"New Years 2011", "d1f20f8534f9f58a3a0a26586d5615f513add564809986334b7f247593425ee3"},
			{"Progress Pride", "432c50e576e0b490865b562c7acf10473ac24780ea0fc3ef80fb303f482ba64"},
			{"Awesom", "da01a74f8ca96bdf652ad3acddc886d6396eea482870ed3d2678e07cd1cd653f"},
			{"Blonk", "c900e2768696a783f34a6ce548aad6d4241051fac15b1622fa7beeb521ae43e"},
			{"No Circle", "16516dd786b870268e7601ad9c9dbf53530fef54041a2d18f2b5fbf15c0724ea"},
			{"Nyan", "17c4ec5654f5d2f37953f228be1aa796d482a395c08dba65c82c020ebc6e03d8"},
			{"Squid", "639cb7c0f0d4345900b64f14ee33ecfccc7d6bcb5e18d027fb3452bfc9e5c4d1"},
			{"Veterinarian", "12607ff71c803562dfb985769caaebf867172c13b20853368da1ebb099817f0d"},
			{"Frog", "8c6b65823f7c686895160faf51f571ec2e4841317f7bc30d7e59371344c0c7d1"},
			{"ICU", "b69e02edd267ea9bd7bf3f67f2a5cfff0f5aa8caf7c081e2d7221ac78277970a"},
			{"ICU 2", "b698cefe18ac367f930332dd77f4a6d390be7adb36380e568761df4683562f84"},
			{"Snail", "e75f4110215ef4f3c25ed4b4fb0cd76cb6c4d5fc8bf7f351811435fbc2e085c4"},
			{"Test", "7a93b1867eb599f2b76e6e1c30a0ddb530e6f4c7bce6515d1ba72b206df30e39"},
			{"4J Studios", "c2e0cb7bfff287e2abbde2a4667af034e90d4b103decfc93ed990dbdf3a2ebe1"},
			{"Xbox 360 1st Birthday", "938155dd83118a3993a22579649fab313cdb06073029c3839843d58fad06ebb2"},
			{"Xbox 360 Microsoft", "13c8779683fd31ad49adc1e68df3f285fabe71d53f8b4b0ec5259085e37179fb"},
			{"Xbox 360 Minecraft", "5e8f3740ec1aabc872d8149c5e00b5b739cce63971db6edab30f94ccffed9d37"},
			{"MineCon 2011 Vote 3", "4e25998e4db8e19fe4df3df74d7983f03ff81a4074426252ce6eb3d1c70c9a59"},
			{"MineCon 2011 Vote 4", "35d9516769099ad42be14344551f9e9dfe66ee9ceb1d5624b4442f76cef9ea9e"},
			{"MineCon 2011 Vote 5", "dc39d8eb38419f4cbb9a2e19642893b854c131a9ab06bd4e2c2a5b3af98f3a19"},
	};

	/** One cape: either a bundled resource texture or a streamed URL (resolved lazily). */
	private static final class CapeEntry {
		final String group;
		final String name;
		final Identifier resource;      // bundled folder cape (null if streamed)
		final String url;               // streamed cape (null if bundled)
		final Identifier streamId;      // where a streamed texture registers
		volatile ClientAsset.Texture resolved;
		boolean requested;

		CapeEntry(String group, String name, Identifier resource, String url, Identifier streamId) {
			this.group = group;
			this.name = name;
			this.resource = resource;
			this.url = url;
			this.streamId = streamId;
		}
	}

	private static final List<CapeEntry> CAPES = new ArrayList<>();
	/** Live GitHub cape pack: display name -> raw download URL. */
	private static final Map<String, String> githubIndex = new LinkedHashMap<>();
	private static final Gson GSON = new Gson();
	private static boolean loaded;
	private static boolean githubStarted;
	/** Bumped whenever the cape list changes, so the picker knows to refresh. */
	private static volatile int revision;
	private static SkinTextureDownloader downloader;
	private static HttpClient http;

	private CapeManager() {
	}

	/** Rebuilds the cape list: the streamed official set, the live GitHub pack, and the capes folder. */
	public static void reload() {
		initGithub();
		CAPES.clear();
		for (String[] official : OFFICIAL) {
			String slug = slug(official[0]);
			Identifier id = UnluckyClientMod.id("capes/" + STREAM_GROUP + "/" + slug);
			CAPES.add(new CapeEntry(STREAM_GROUP, official[0], null, TEX_BASE + official[1], id));
		}
		for (Map.Entry<String, String> entry : githubIndex.entrySet()) {
			String slug = slug(entry.getKey());
			Identifier id = UnluckyClientMod.id("capes/" + GITHUB_GROUP + "/" + slug);
			CAPES.add(new CapeEntry(GITHUB_GROUP, entry.getKey(), null, entry.getValue(), id));
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getResourceManager() != null) {
			Map<Identifier, Resource> found = mc.getResourceManager().listResources(FOLDER,
					loc -> loc.getNamespace().equals(UnluckyClientMod.MOD_ID) && loc.getPath().endsWith(".png"));
			String prefix = FOLDER + "/";
			for (Identifier loc : found.keySet()) {
				String rest = loc.getPath().substring(prefix.length());
				int slash = rest.indexOf('/');
				String group = slash >= 0 ? rest.substring(0, slash) : ROOT_GROUP;
				String file = slash >= 0 ? rest.substring(slash + 1) : rest;
				if (file.contains("/") || group.equals(STREAM_GROUP) || group.equals(GITHUB_GROUP)) {
					continue; // ignore deeper nesting, and don't clash with the streamed groups
				}
				String slug = file.substring(0, file.length() - ".png".length());
				CAPES.add(new CapeEntry(group, prettify(slug), loc, null, null));
			}
		}
		CAPES.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		loaded = true;
		revision++;
	}

	private static void ensureLoaded() {
		if (!loaded) {
			reload();
		}
	}

	/** A counter that changes whenever the cape list is rebuilt (e.g. the GitHub list arrives). */
	public static int revision() {
		ensureLoaded();
		return revision;
	}

	// --- Live GitHub cape pack ------------------------------------------------

	/** Loads the cached listing (once) and kicks off a single background refresh. */
	private static void initGithub() {
		if (githubStarted) {
			return;
		}
		githubStarted = true;
		loadGithubCache();
		fetchGithub();
	}

	private static void loadGithubCache() {
		Path cache = githubCacheFile();
		if (!Files.exists(cache)) {
			return;
		}
		try {
			Map<String, String> map = GSON.fromJson(Files.readString(cache),
					new TypeToken<LinkedHashMap<String, String>>() {
					}.getType());
			if (map != null) {
				githubIndex.putAll(map);
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("Cape index cache read failed", e);
		}
	}

	private static void fetchGithub() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(GITHUB_API))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "UnluckyClient") // GitHub rejects requests without one
				.timeout(Duration.ofSeconds(10))
				.GET().build();
		http().sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(HttpResponse::body)
				.thenApply(CapeManager::parseGithub)
				.thenAccept(map -> {
					if (map.isEmpty()) {
						return; // keep whatever the cache gave us
					}
					// Touch the cape list and register textures only on the client thread.
					Minecraft.getInstance().execute(() -> {
						githubIndex.clear();
						githubIndex.putAll(map);
						saveGithubCache(map);
						reload();
					});
				})
				.exceptionally(error -> {
					UnluckyClientMod.LOGGER.warn("GitHub cape list fetch failed", error);
					return null;
				});
	}

	/** Parses the GitHub contents API array into display-name -> raw-URL for every PNG. */
	private static Map<String, String> parseGithub(String body) {
		Map<String, String> map = new LinkedHashMap<>();
		try {
			JsonElement root = JsonParser.parseString(body);
			if (!root.isJsonArray()) {
				return map; // e.g. a rate-limit message object
			}
			for (JsonElement element : root.getAsJsonArray()) {
				JsonObject obj = element.getAsJsonObject();
				if (!obj.has("type") || !"file".equals(obj.get("type").getAsString())) {
					continue;
				}
				String name = obj.get("name").getAsString();
				if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
					continue;
				}
				JsonElement url = obj.get("download_url");
				if (url == null || url.isJsonNull()) {
					continue;
				}
				String display = name.substring(0, name.length() - ".png".length());
				map.put(display, url.getAsString());
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("GitHub cape list parse failed", e);
		}
		return map;
	}

	private static void saveGithubCache(Map<String, String> map) {
		Path cache = githubCacheFile();
		try {
			Files.createDirectories(cache.getParent());
			Files.writeString(cache, GSON.toJson(map));
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("Cape index cache write failed", e);
		}
	}

	private static Path githubCacheFile() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("unlucky/capes/" + GITHUB_GROUP + "/index.json");
	}

	private static HttpClient http() {
		if (http == null) {
			http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		}
		return http;
	}

	/** All groups (folder names), sorted. */
	/**
	 * Whether a cape group can be shown to <em>other</em> players. Only the two
	 * groups every client can fetch for itself qualify: {@code mojang} (streamed by
	 * texture hash) and {@code unlucky} (the public GitHub repo). Local {@code custom}
	 * PNGs exist on one disk only, so nobody else could ever render them — the
	 * registry rejects them for the same reason.
	 */
	public static boolean isShareable(String group) {
		return STREAM_GROUP.equalsIgnoreCase(group) || GITHUB_GROUP.equalsIgnoreCase(group);
	}

	public static List<String> groups() {
		ensureLoaded();
		Set<String> groups = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (CapeEntry cape : CAPES) {
			groups.add(cape.group);
		}
		return new ArrayList<>(groups);
	}

	/** Picker options for a group: "None" followed by every cape name in it. */
	public static List<String> optionNames(String group) {
		ensureLoaded();
		List<String> names = new ArrayList<>();
		names.add("None");
		for (CapeEntry cape : CAPES) {
			if (cape.group.equalsIgnoreCase(group)) {
				names.add(cape.name);
			}
		}
		return names;
	}

	/**
	 * The texture for a cape, or null if it's a streamed cape still downloading
	 * (the download is kicked off on the first call and cached for next time).
	 */
	public static ClientAsset.Texture textureFor(String group, String name) {
		ensureLoaded();
		CapeEntry entry = null;
		for (CapeEntry cape : CAPES) {
			if (cape.group.equalsIgnoreCase(group) && cape.name.equals(name)) {
				entry = cape;
				break;
			}
		}
		if (entry == null) {
			return null;
		}
		if (entry.resource != null) {
			if (entry.resolved == null) {
				entry.resolved = new ClientAsset.ResourceTexture(entry.resource);
			}
			return entry.resolved;
		}
		if (entry.resolved == null && !entry.requested) {
			entry.requested = true;
			stream(entry);
		}
		return entry.resolved;
	}

	/** Downloads a streamed cape to the config cache and registers it. */
	private static void stream(CapeEntry entry) {
		Minecraft mc = Minecraft.getInstance();
		Path cache = FabricLoader.getInstance().getConfigDir()
				.resolve("unlucky/capes/" + entry.group + "/" + slug(entry.name) + ".png");
		try {
			Files.createDirectories(cache.getParent());
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Cape cache dir failed", e);
			entry.requested = false;
			return;
		}
		downloader(mc).downloadAndRegisterSkin(entry.streamId, cache, entry.url, false)
				.thenAccept(texture -> entry.resolved = texture)
				.exceptionally(error -> {
					UnluckyClientMod.LOGGER.warn("Cape download failed: {}", entry.name, error);
					entry.requested = false; // allow a retry on next selection
					return null;
				});
	}

	private static SkinTextureDownloader downloader(Minecraft mc) {
		if (downloader == null) {
			downloader = new SkinTextureDownloader(mc.getProxy(), mc.getTextureManager(), mc::execute);
		}
		return downloader;
	}

	private static String slug(String name) {
		String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		return s.replaceAll("^_+|_+$", "");
	}

	private static String prettify(String slug) {
		StringBuilder sb = new StringBuilder();
		for (String part : slug.replace('-', '_').split("_")) {
			if (part.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.length() == 0 ? slug : sb.toString();
	}
}
