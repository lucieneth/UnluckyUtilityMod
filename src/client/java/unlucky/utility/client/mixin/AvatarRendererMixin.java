package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;

/**
 * ElytraPhysics wing spread: nudges elytraRotZ — the wings' actual spread
 * axis, mirrored for both wings by the model itself — from the cape motion.
 * This is the only per-wing rotation the module does; the sway is a rigid
 * PoseStack transform in WingsLayerMixin.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void unlucky$wingSpread(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		ElytraPhysics module = UnluckyClient.INSTANCE.modules.get(ElytraPhysics.class);
		if (module.isEnabled()) {
			state.elytraRotZ -= module.wingSpread(state);
		}
	}
}
