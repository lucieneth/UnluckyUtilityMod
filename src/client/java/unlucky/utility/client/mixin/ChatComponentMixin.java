package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.AdBlocker;
import unlucky.utility.client.module.modules.misc.BetterChat;
import unlucky.utility.client.module.modules.player.DonkeyRitual;
import unlucky.utility.client.module.modules.misc.AntiToS;
import unlucky.utility.client.module.modules.misc.ChatTag;
import unlucky.utility.client.module.modules.render.Heads;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), cancellable = true)
	private void unlucky$filterChat(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		// consume the ChatListener sender stash even when this message ends up
		// cancelled/filtered — a blocked line must not donate its chat head to
		// the next one (Heads)
		Heads.beginMessage();
		// never touch our own client messages
		if (source == GuiMessageSource.SYSTEM_CLIENT) {
			return;
		}
		// DonkeyRitual is mid-performance: the server's command replies would give the
		// whole thing away, so they go in the bin while it runs. SYSTEM_SERVER only —
		// player chat is a different source and keeps coming through, because a ritual
		// that also mutes your friends is a bug rather than a flourish.
		if (source == GuiMessageSource.SYSTEM_SERVER && DonkeyRitual.swallowingServerChat()) {
			ci.cancel();
			return;
		}
		AdBlocker adBlocker = UnluckyClient.INSTANCE.modules.get(AdBlocker.class);
		if (adBlocker.isEnabled() && adBlocker.shouldBlock(contents.getString())) {
			adBlocker.onBlocked();
			ci.cancel();
			return;
		}
		BetterChat betterChat = UnluckyClient.INSTANCE.modules.get(BetterChat.class);
		if (betterChat.isEnabled() && betterChat.shouldHide(contents, source)) {
			ci.cancel();
		}
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component unlucky$censorChat(Component contents, Component contentsAgain, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag) {
		Component result = contents;
		// AntiToS and ChatTag stay off our own client output — censoring or highlighting a
		// line this client wrote is at best pointless. BetterChat does not: a timestamp that
		// covers everything except your own command replies is a timestamp with holes in it,
		// and its filtering declines SYSTEM_CLIENT on its own.
		if (source != GuiMessageSource.SYSTEM_CLIENT) {
			AntiToS antiToS = UnluckyClient.INSTANCE.modules.get(AntiToS.class);
			result = antiToS.isEnabled() ? antiToS.censor(result) : result;
			// censor first, then highlight — chained here in one handler rather than as a
			// second @ModifyVariable, since mixin doesn't order injections into one method
			ChatTag chatTag = UnluckyClient.INSTANCE.modules.get(ChatTag.class);
			result = chatTag.isEnabled() ? chatTag.highlight(result) : result;
		}
		// BetterChat goes last: its timestamp must not be part of the text the other two
		// match against, and its duplicate key must describe the line as they leave it.
		BetterChat betterChat = UnluckyClient.INSTANCE.modules.get(BetterChat.class);
		return betterChat.isEnabled() ? betterChat.transform(result, source) : result;
	}

	/**
	 * Chat heads: attach the sender (captured by ChatListenerMixin, or guessed
	 * from the text) to the GuiMessage right before it splits into display
	 * lines — GuiMessageMixin reads it during the split.
	 */
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V"))
	private void unlucky$tagSender(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag,
			CallbackInfo ci, @Local GuiMessage message) {
		// ChatTag pings here rather than at HEAD so blocked/filtered messages stay
		// silent, and goes first because it peeks the sender tagMessage consumes
		ChatTag chatTag = UnluckyClient.INSTANCE.modules.get(ChatTag.class);
		if (chatTag.isEnabled() && source != GuiMessageSource.SYSTEM_CLIENT) {
			chatTag.onMessageShown(message, Heads.currentSender());
		}
		UnluckyClient.INSTANCE.modules.get(Heads.class).tagMessage(message);
		UnluckyClient.INSTANCE.modules.get(BetterChat.class).tagMessage(message);
	}
}
