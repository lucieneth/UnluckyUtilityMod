package unlucky.utility.client.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;

/** Adds cape-like sway to the elytra wings, on top of the vanilla pose. */
@Mixin(ElytraModel.class)
public class ElytraModelMixin {
	@Shadow
	@Final
	private ModelPart leftWing;
	@Shadow
	@Final
	private ModelPart rightWing;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void unlucky$elytraPhysics(HumanoidRenderState state, CallbackInfo ci) {
		ElytraPhysics module = UnluckyClient.INSTANCE.modules.get(ElytraPhysics.class);
		if (!module.isEnabled() || !(state instanceof AvatarRenderState avatar)) {
			return;
		}
		float i = module.intensity.getFloat();
		// The elytra is TWO wings that pivot apart from the spine, not one cape sheet.
		// The roll axis (zRot) IS their spread, so nudging it just folds the wings into
		// each other. Instead we sway the whole assembly as a rigid unit, applying the
		// SAME offset to both wings so their spread (and mirroring) is untouched:
		//   capeFlap + a little capeLean (forward billow) -> pitch both back/down (xRot)
		//   capeLean2 (sideways sway)                     -> yaw the assembly side to side (yRot)
		float pitch = (float) Math.toRadians(avatar.capeFlap + avatar.capeLean * 0.2f) * 0.35f * i;
		float yaw = (float) Math.toRadians(avatar.capeLean2) * 0.5f * i;
		leftWing.xRot += pitch;
		rightWing.xRot += pitch;
		leftWing.yRot += yaw;
		rightWing.yRot += yaw;
	}
}
