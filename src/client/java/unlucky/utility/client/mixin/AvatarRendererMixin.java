package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.module.modules.render.PopChams;
import unlucky.utility.client.util.ChamsRenderState;
import unlucky.utility.client.util.FreecamRenderProxy;
import unlucky.utility.client.util.FreecamProxyRenderState;
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
		if (avatar == Minecraft.getInstance().player) {
			// A backstop, not the mechanism. When this was measured, the values here already
			// matched what vanilla built from the spoofed yHeadRot/yBodyRot even while the
			// Printer was flying, so the entity-field path does hold up under movement -
			// which is what three earlier "fixes" here wrongly assumed was broken. It stays
			// because it is free and makes the state agree with the pose no matter what
			// re-derives the entity fields.
			//
			// The gate is "was a rotation asked for recently", in milliseconds: tick-end
			// bookkeeping decides nothing a frame can observe.
			if (RotationManager.hasVisualPose()) {
				// yRot is the head yaw *relative to* bodyRot, so both move together
				float body = RotationManager.getPoseBodyYaw();
				state.bodyRot = body;
				state.yRot = net.minecraft.util.Mth.wrapDegrees(RotationManager.getPoseYaw() - body);
				state.xRot = RotationManager.getPitch();
			}

			Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
			if (freecam.shouldRenderSpectatorHead() && FreecamRenderProxy.isExtracting()) {
				// PlayerModel uses this same flag in real spectator mode: it hides the
				// body/limbs, leaves the head, and AvatarRenderer suppresses layers.
				// This is a separately extracted state; never move the ordinary local
				// player state or the ground body disappears.
				Vec3 eye = freecam.getPosition();
				state.x = eye.x;
				state.y = eye.y - avatar.getEyeHeight();
				state.z = eye.z;
				state.distanceToCameraSq = 0.0;
				state.bodyRot = freecam.getYaw();
				state.yRot = 0.0f;
				state.xRot = freecam.getPitch();
				state.isSpectator = true;
				((FreecamProxyRenderState) state).unlucky$setFreecamProxy(true);
				// Vanilla's invisible-to-other-player path supplies the proper
				// translucent render type and a 15% alpha tint.  Keep the second
				// flag false so this local synthetic proxy is actually submitted.
				state.isInvisible = true;
				state.isInvisibleToPlayer = false;
				state.nameTag = null;
				state.scoreText = null;
			}
		}
		// the uuid is only reachable here — carry the (already faded) pop tint to submit
		((ChamsRenderState) state).unlucky$setPopColor(
				UnluckyClient.INSTANCE.modules.get(PopChams.class).tintFor(avatar.getUUID()));
	}
}
