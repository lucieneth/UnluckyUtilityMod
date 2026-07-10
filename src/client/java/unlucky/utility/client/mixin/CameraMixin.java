package unlucky.utility.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Freecam;
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
