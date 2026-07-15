package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;
import unlucky.utility.client.module.modules.render.PopChams;
import unlucky.utility.client.util.ChamsRenderState;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.skinlayers.SkinLayer3DFeature;

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
	/** Attach the 3D skin layer renderer once, when the avatar renderer is built. */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void unlucky$addSkinLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
		@SuppressWarnings("unchecked")
		RenderLayerParent<AvatarRenderState, PlayerModel> parent =
				(RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) this;
		((LivingEntityRendererInvoker) this).unlucky$addLayer(new SkinLayer3DFeature(parent));
	}

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
		// the uuid is only reachable here — carry the (already faded) pop tint to submit
		((ChamsRenderState) state).unlucky$setPopColor(
				UnluckyClient.INSTANCE.modules.get(PopChams.class).tintFor(avatar.getUUID()));
	}
}
