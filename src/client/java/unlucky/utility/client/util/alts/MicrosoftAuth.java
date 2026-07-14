package unlucky.utility.client.util.alts;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import unlucky.utility.client.UnluckyClientMod;

/**
 * The Microsoft → Xbox → Minecraft login chain, ported from PandoraLauncher's
 * {@code crates/auth} (which the client's default id belongs to). Uses the
 * <b>authorization-code + PKCE</b> flow with a {@code localhost} redirect —
 * NOT device-code — because that is what the app registration is configured
 * for (its redirect URI is {@code http://localhost:3160/auth}, and
 * {@code XboxLive.signin} is a static app permission). We open the browser to
 * Microsoft's sign-in, catch the redirect on a tiny loopback socket, exchange
 * the code, then walk MSA → Xbox Live → XSTS → Minecraft token → profile. No
 * password ever touches this code.
 *
 * <p>The MSA <b>refresh token</b> is saved for silent re-auth on switch
 * ({@link #refresh}). All UI callbacks hop to the client thread.
 */
public final class MicrosoftAuth {
	private static final String AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
	private static final String TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	private static final String XBL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";
	// must match a redirect URI registered on the app (Pandora's): loopback :3160/auth
	private static final int PORT = 3160;
	private static final String REDIRECT_URI = "http://localhost:" + PORT + "/auth";
	private static final String SCOPE = "XboxLive.signin XboxLive.offline_access";
	private static final int LOGIN_TIMEOUT_MS = 180_000;

	private static HttpClient http;

	private MicrosoftAuth() {
	}

	private static HttpClient http() {
		if (http == null) {
			http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
		}
		return http;
	}

	/**
	 * Begin adding a Microsoft account. Opens the browser to Microsoft's sign-in
	 * and waits (up to 3 min) for the redirect on the loopback listener;
	 * {@code onStatus} carries progress messages, then {@code onDone} the ready
	 * account or {@code onError} a message. Everything runs off-thread.
	 */
	public static void addAccount(Consumer<String> onStatus, Consumer<AltAccount> onDone, Consumer<String> onError) {
		String clientId = AltManager.clientId();
		Thread thread = new Thread(() -> {
			try {
				String verifier = randomUrlToken(64);
				String challenge = base64Url(sha256(verifier));
				String state = randomUrlToken(16);
				String authorizeUrl = AUTHORIZE + "?client_id=" + clientId + "&response_type=code"
						+ "&redirect_uri=" + enc(REDIRECT_URI) + "&response_mode=query"
						+ "&scope=" + enc(SCOPE) + "&state=" + state
						+ "&code_challenge=" + challenge + "&code_challenge_method=S256"
						// force the account chooser — without this Microsoft's SSO cookie
						// silently returns the already-signed-in account every time
						+ "&prompt=select_account";
				client(() -> {
					net.minecraft.util.Util.getPlatform().openUri(authorizeUrl);
					onStatus.accept("Sign in with Microsoft in your browser …");
				});

				String code = awaitRedirect(state);
				client(() -> onStatus.accept("Signing in …"));
				JsonObject token = postForm(TOKEN, "client_id=" + clientId + "&scope=" + enc(SCOPE)
						+ "&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT_URI)
						+ "&grant_type=authorization_code&code_verifier=" + verifier);
				AltAccount account = finish(token);
				client(() -> onDone.accept(account));
			} catch (AuthException e) {
				client(() -> onError.accept(e.getMessage()));
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.error("Microsoft login failed", e);
				client(() -> onError.accept("Login failed: " + e.getMessage()));
			}
		}, "unlucky-msa-login");
		thread.setDaemon(true);
		thread.start();
	}

