package unlucky.utility.client.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * Name → profile resolution for the friends UI/commands. Tries the current
 * server's tablist first (free, exact) and falls back to Mojang's public
 * profile API for offline players. Callbacks run on the client thread.
 */
public final class MojangLookup {
	private static final String API = "https://api.mojang.com/users/profiles/minecraft/";
	private static HttpClient http;

	private MojangLookup() {
	}

	/** Resolves {@code name}; ok(uuid, correctlyCasedName) or fail(message). */
	public static void resolve(String name, BiConsumer<UUID, String> ok, Consumer<String> fail) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			for (var info : mc.getConnection().getOnlinePlayers()) {
				if (info.getProfile().name().equalsIgnoreCase(name)) {
					ok.accept(info.getProfile().id(), info.getProfile().name());
					return;
				}
			}
		}
		if (http == null) {
			http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + name))
				.timeout(Duration.ofSeconds(10)).GET().build();
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> mc.execute(() -> {
					if (response.statusCode() != 200) {
						fail.accept("Player not found: " + name);
						return;
					}
					try {
						JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
						String id = json.get("id").getAsString(); // undashed hex
						UUID uuid = UUID.fromString(id.replaceFirst(
								"(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
						ok.accept(uuid, json.get("name").getAsString());
					} catch (Exception e) {
						fail.accept("Lookup failed for " + name);
					}
				}))
				.exceptionally(error -> {
					mc.execute(() -> fail.accept("Lookup failed (offline?)"));
					return null;
				});
	}
}
