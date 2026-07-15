package unlucky.utility.client.util.alts;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.util.Util;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.mixin.MinecraftAccessor;

/**
 * Swaps the live Minecraft session to a saved alt without a restart. Replaces
 * both {@code Minecraft.user} and {@code Minecraft.profileFuture} (see
 * {@link MinecraftAccessor} for why both) so server joins use the new account's
 * uuid <em>and</em> token together. Not allowed while connected to a server —
 * the handshake already captured the old session.
 */
public final class AccountSwitcher {
	private AccountSwitcher() {
	}

	/** True only in the main menu / singleplayer — switching mid-multiplayer would desync auth. */
	public static boolean canSwitch() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() == null || mc.hasSingleplayerServer();
	}

	/** The uuid of the account currently in use, for highlighting the active row. */
	public static java.util.UUID activeUuid() {
		return Minecraft.getInstance().getUser().getProfileId();
	}

	public static void switchTo(AltAccount account) {
		Minecraft mc = Minecraft.getInstance();
		Optional<String> xuid = account.xuid() == null ? Optional.empty() : Optional.of(account.xuid());
		User user = new User(account.name(), account.uuid(), account.accessToken(),
				xuid, mc.getUser().getClientId());
		MinecraftAccessor accessor = (MinecraftAccessor) (Object) mc;
		accessor.unlucky$setUser(user);
		accessor.unlucky$setProfileFuture(profileFuture(mc, account));
		rebuildSession(mc, accessor, user, account);
	}

	/**
	 * Rebuilds the account-bound services the game otherwise only builds at startup,
	 * so the new session is authenticated everywhere — not just on offline-mode
	 * servers that trust the raw name. See {@link MinecraftAccessor} for why each one
	 * matters; the short version is that Realms and the registry both verify against
	 * Mojang through {@code userApiService}, and a stale one answers for the launch
	 * account, reading as "invalid session".
	 *
	 * <p>Mirrors vanilla's own construction: a fresh {@code UserApiService} from the
	 * new access token, the properties future derived from it, and the chat-key
	 * manager rebound to the new user. The session service itself isn't touched — it
	 * takes the token per call, so it's account-independent. Offline accounts get a
	 * {@code UserApiService} too; its {@code fetchProperties} simply falls back to the
	 * offline properties, exactly as at a normal offline launch.
	 */
	private static void rebuildSession(Minecraft mc, MinecraftAccessor accessor, User user, AltAccount account) {
		try {
			UserApiService api = new YggdrasilAuthenticationService(mc.getProxy())
					.createUserApiService(account.accessToken());
			accessor.unlucky$setUserApiService(api);
			accessor.unlucky$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
				try {
					return api.fetchProperties();
				} catch (Exception e) {
					// offline account, or the properties call is down: the game runs fine
					// with the offline set, so don't let it block or throw
					return UserApiService.OFFLINE_PROPERTIES;
				}
			}, Util.nonCriticalIoPool()));
			accessor.unlucky$setProfileKeyPairManager(
					ProfileKeyPairManager.create(api, user, mc.gameDirectory.toPath()));
		} catch (Exception e) {
			// never leave the switch half-applied without a trace — the user/profile are
			// already swapped, so at worst online-privilege checks stay stale until restart
			UnluckyClientMod.LOGGER.warn("Alt switch: could not rebuild session services", e);
		}
	}

	/**
	 * The new session's profile, resolved the way vanilla resolves it at startup:
	 * {@code sessionService.fetchProfile(uuid, true)} on the non-critical IO pool.
	 *
	 * <p>The fetch is what carries the <b>textures</b> property, and that matters in
	 * singleplayer: the integrated server builds your ServerPlayer straight from this
	 * profile, so a bare {@code new GameProfile(uuid, name)} — no properties — renders
	 * as Steve. Multiplayer hides the bug, because there the <i>server</i> looks the
	 * textures up by uuid and sends them back in the player-info packet.
	 *
	 * <p>Offline accounts have no textures to fetch, so they keep the bare profile
	 * (and the default skin, correctly).
	 */
	private static CompletableFuture<ProfileResult> profileFuture(Minecraft mc, AltAccount account) {
		GameProfile bare = new GameProfile(account.uuid(), account.name());
		if (!account.isMicrosoft()) {
			return CompletableFuture.completedFuture(new ProfileResult(bare));
		}
		return CompletableFuture.supplyAsync(() -> {
			ProfileResult fetched = mc.services().sessionService().fetchProfile(account.uuid(), true);
			return fetched != null ? fetched : new ProfileResult(bare); // offline / API down
		}, Util.nonCriticalIoPool());
	}
}
