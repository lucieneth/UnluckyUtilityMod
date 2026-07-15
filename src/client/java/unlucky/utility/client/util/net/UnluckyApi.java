package unlucky.utility.client.util.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import unlucky.utility.client.UnluckyClientMod;

/**
 * The client half of the Unlucky registry.
 *
 * <p>The registry is a public, cosmetic directory — who runs Unlucky and what cape /
 * marker colour they picked. There's no login: each client just publishes its own
 * uuid. The proper Mojang joinServer/hasJoined handshake we'd use to <i>prove</i> that
 * uuid can't run from Cloudflare (Mojang's WAF 403s its datacenter IPs), and since
 * everything here is cosmetic the trade is worth it — a forged write can only put a
 * cape on a uuid that isn't yours, with no token or account access involved. See the
 * server's file header for the full reasoning and the tamper-proof upgrade path.
 */
public final class UnluckyApi {
	/**
	 * The deployed Worker, unless overridden: {@code -Dunlucky.api} wins, then
	 * {@code UNLUCKY_API} in the environment (what run-local-api.bat sets, since a JVM
	 * flag on the gradle command line wouldn't reach the client).
	 */
	private static final String BASE = resolveBase();
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private UnluckyApi() {
	}

	private static String resolveBase() {
		String property = System.getProperty("unlucky.api");
		if (property != null && !property.isBlank()) {
			return property;
		}
		String env = System.getenv("UNLUCKY_API");
		if (env != null && !env.isBlank()) {
			return env;
		}
		return "https://api.unlucky.life";
	}

	/**
	 * Publishes how you present to other Unlucky users: your uuid and name, the cape you
	 * picked and the colour your marker shows in. Off-thread; {@code onError} gets a
	 * message safe to show on screen.
	 */
	public static void setProfile(String cape, int rgb, Consumer<String> onDone, Consumer<String> onError) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getUser() == null) {
			onError.accept("No account");
			return;
		}
		UUID uuid = mc.getUser().getProfileId();
		String name = mc.getUser().getName();
		new Thread(() -> {
			try {
				JsonObject payload = new JsonObject();
				payload.addProperty("uuid", uuid.toString());
				payload.addProperty("name", name);
				payload.addProperty("cape", cape);
				payload.addProperty("color", rgb & 0xFFFFFF);
				JsonObject result = put("/v1/profile", payload);
				onDone.accept(result.get("cape").getAsString());
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.warn("Unlucky registry profile update failed", e);
				onError.accept(friendly(e));
			}
		}, "unlucky-registry-profile").start();
	}

	/** Echoes back what the registry currently has for you — handy from the console. */
	public static void whoami(Consumer<String> onDone, Consumer<String> onError) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getUser() == null) {
			onError.accept("No account");
			return;
		}
		UUID uuid = mc.getUser().getProfileId();
		new Thread(() -> {
			try {
				JsonObject result = users(java.util.List.of(uuid));
				JsonObject usersObj = result.getAsJsonObject("users");
				String key = uuid.toString().replace("-", "");
				if (usersObj == null || !usersObj.has(key)) {
					onDone.accept("not registered yet");
					return;
				}
				JsonObject me = usersObj.getAsJsonObject(key);
				String cape = me.has("cape") ? me.get("cape").getAsString() : "none";
				onDone.accept(mc.getUser().getName() + " (cape: " + cape + ")");
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.warn("Unlucky registry whoami failed", e);
				onError.accept(friendly(e));
			}
		}, "unlucky-registry-whoami").start();
	}

	private static JsonObject put(String path, JsonObject body) throws Exception {
		return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build());
	}

	private static JsonObject get(String path) throws Exception {
		return send(request(path).GET().build());
	}

	private static HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(BASE + path))
				.timeout(Duration.ofSeconds(15))
				.header("content-type", "application/json");
	}

	/**
	 * The public batched lookup — cacheable, one request for a whole tab list.
	 * Blocking: {@link RegistryUsers} calls it off-thread.
	 */
	static JsonObject users(java.util.List<UUID> uuids) throws Exception {
		StringBuilder query = new StringBuilder();
		for (UUID uuid : uuids) {
			if (query.length() > 0) {
				query.append(',');
			}
			query.append(uuid.toString().replace("-", ""));
		}
		return get("/v1/users?uuids=" + query);
	}

	private static JsonObject send(HttpRequest request) throws Exception {
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
		if (response.statusCode() / 100 != 2) {
			String error = json != null && json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
			if (json != null && json.has("groups")) {
				error += " - allowed: " + json.get("groups");
			}
			throw new IllegalStateException(error);
		}
		return json;
	}

	/** Never surface a raw response. Log those, show this. */
	private static String friendly(Exception e) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			return "Registry unreachable";
		}
		if (message.contains("Connection refused")) {
			return "Registry unreachable (is the worker running?)";
		}
		return message;
	}
}
