package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;

/**
 * ElytraPhysics sway: rotates the whole elytra layer as one rigid unit by
 * transforming the PoseStack around the layer's submit. The collector copies
 * the pose when the model is submitted, so push at HEAD / pop at RETURN
 * brackets exactly the elytra draw and nothing else.
 */
@Mixin(WingsLayer.class)
public class WingsLayerMixin {
	@Unique
	private boolean unlucky$swayed;

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
			at = @At("HEAD"))
	private void unlucky$swayBegin(PoseStack poseStack, SubmitNodeCollector collector, int light,
			HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		unlucky$swayed = false;
		ElytraPhysics module = UnluckyClient.INSTANCE.modules.get(ElytraPhysics.class);
		if (!module.isEnabled() || !(state instanceof AvatarRenderState avatar)) {
			return;
		}
		poseStack.pushPose();
		module.applySway(poseStack, avatar);
		unlucky$swayed = true;
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
			at = @At("RETURN"))
	private void unlucky$swayEnd(PoseStack poseStack, SubmitNodeCollector collector, int light,
			HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (unlucky$swayed) {
			poseStack.popPose();
			unlucky$swayed = false;
		}
	}
}
