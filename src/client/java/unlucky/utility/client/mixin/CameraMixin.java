package unlucky.utility.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.module.modules.render.ViewClip;
import unlucky.utility.client.module.modules.visuals.Zoom;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void unlucky$zoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float divisor = UnluckyClient.INSTANCE.modules.get(Zoom.class).fovDivisor();
		if (divisor != 1.0f) {
			cir.setReturnValue(cir.getReturnValueF() / divisor);
		}
	}

	/**
	 * ViewClip distance. alignWithEntity calls getMaxZoom only in its detached
	 * (third-person) branch, passing vanilla's hardcoded 4 — swap in our own.
	 */
	@ModifyArg(method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private float unlucky$viewClipDistance(float vanillaDistance) {
		ViewClip viewClip = UnluckyClient.INSTANCE.modules.get(ViewClip.class);
		return viewClip.isEnabled() ? viewClip.distance.getFloat() : vanillaDistance;
	}

	/** ViewClip pass-through: hand back the asked-for distance without raycasting terrain. */
	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void unlucky$viewClipThroughBlocks(float requested, CallbackInfoReturnable<Float> cir) {
		ViewClip viewClip = UnluckyClient.INSTANCE.modules.get(ViewClip.class);
		if (viewClip.isEnabled() && viewClip.clip.get()) {
			cir.setReturnValue(requested);
		}
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void unlucky$freecam(float partialTicks, CallbackInfo ci) {
		Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
		if (freecam.isEnabled()) {
			// detached makes the local player's own model render
			this.detached = true;
			this.setRotation(freecam.getYaw(), freecam.getPitch());
			this.setPosition(freecam.advance());
		}
	}
}
