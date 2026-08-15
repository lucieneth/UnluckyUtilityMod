package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.util.ChamsRenderState;
import unlucky.utility.client.util.ChamsRenderType;

/**
 * Keeps the cape visible on top of a chams silhouette.
 *
 * <p>The cape is an ordinary render layer, submitted while the model is being drawn; the chams
 * re-submit happens afterwards, at {@code popPose}. So the silhouette lands on top and the cape
 * simply disappears into it — which is a shame, because the cape is the one part of a player
 * worth still being able to identify at a glance.
 *
 * <p><b>Fixed by order, not by depth.</b> 26.2's submit storage keys its collections by an
 * integer order and walks them in ascending sequence, so asking for a later order is the
 * supported way to say "after that". Re-pointing the cape at the through-walls chams pipeline at
 * the same time keeps the two consistent: a silhouette you can see through a wall with a cape
 * that vanishes at the wall would be worse than either.
 *
 * <p>The tint is left alone — the cape draws in its own colours over the flat silhouette, which
 * is the entire point of the request.
 */
@Mixin(CapeLayer.class)
public class CapeLayerMixin {
	/**
	 * The order the cape is moved to.
	 *
	 * <p>One step past the default collection the model and the chams pass both submit into.
	 * Deliberately the smallest step that works rather than a large number: every order above
	 * this one is something else's to claim later, and jumping to 100 to "be safe" would be
	 * claiming all of them.
	 */
	private static final int AFTER_CHAMS = 1;

	@Redirect(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
	private void unlucky$capeOverChams(SubmitNodeCollector collector, Model model, Object modelState,
			PoseStack poseStack, RenderType renderType, int light, int overlay, int outlineColor,
			ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
		int chams = modelState instanceof ChamsRenderState carrier ? carrier.unlucky$getChamsColor() : 0;
		if (chams == 0 || !(modelState instanceof AvatarRenderState state) || state.skin == null) {
			collector.submitModel(model, modelState, poseStack, renderType, light, overlay,
					outlineColor, crumblingOverlay);
			return;
		}
		Identifier texture = state.skin.cape().texturePath();
		collector.order(AFTER_CHAMS).submitModel(model, modelState, poseStack,
				ChamsRenderType.get(texture, true), light, overlay, outlineColor, crumblingOverlay);
	}
}