	/** Silent re-auth from a saved refresh token; hands back a refreshed account (new tokens). */
	public static void refresh(AltAccount account, Consumer<AltAccount> onDone, Consumer<String> onError) {
		String clientId = AltManager.clientId();
		Thread thread = new Thread(() -> {
			try {
				JsonObject token = postForm(TOKEN, "client_id=" + clientId + "&scope=" + enc(SCOPE)
						+ "&grant_type=refresh_token&refresh_token=" + enc(account.refreshToken()));
				AltAccount refreshed = finish(token);
				client(() -> onDone.accept(refreshed));
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.error("Microsoft token refresh failed", e);
				client(() -> onError.accept("Re-auth failed: " + e.getMessage()));
			}
		}, "unlucky-msa-refresh");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Listen on the loopback redirect URI for Microsoft's {@code ?code=…}, reply
	 * with a small "you can close this" page, and return the code. Raw socket so
	 * there's no dependency on the optional {@code jdk.httpserver} module.
	 */
	private static String awaitRedirect(String expectedState) throws Exception {
		try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
			server.setSoTimeout(LOGIN_TIMEOUT_MS);
			Socket socket;
			try {
				socket = server.accept();
			} catch (java.net.SocketTimeoutException timeout) {
				throw new AuthException("Timed out waiting for the browser sign-in.");
			}
			try (socket) {
				InputStream in = socket.getInputStream();
				StringBuilder firstLine = new StringBuilder();
				int c;
				while ((c = in.read()) != -1 && c != '\n' && firstLine.length() < 4096) {
					if (c != '\r') {
						firstLine.append((char) c);
					}
				}
				Map<String, String> query = parseQuery(firstLine.toString());
				String body = "<html><body style='font-family:sans-serif;text-align:center;padding-top:3em'>"
						+ "<h2>Unlucky Client</h2><p>Sign-in complete — you can close this tab.</p></body></html>";
				OutputStream out = socket.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
						+ body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body)
						.getBytes(StandardCharsets.UTF_8));
				out.flush();

				if (query.containsKey("error")) {
					throw new AuthException("Sign-in was cancelled or failed (" + query.get("error") + ").");
				}
				if (!expectedState.equals(query.get("state"))) {
					throw new AuthException("Sign-in response didn't match — please retry.");
				}
				String code = query.get("code");
				if (code == null) {
					throw new AuthException("No authorization code returned.");
				}
				return code;
			}
		}
	}

	/** Pull the query pairs out of a request line like {@code GET /auth?code=..&state=.. HTTP/1.1}. */
	private static Map<String, String> parseQuery(String requestLine) {
		Map<String, String> map = new HashMap<>();
		int q = requestLine.indexOf('?');
		int end = requestLine.indexOf(' ', Math.max(q, 0));
		if (q < 0 || end < 0) {
			return map;
		}
		for (String pair : requestLine.substring(q + 1, end).split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return map;
	}

	// --- the chain (off-thread) ---

	/** MSA token -> Xbox -> XSTS -> Minecraft token -> profile -> AltAccount. */
	private static AltAccount finish(JsonObject msaToken) throws Exception {
		String msAccess = require(msaToken, "access_token", "Microsoft token");
		String refreshToken = msaToken.has("refresh_token") ? msaToken.get("refresh_token").getAsString() : null;

		JsonObject xbl = postJson(XBL, "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":"
				+ "\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + msAccess + "\"},"
				+ "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}", "Xbox Live sign-in");
		String xblToken = require(xbl, "Token", "Xbox Live sign-in");
		String uhs = uhs(xbl, "Xbox Live sign-in");

		JsonObject xsts = postJson(XSTS, "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\""
				+ xblToken + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}",
				"Xbox XSTS");
		if (xsts.has("XErr")) {
			throw new AuthException(xstsError(xsts.get("XErr").getAsLong()));
		}
		String xstsToken = require(xsts, "Token", "Xbox XSTS");
		JsonObject xui = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject();
		String xuid = xui.has("xid") ? xui.get("xid").getAsString() : null;

		JsonObject mc = postJson(MC_LOGIN, "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\"}",
				"Minecraft login");
		String mcToken = require(mc, "access_token", "Minecraft login");

		HttpResponse<String> profileRes = send(HttpRequest.newBuilder(URI.create(MC_PROFILE))
				.header("Authorization", "Bearer " + mcToken).GET().build());
		JsonObject profile = JsonParser.parseString(profileRes.body()).getAsJsonObject();
		if (!profile.has("id")) {
			throw new AuthException("This account doesn't own Minecraft (Java Edition).");
		}
		UUID uuid = dashed(profile.get("id").getAsString());
		String name = profile.get("name").getAsString();
		String skinUrl = null;
		if (profile.has("skins") && profile.getAsJsonArray("skins").size() > 0) {
			skinUrl = profile.getAsJsonArray("skins").get(0).getAsJsonObject().get("url").getAsString();
		}
		return new AltAccount(AltAccount.Type.MICROSOFT, name, uuid, mcToken, refreshToken, xuid, skinUrl);
	}

	private static String uhs(JsonObject xboxResponse, String step) {
		try {
			return xboxResponse.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0)
					.getAsJsonObject().get("uhs").getAsString();
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("MSA {} — no user hash in {}", step, xboxResponse);
			throw new AuthException(step + " returned an unexpected response (see the log).");
		}
	}

	// --- http helpers ---

	private static JsonObject postForm(String url, String body) throws Exception {
		HttpResponse<String> res = send(form(url, body));
		JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
		if (res.statusCode() >= 400) {
			String error = json.has("error_description") ? json.get("error_description").getAsString()
					: json.has("error") ? json.get("error").getAsString() : ("HTTP " + res.statusCode());
			UnluckyClientMod.LOGGER.error("MSA token endpoint {}: {}", res.statusCode(), res.body());
			throw new AuthException("Microsoft: " + error.split("\\.")[0]);
		}
		return json;
	}

	private static JsonObject postJson(String url, String body, String step) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
		HttpResponse<String> res = send(request);
		if (res.statusCode() / 100 != 2) {
			UnluckyClientMod.LOGGER.error("MSA {} HTTP {}: {}", step, res.statusCode(), res.body());
			throw new AuthException(step + " failed (HTTP " + res.statusCode() + ") — see the log.");
		}
		return JsonParser.parseString(res.body()).getAsJsonObject();
	}

	private static String require(JsonObject json, String key, String step) {
		com.google.gson.JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			UnluckyClientMod.LOGGER.error("MSA {} — missing '{}' in {}", step, key, json);
			throw new AuthException(step + " returned an unexpected response (see the log).");
		}
		return element.getAsString();
	}

	private static HttpRequest form(String url, String body) {
		return HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
	}

	private static HttpResponse<String> send(HttpRequest request) throws Exception {
		return http().send(request, HttpResponse.BodyHandlers.ofString());
	}

	// --- small utils ---

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String randomUrlToken(int bytes) {
		byte[] buf = new byte[bytes];
		new SecureRandom().nextBytes(buf);
		return base64Url(buf);
	}

	private static byte[] sha256(String s) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
	}

	private static String base64Url(byte[] data) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}

	private static UUID dashed(String undashed) {
		return UUID.fromString(undashed.replaceFirst(
				"(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
	}

	private static String xstsError(long xerr) {
		return switch ((int) (xerr - 2148916230L)) {
			case 3 -> "This Microsoft account has no Xbox profile — create one first.";
			case 5 -> "Xbox Live isn't available in this account's region.";
			case 8 -> "This account is a child; add it to a Family to use Xbox Live.";
			default -> "Xbox sign-in failed (XErr " + xerr + ").";
		};
	}

	private static void client(Runnable action) {
		Minecraft.getInstance().execute(action);
	}

	private static final class AuthException extends RuntimeException {
		AuthException(String message) {
			super(message);
		}
	}
}
