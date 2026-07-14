package unlucky.utility.client.mixin;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.SkinLayers3D;

/**
 * 3DSkinLayers: hide the flat vanilla overlay parts so
 * {@code SkinLayer3DFeature} can replace them with voxel geometry instead of
 * doubling up. {@code setupAnim} is where vanilla sets each overlay part's
 * visibility from the render state's {@code show*} flags, so overriding at TAIL
 * flips the enabled ones off — but only when the module will actually draw the
 * 3D version (same gate the layer uses: enabled, in range, mesh buildable).
 * The parts keep their animated transform; only {@code visible} changes.
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void unlucky$hideFlatLayers(AvatarRenderState state, CallbackInfo ci) {
		SkinLayers3D module = UnluckyClient.INSTANCE.modules.get(SkinLayers3D.class);
		if (!module.replaces(state) || !module.meshesFor(state).usable()) {
			return;
		}
		PlayerModel self = (PlayerModel) (Object) this;
		if (module.head.get() && state.showHat) {
			self.hat.visible = false;
		}
		if (module.body.get() && state.showJacket) {
			self.jacket.visible = false;
		}
		if (module.arms.get()) {
			if (state.showLeftSleeve) {
				self.leftSleeve.visible = false;
			}
			if (state.showRightSleeve) {
				self.rightSleeve.visible = false;
			}
		}
		if (module.legs.get()) {
			if (state.showLeftPants) {
				self.leftPants.visible = false;
			}
			if (state.showRightPants) {
				self.rightPants.visible = false;
			}
		}
	}
}
