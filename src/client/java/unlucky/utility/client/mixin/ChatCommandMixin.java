package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.command.CommandManager;
import unlucky.utility.client.module.modules.misc.Greentext;
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

	/**
	 * Greentext, rewriting the outgoing message on its way past.
	 *
	 * <p>This is the second injection at the same HEAD as the command hook above,
	 * and <b>mixin does not order those</b>. That would normally be a trap: if the
	 * rewrite won the race and prefixed {@code >} onto {@code .report}, the command
	 * hook would no longer recognise it and every client command would go out as
	 * public chat instead of running.
	 *
	 * <p>The fix isn't to force an order — it's to make the order stop mattering.
	 * {@link Greentext#apply} skips anything the command hook would claim, so both
	 * sequences produce the same bytes, and neither injection has to know whether it
	 * ran first.
	 */
	@ModifyVariable(method = "sendChat", at = @At("HEAD"), argsOnly = true)
	private String unlucky$greentext(String message) {
		return UnluckyClient.INSTANCE.modules.get(Greentext.class).apply(message);
	}
}
