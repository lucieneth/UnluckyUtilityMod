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
import unlucky.utility.client.module.modules.misc.AntiToS;
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
		AdBlocker adBlocker = UnluckyClient.INSTANCE.modules.get(AdBlocker.class);
		if (adBlocker.isEnabled() && adBlocker.shouldBlock(contents.getString())) {
			adBlocker.onBlocked();
			ci.cancel();
		}
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component unlucky$censorChat(Component contents, Component contentsAgain, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag) {
		if (source == GuiMessageSource.SYSTEM_CLIENT) {
			return contents;
		}
		AntiToS antiToS = UnluckyClient.INSTANCE.modules.get(AntiToS.class);
		return antiToS.isEnabled() ? antiToS.censor(contents) : contents;
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
		UnluckyClient.INSTANCE.modules.get(Heads.class).tagMessage(message);
	}
}
