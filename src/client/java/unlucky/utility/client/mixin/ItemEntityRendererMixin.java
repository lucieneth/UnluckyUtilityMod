package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ItemPhysics;
import unlucky.utility.client.util.ItemPhysicsData;

/**
 * ItemPhysics. Vanilla's submit bobs the item with a {@code translate} and spins
 * it with a Y-axis {@code mulPose}; redirecting just those two leaves the whole
 * model/bundle/stack-count pipeline below them alone.
 */
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {
	/** The entity is only in scope here — stash what submit will need. */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"))
	private void unlucky$captureItemPhysics(ItemEntity entity, ItemEntityRenderState state,
			float partialTick, CallbackInfo ci) {
		ItemPhysicsData data = (ItemPhysicsData) state;
		data.unlucky$setOnGround(entity.onGround());
		data.unlucky$setSpeed((float) entity.getDeltaMovement().length());
		data.unlucky$setSeed(entity.getId());
	}

	@Redirect(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void unlucky$itemLift(PoseStack poseStack, float x, float y, float z,
			ItemEntityRenderState state) {
		ItemPhysics module = UnluckyClient.INSTANCE.modules.get(ItemPhysics.class);
		if (module.isEnabled()) {
			poseStack.translate(x, module.lift((ItemPhysicsData) state, y), z);
		} else {
			poseStack.translate(x, y, z);
		}
	}

	@Redirect(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"))
	private void unlucky$itemRotation(PoseStack poseStack, Quaternionfc vanillaSpin,
			ItemEntityRenderState state) {
		ItemPhysics module = UnluckyClient.INSTANCE.modules.get(ItemPhysics.class);
		if (module.isEnabled()) {
			poseStack.mulPose(module.rotation((ItemPhysicsData) state, state.ageInTicks));
		} else {
			poseStack.mulPose(vanillaSpin);
		}
	}
}
