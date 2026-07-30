package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.command.CommandManager;
import unlucky.utility.client.util.ChatUtil;

/**
 * Client-side chat commands: a message starting with '.' runs through the console's
 * command set and never reaches the server. Built for the Printer's in-game bug
 * reports — look at the missed block, type {@code .report}, keep playing — but every
 * console command works this way.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChatCommandMixin {
	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void unlucky$chatCommand(String message, CallbackInfo ci) {
		// only a dot followed by a letter is claimed, so ".5 stars" still sends
		if (message.length() > 1 && message.charAt(0) == '.'
				&& Character.isLetter(message.charAt(1))) {
			CommandManager.execute(message.substring(1), ChatUtil::info);
			ci.cancel();
		}
	}
}
