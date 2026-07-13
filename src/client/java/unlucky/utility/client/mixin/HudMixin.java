package unlucky.utility.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoRender;

/**
 * Two vanilla-HUD tweaks:
 * <ul>
 *   <li>NoRender's carved-pumpkin overlay: in 26.2 the head-worn camera overlay is
 *       data-driven ({@code Equippable.cameraOverlay()}) and drawn through
 *       {@code extractTextureOverlay}, which fires <em>only</em> for that (spyglass
 *       has its own path) — cancelling it removes the pumpkin vignette.</li>
 *   <li>Chat-clear: {@code extractHotbarAndDecorations} draws the whole bottom
 *       cluster (hotbar, health, food, armor, air, XP/contextual bar, held-item
 *       name), so wrapping it with a pose translate slides them all up a little
 *       while chat is open, clearing the input bar. Sustained + eased, mirroring
 *       the chat slide animations; works in creative and survival alike.</li>
 * </ul>
 */
@Mixin(Hud.class)
public class HudMixin {
	private static final float CHAT_CLEAR_SHIFT = 16f;
	private static float unlucky$hudShift;
	private static long unlucky$hudShiftNanos;

	@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void unlucky$noPumpkinOverlay(GuiGraphicsExtractor graphics, Identifier texture, float alpha, CallbackInfo ci) {
		NoRender module = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		if (module.isEnabled() && module.pumpkinOverlay.get()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void unlucky$clearChatStart(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		boolean chatOpen = ((Hud) (Object) this).getChat().isChatFocused();
		long now = System.nanoTime();
		float dt = unlucky$hudShiftNanos == 0L ? 0f : Math.min((now - unlucky$hudShiftNanos) / 1.0e9f, 0.1f);
		unlucky$hudShiftNanos = now;
		float target = chatOpen ? -CHAT_CLEAR_SHIFT : 0f; // negative Y = up
		unlucky$hudShift += (target - unlucky$hudShift) * (1f - (float) Math.exp(-14f * dt));
		if (Math.abs(target - unlucky$hudShift) < 0.3f) {
			unlucky$hudShift = target;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(0f, unlucky$hudShift);
	}

	@Inject(method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
	private void unlucky$clearChatEnd(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}
}
