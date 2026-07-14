package unlucky.utility.client.mixin;

import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Alt switcher: swap the live session at runtime (no restart). Both fields are
 * {@code final}, so a {@code @Mutable @Accessor} is needed. Swapping
 * <b>only</b> {@code user} isn't enough — {@code getGameProfile()} reads the
 * resolved {@code profileFuture} first (set at startup with the original
 * account) and only falls back to {@code user} when it's null, so a server
 * join would use the new access token but the OLD uuid → auth mismatch. The
 * switcher replaces both.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Mutable
	@Accessor("user")
	void unlucky$setUser(User user);

	@Mutable
	@Accessor("profileFuture")
	void unlucky$setProfileFuture(CompletableFuture<ProfileResult> profileFuture);
}
