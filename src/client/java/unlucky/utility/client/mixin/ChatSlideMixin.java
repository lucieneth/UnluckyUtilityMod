package unlucky.utility.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.util.ChatAnim;

/**
 * The message log's open animation: on the closed→open edge it slides in from the
 * left, easing to rest. Both the always-on log (HUD, {@code BACKGROUND}) and the
 * focused text ({@code ChatScreen}, {@code FOREGROUND}) go through this method, so
 * the translate keeps them in lock-step and the whole log slides as one.
 *
 * <p>This is the "green" half — the messages and their dark backing. The bottom
 * input bar is a separate element handled by {@link ChatInputSlideMixin}. Only the
 * message log lives here, and it deliberately does <b>not</b> nudge the HUD.
 *
 * <p>One-shot: at rest (settled or closed) the offset is exactly zero, so the
 * resting log is never shifted or clipped. Timing is shared via {@link ChatAnim};
 * this method runs every frame (the log always draws), so it drives the edge stamp.
 */
@Mixin(ChatComponent.class)
public class ChatSlideMixin {
	private static final float SLIDE_PX = 12f;

	private static final String EXTRACT = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
			+ "Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V";

	@Inject(method = EXTRACT, at = @At("HEAD"))
	private void unlucky$slideIn(GuiGraphicsExtractor graphics, Font font, int a, int b, int c,
			ChatComponent.DisplayMode mode, boolean restricted, CallbackInfo ci) {
		boolean focused = ((ChatComponent) (Object) this).isChatFocused();
		ChatAnim.noteOpen(focused);
		graphics.pose().pushMatrix();
		graphics.pose().translate(-SLIDE_PX * ChatAnim.entrance(focused), 0f); // -X: in from the left
	}

	@Inject(method = EXTRACT, at = @At("RETURN"))
	private void unlucky$slideOut(GuiGraphicsExtractor graphics, Font font, int a, int b, int c,
			ChatComponent.DisplayMode mode, boolean restricted, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}
}
