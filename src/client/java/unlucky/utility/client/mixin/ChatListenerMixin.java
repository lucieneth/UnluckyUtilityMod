package unlucky.utility.client.mixin;

import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.Heads;

/**
 * Chat heads, sender half: {@code showMessageToPlayer} is the one spot where
 * the signed sender UUID is in scope right before vanilla hands the decorated
 * component to {@code ChatComponent.addPlayerMessage} (synchronously — the
 * delayed-message queue wraps the whole call, not its body). Stash it; the
 * ChatComponent mixin consumes it once per displayed message.
 */
@Mixin(ChatListener.class)
public class ChatListenerMixin {
	@Inject(method = "showMessageToPlayer", at = @At("HEAD"))
	private void unlucky$captureSender(ChatType.Bound boundChatType, PlayerChatMessage message, Component decoratedMessage,
			GameProfile sender, boolean onlyShowSecure, Instant received, CallbackInfoReturnable<Boolean> cir) {
		Heads.stashSender(message.sender());
	}
}
