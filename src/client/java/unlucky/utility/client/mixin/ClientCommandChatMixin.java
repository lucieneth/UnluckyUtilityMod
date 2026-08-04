package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.gui.chat.ClientCommandChatUi;

/**
 * Routes only dot-command completion input to {@link ClientCommandChatUi};
 * regular messages and vanilla slash commands continue through ChatScreen's
 * normal CommandSuggestions path untouched.
 */
@Mixin(ChatScreen.class)
public abstract class ClientCommandChatMixin {
	@Shadow protected EditBox input;

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void unlucky$clientCommandKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (ClientCommandChatUi.keyPressed(input, event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void unlucky$clientCommandClick(MouseButtonEvent event, boolean doubleClick,
			CallbackInfoReturnable<Boolean> cir) {
		if (ClientCommandChatUi.mouseClicked(input, event, input.getY() + input.getHeight())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void unlucky$clientCommandScroll(double mouseX, double mouseY, double scrollX, double scrollY,
			CallbackInfoReturnable<Boolean> cir) {
		if (ClientCommandChatUi.mouseScrolled(input, mouseX, mouseY, scrollY, input.getY() + input.getHeight())) {
			cir.setReturnValue(true);
		}
	}
}
