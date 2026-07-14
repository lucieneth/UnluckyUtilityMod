package unlucky.utility.client.util.alts;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.util.Util;
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
