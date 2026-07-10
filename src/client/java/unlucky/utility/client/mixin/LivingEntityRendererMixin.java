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
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Chams;
import unlucky.utility.client.util.ChamsRenderState;
import unlucky.utility.client.util.ChamsRenderType;

/** Re-submits the entity model as a tinted chams silhouette, in the same transform as the real model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Shadow
	protected EntityModel model;

	@Shadow
	public abstract Identifier getTextureLocation(LivingEntityRenderState state);

	// runs just before the model transform is popped, so the chams pass lands in the same pose
	@Inject(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", ordinal = 0))
	private void unlucky$chams(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo ci) {
		ChamsRenderState carrier = (ChamsRenderState) state;
		int color = carrier.unlucky$getChamsColor();
		int outline = carrier.unlucky$getSpinOutlineColor();
		if (color == 0 && outline == 0) {
			return;
		}
		Identifier texture = getTextureLocation(state);
		if (color != 0) {
			Chams chams = UnluckyClient.INSTANCE.modules.get(Chams.class);
			if (chams.csgo.get()) {
				// two-tone: behind-wall parts in the wall color, in-sight parts in the base color
				submitChams(collector, state, poseStack, ChamsRenderType.occluded(texture), chams.wallArgb());
				submitChams(collector, state, poseStack, ChamsRenderType.visible(texture), color);
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
