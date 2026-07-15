package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Chams;
import unlucky.utility.client.util.ChamsRenderState;
import unlucky.utility.client.util.ChamsRenderType;

/**
 * Chams rendering. Two strategies:
 * <ul>
 *   <li><b>Image / Portal</b> modes swap the model's <em>own</em> render type via
 *       {@code getRenderType} (Meteor-style, in place) so the model renders <b>once</b>
 *       as the screen-space galaxy / end-portal starfield — a single render means no
 *       coincident re-draw, hence no z-fighting and a pixel-perfect 1:1 silhouette.</li>
 *   <li><b>Flat / CS:GO</b> modes overlay a tint by re-submitting the model in the same
 *       pose (they want the skin visible underneath), so they stay on the submit hook.</li>
 * </ul>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Shadow
	protected EntityModel model;

	@Shadow
	public abstract Identifier getTextureLocation(LivingEntityRenderState state);

	// Image mode: replace the model's render type so it draws once as the galaxy (no z-fight)
	@Inject(method = "getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;",
			at = @At("RETURN"), cancellable = true)
	private void unlucky$chamsType(LivingEntityRenderState state, boolean visible, boolean visibleToSelf, boolean glowing,
			CallbackInfoReturnable<RenderType> cir) {
		if (cir.getReturnValue() == null || ((ChamsRenderState) state).unlucky$getChamsColor() == 0) {
			return; // invisible, or not a chams target
		}
		Chams chams = UnluckyClient.INSTANCE.modules.get(Chams.class);
		if (chams.mode.is("Image")) {
			cir.setReturnValue(ChamsRenderType.image(chams.throughWalls.get()));
		} else if (chams.mode.is("Portal")) {
			cir.setReturnValue(ChamsRenderType.portal(chams.throughWalls.get()));
		} else if (chams.mode.is("CS:GO")) {
			// CS:GO replaces the skin with a solid colour: hide the real model here, then
			// unlucky$chams draws the solid two-tone (no skin showing through).
			cir.setReturnValue(null);
		}
	}

	// runs just before the model transform is popped, so the chams pass lands in the same pose
	@Inject(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", ordinal = 0))
	private void unlucky$chams(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo ci) {
		ChamsRenderState carrier = (ChamsRenderState) state;
		int color = carrier.unlucky$getChamsColor();
		int outline = carrier.unlucky$getSpinOutlineColor();
		int pop = carrier.unlucky$getPopColor();
		if (color == 0 && outline == 0 && pop == 0) {
			return;
		}
		Identifier texture = getTextureLocation(state);
		// PopChams rides the same re-submit as chams — through walls, because the
		// whole point is to catch a pop you'd otherwise miss behind cover.
		if (pop != 0) {
			submitChams(collector, state, poseStack, ChamsRenderType.get(texture, true), pop);
		}
		Chams chams = UnluckyClient.INSTANCE.modules.get(Chams.class);
		// Image/Portal modes are handled in-place by unlucky$chamsType; only the overlay tints re-submit
		if (color != 0 && !chams.inPlaceMode()) {
			if (chams.mode.is("CS:GO")) {
				// solid two-tone over the hidden skin: behind-wall parts in the wall colour,
				// in-sight parts in the base colour, both a flat colour via the white texture
				submitChams(collector, state, poseStack, ChamsRenderType.occluded(ChamsRenderType.WHITE), chams.wallArgb());
				submitChams(collector, state, poseStack, ChamsRenderType.visible(ChamsRenderType.WHITE), color);
			} else {
				submitChams(collector, state, poseStack, ChamsRenderType.get(texture, chams.throughWalls.get()), color);
			}
		}
		// Spinbot ghost: a through-wall silhouette re-pointed to the real facing.
		// The base model is posed at state.bodyRot, so rotating by (realYaw -
		// bodyRot) about the vertical centerline lands it on the true angle.
		if (outline != 0) {
			float delta = carrier.unlucky$getSpinOutlineYaw() - state.bodyRot;
			poseStack.pushPose();
			poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(delta)));
			submitChams(collector, state, poseStack, ChamsRenderType.get(texture, true), outline);
			poseStack.popPose();
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private void submitChams(SubmitNodeCollector collector, LivingEntityRenderState state, PoseStack poseStack,
			RenderType type, int color) {
		collector.submitModel((Model) model, state, poseStack, type, 0xF000F0, OverlayTexture.NO_OVERLAY,
				color, null, 0, null);
	}
}
