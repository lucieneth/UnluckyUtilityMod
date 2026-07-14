package unlucky.utility.client.util.alts;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One saved account in the alt switcher. Microsoft accounts carry the live
 * Minecraft {@code accessToken} plus the MSA {@code refreshToken} for silent
 * re-auth; offline accounts are just a name (deterministic offline uuid, dummy
 * token) and only work on offline-mode servers. {@code skinUrl} is cached for
 * the preview so we don't re-resolve every open.
 *
 * <p><b>Sensitive:</b> a Microsoft account's tokens grant access to the real
 * account — see {@link AltManager} for the file warning.
 */
public record AltAccount(Type type, String name, UUID uuid, String accessToken,
		String refreshToken, String xuid, String skinUrl) {

	public enum Type {
		MICROSOFT, OFFLINE
	}

	/** A username-only offline alt (standard offline-mode uuid, no real auth). */
	public static AltAccount offline(String name) {
		return new AltAccount(Type.OFFLINE, name, offlineUuid(name), "0", null, null, null);
	}

	/** The vanilla offline-mode uuid derivation, so names map to the same uuid a server would assign. */
	public static UUID offlineUuid(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	public boolean isMicrosoft() {
		return type == Type.MICROSOFT;
	}
}
