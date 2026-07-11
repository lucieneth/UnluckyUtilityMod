package unlucky.utility.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.util.ChatAnim;

/**
 * The "red" half: the bottom input bar (the dark {@code fill} you type into, plus
 * its EditBox and command suggestions) rises up from the bottom edge when chat
 * opens, sharing {@link ChatAnim}'s one-shot timing with the message-log slide.
 *
 * <p>{@code ChatScreen.extractRenderState} draws three things in order: the input
 * bar background, then the <em>foreground message text</em> (via
 * {@code ChatComponent.extractRenderState}), then the EditBox and suggestions. We
 * want only the first and last to ride up — the message text is "green" and already
 * gets its own left-slide in {@link ChatSlideMixin}, and translating it here too
 * would double-move it (and desync it from the always-on log). So the pose translate
 * is bracketed <b>around</b> the message call: push+translate at HEAD, pop right
 * before the message call, push+translate right after it, pop at RETURN.
 */
@Mixin(ChatScreen.class)
public class ChatInputSlideMixin {
	private static final float SLIDE_PX = 16f; // bar height + margin, so it starts fully off-screen

	private static final String EXTRACT = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
	private static final String CHAT_CALL = "Lnet/minecraft/client/gui/components/ChatComponent;extractRenderState("
			+ "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;III"
			+ "Lnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V";

	@Inject(method = EXTRACT, at = @At("HEAD"))
	private void unlucky$riseBarStart(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(0f, unlucky$offset()); // +Y: start below rest, rise up
	}

	// stop the rise before the foreground message text (green) is drawn
	@Inject(method = EXTRACT, at = @At(value = "INVOKE", target = CHAT_CALL, shift = At.Shift.BEFORE))
	private void unlucky$riseBarPauseForMessages(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}

	// resume the rise for the EditBox + command suggestions
	@Inject(method = EXTRACT, at = @At(value = "INVOKE", target = CHAT_CALL, shift = At.Shift.AFTER))
	private void unlucky$riseBarResume(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(0f, unlucky$offset());
	}

	@Inject(method = EXTRACT, at = @At("RETURN"))
	private void unlucky$riseBarEnd(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}

	private static float unlucky$offset() {
		return SLIDE_PX * ChatAnim.entrance(true); // the screen only renders while open
	}
}
