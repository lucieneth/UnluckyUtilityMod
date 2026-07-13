package unlucky.utility.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.Heads;

/** Chat heads, draw half (focused chat — see {@link ChatGraphicsBackgroundMixin}). */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess")
public class ChatGraphicsFocusedMixin {
	@Shadow
	@Final
	private GuiGraphicsExtractor graphics;

	@Inject(method = "handleMessage", at = @At("HEAD"))
	private void unlucky$drawHead(int textTop, float opacity, FormattedCharSequence message, CallbackInfoReturnable<Boolean> cir) {
		Heads.drawChatHead(graphics, message, textTop, opacity);
	}
}
