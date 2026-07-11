package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;
import unlucky.utility.client.util.RotationManager;

/**
 * Two render-state tweaks on the player model:
 * <ul>
 *   <li>ElytraPhysics wing spread via {@code elytraRotZ} (the mirrored spread axis).</li>
 *   <li>Silent-aim <b>pitch</b> for the local model. RotationManager already sets
 *       {@code yHeadRot}/{@code yBodyRot}, so head/body yaw follow the spoof — but
 *       the render state's {@code xRot} comes from {@code getXRot()}, the shared
 *       <em>camera</em> pitch, which we must not move (that would break "silent").
 *       So the third-person model always aimed at body height. Overriding
 *       {@code state.xRot} here makes it actually tilt at the target's head/feet,
 *       first-person camera untouched.</li>
 * </ul>
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void unlucky$renderState(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		ElytraPhysics module = UnluckyClient.INSTANCE.modules.get(ElytraPhysics.class);
		if (module.isEnabled()) {
			state.elytraRotZ -= module.wingSpread(state);
		}
		if (avatar == Minecraft.getInstance().player && RotationManager.isSpoofing()) {
			state.xRot = RotationManager.getPitch();
		}
	}
}
