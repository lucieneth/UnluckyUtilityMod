package unlucky.utility.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Alt switcher: swap the live session at runtime (no restart). Every field is
 * {@code final}, so each needs a {@code @Mutable @Accessor}.
 *
 * <p>Swapping {@code user} alone is not enough — the account is threaded through
 * several fields the constructor builds once and never touches again:
 * <ul>
 *   <li>{@code profileFuture} — {@code getGameProfile()} reads this first and only
 *       falls back to {@code user} when it's null, so a server join would otherwise
 *       use the new token with the OLD uuid → auth mismatch.</li>
 *   <li>{@code userApiService} — entitlements, telemetry, the blocklist, and the
 *       online-privilege check Realms gates on. Left stale, it keeps answering for
 *       the account the game launched with, which is why a switched session reads as
 *       "invalid" everywhere that verifies against Mojang (Realms, the registry) even
 *       though offline-mode servers, which trust the raw name, look fine.</li>
 *   <li>{@code userPropertiesFuture} — derived from the above; must be rebuilt with it.</li>
 *   <li>{@code profileKeyPairManager} — the chat-signing keys, bound to the account.</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Mutable
	@Accessor("user")
	void unlucky$setUser(User user);

	@Mutable
	@Accessor("profileFuture")
	void unlucky$setProfileFuture(CompletableFuture<ProfileResult> profileFuture);

	@Mutable
	@Accessor("userApiService")
	void unlucky$setUserApiService(UserApiService userApiService);

	@Mutable
	@Accessor("userPropertiesFuture")
	void unlucky$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

	@Mutable
	@Accessor("profileKeyPairManager")
	void unlucky$setProfileKeyPairManager(ProfileKeyPairManager manager);
}
