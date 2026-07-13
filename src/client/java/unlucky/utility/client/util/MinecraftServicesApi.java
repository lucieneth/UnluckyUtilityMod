package unlucky.utility.client.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * The real account skin/cape API — {@code api.minecraftservices.com} — with the
 * in-game session token ({@code mc.getUser().getAccessToken()}) as the bearer.
 * Exactly the calls Pandora Launcher makes (verified against its source):
 * multipart POST for skin uploads, JSON POST for by-URL, PUT/DELETE for the
 * active cape, GET profile for the owned-capes list. Nothing here is spoofed —
 * changes land on the Mojang account. All callbacks hop to the client thread.
 *
 * <p>Two footnotes for the UI: the per-IP rate limit is 200 req / 2 min, so
 * the profile is fetched once per screen visit; and a mid-session skin change
 * shows to <i>others</i> only after they next see you join (Mojang hands
 * textures out at join) — locally we refresh instantly.
 */
public final class MinecraftServicesApi {
	private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
	private static final String SKINS = PROFILE + "/skins";
	private static final String SKIN_ACTIVE = SKINS + "/active";
	private static final String CAPE_ACTIVE = PROFILE + "/capes/active";
	private static final String SESSION = "https://sessionserver.mojang.com/session/minecraft/profile/";

	private static HttpClient http;

	private MinecraftServicesApi() {
	}

	/** One owned cape from the profile. */
	public record Cape(String id, String alias, String url, boolean active) {
	}

	/** The account profile slice the skins screen needs. */
	public record Profile(String name, String skinUrl, boolean slim, List<Cape> capes) {
	}

	// --- reads ---

	/** GET the authenticated profile: current skin + every owned cape. */
	public static void fetchProfile(Consumer<Profile> ok, Consumer<String> fail) {
		send(authed(PROFILE).GET(), response -> {
			JsonObject json = JsonParser.parseString(response).getAsJsonObject();
			String skinUrl = null;
			boolean slim = false;
			if (json.has("skins")) {
				for (var e : json.getAsJsonArray("skins")) {
					JsonObject skin = e.getAsJsonObject();
					if ("ACTIVE".equals(str(skin, "state"))) {
						skinUrl = str(skin, "url");
						slim = "SLIM".equalsIgnoreCase(str(skin, "variant"));
					}
				}
			}
			List<Cape> capes = new ArrayList<>();
			if (json.has("capes")) {
				for (var e : json.getAsJsonArray("capes")) {
					JsonObject cape = e.getAsJsonObject();
					capes.add(new Cape(str(cape, "id"), str(cape, "alias"), str(cape, "url"),
							"ACTIVE".equals(str(cape, "state"))));
				}
			}
			return new Profile(str(json, "name"), skinUrl, slim, capes);
		}, ok, fail);
	}

	/**
	 * Another player's current skin URL + model, from the session server
	 * (base64 {@code textures} property) — feeds "copy from player".
	 */
	public static void fetchSkinOf(UUID player, BiConsumer<String, Boolean> ok, Consumer<String> fail) {
		HttpRequest.Builder request = HttpRequest.newBuilder(
				URI.create(SESSION + player.toString().replace("-", ""))).timeout(Duration.ofSeconds(10));
		send(request.GET(), response -> {
			JsonObject json = JsonParser.parseString(response).getAsJsonObject();
			JsonArray properties = json.getAsJsonArray("properties");
			for (var e : properties) {
				JsonObject property = e.getAsJsonObject();
				if (!"textures".equals(str(property, "name"))) {
					continue;
				}
				String decoded = new String(Base64.getDecoder().decode(str(property, "value")), StandardCharsets.UTF_8);
				JsonObject skin = JsonParser.parseString(decoded).getAsJsonObject()
						.getAsJsonObject("textures").getAsJsonObject("SKIN");
				boolean slim = skin.has("metadata")
						&& "slim".equals(str(skin.getAsJsonObject("metadata"), "model"));
				return new String[]{str(skin, "url"), slim ? "y" : ""};
			}
			throw new IllegalStateException("no textures");
		}, result -> ok.accept(result[0], !result[1].isEmpty()), fail);
	}

	// --- writes ---

	/** POST a skin the account should wear, by public URL. */
	public static void setSkinByUrl(String url, boolean slim, Runnable ok, Consumer<String> fail) {
		JsonObject body = new JsonObject();
		body.addProperty("variant", slim ? "slim" : "classic");
		body.addProperty("url", url);
		send(authed(SKINS).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())), r -> r, r -> ok.run(), fail);
	}

	/** POST a skin PNG from disk (multipart, like the launcher upload). */
	public static void uploadSkin(Path file, boolean slim, Runnable ok, Consumer<String> fail) {
		byte[] png;
		try {
			png = Files.readAllBytes(file);
		} catch (Exception e) {
			fail.accept("Could not read " + file.getFileName());
			return;
		}
		String boundary = "unlucky" + System.nanoTime();
		byte[] head = ("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
				+ (slim ? "slim" : "classic") + "\r\n"
				+ "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] body = new byte[head.length + png.length + tail.length];
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy(png, 0, body, head.length, png.length);
		System.arraycopy(tail, 0, body, head.length + png.length, tail.length);
		send(authed(SKINS).header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)), r -> r, r -> ok.run(), fail);
	}

	/** DELETE the custom skin — back to the default Steve/Alex. */
	public static void resetSkin(Runnable ok, Consumer<String> fail) {
		send(authed(SKIN_ACTIVE).DELETE(), r -> r, r -> ok.run(), fail);
	}

	/** PUT the active cape by id, or DELETE it when {@code capeId} is null (hide). */
	public static void setCape(String capeId, Runnable ok, Consumer<String> fail) {
		HttpRequest.Builder request = authed(CAPE_ACTIVE);
		if (capeId == null) {
			request.DELETE();
		} else {
			JsonObject body = new JsonObject();
			body.addProperty("capeId", capeId);
			request.header("Content-Type", "application/json")
					.PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
		}
		send(request, r -> r, r -> ok.run(), fail);
	}

	// --- plumbing ---

	private static HttpRequest.Builder authed(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + Minecraft.getInstance().getUser().getAccessToken());
	}

	private static String str(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}

	/** Fires the request, parses on a worker, delivers on the client thread. */
	private static <T> void send(HttpRequest.Builder request, Parser<T> parser, Consumer<T> ok, Consumer<String> fail) {
		if (http == null) {
			http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		}
		Minecraft mc = Minecraft.getInstance();
		http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() / 100 != 2) {
						mc.execute(() -> fail.accept(errorOf(response)));
						return;
					}
					T parsed;
					try {
						parsed = parser.parse(response.body());
					} catch (Exception e) {
						mc.execute(() -> fail.accept("Unexpected response from Mojang"));
						return;
					}
					mc.execute(() -> ok.accept(parsed));
				})
				.exceptionally(error -> {
					mc.execute(() -> fail.accept("Request failed (offline?)"));
					return null;
				});
	}

	/** Mojang error bodies carry a human {@code errorMessage}; fall back to the status. */
	private static String errorOf(HttpResponse<String> response) {
		try {
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			String message = str(json, "errorMessage");
			if (!message.isEmpty()) {
				return message;
			}
		} catch (Exception ignored) {
		}
		return response.statusCode() == 401
				? "Not authenticated (offline session?)"
				: "Mojang API error " + response.statusCode();
	}

	@FunctionalInterface
	private interface Parser<T> {
		T parse(String body) throws Exception;
	}
}
