package unlucky.utility.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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
import unlucky.utility.client.module.modules.render.Freelook;
import unlucky.utility.client.module.modules.render.ViewClip;
import unlucky.utility.client.module.modules.visuals.Zoom;
import unlucky.utility.client.module.modules.movement.InventoryMove;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	/**
	 * Camera.update runs once per rendered frame, before it reads the player's
	 * rotation in alignWithEntity. Feeding InventoryMove here makes arrow-look
	 * frame-smooth without touching mouse or GUI event handling.
	 */
	@Inject(method = "update", at = @At("HEAD"))
	private void unlucky$inventoryMoveLook(DeltaTracker deltaTracker, CallbackInfo ci) {
		UnluckyClient.INSTANCE.modules.get(InventoryMove.class)
				.updateFrame(deltaTracker.getRealtimeDeltaTicks());
	}

	/** Freelook's per-frame pump: key edges, start/stop, rotation easing. */
	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void unlucky$freelookTick(float partialTicks, CallbackInfo ci) {
		UnluckyClient.INSTANCE.modules.get(Freelook.class).updateFrame();
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void unlucky$zoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float divisor = UnluckyClient.INSTANCE.modules.get(Zoom.class).fovDivisor();
		if (divisor != 1.0f) {
			cir.setReturnValue(cir.getReturnValueF() / divisor);
		}
	}

	/**
	 * Freelook. alignWithEntity has already pointed the camera at the player's
	 * own rotation by now; swapping in our free rotation here — before vanilla's
	 * move() pushes the camera back by getMaxZoom — makes it orbit along the
	 * look direction we steer with the mouse instead of the player's.
	 */
	@Inject(method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private void unlucky$freelookRotation(float partialTicks, CallbackInfo ci) {
		Freelook freelook = UnluckyClient.INSTANCE.modules.get(Freelook.class);
		// MouseHandler gives Freecam priority over Freelook too.  Keeping the
		// camera path in the same order prevents a concurrent Freelook from
		// overwriting Freecam's virtual-eye rotation below.
		if (freelook.isActive() && !UnluckyClient.INSTANCE.modules.get(Freecam.class).isEnabled()) {
			this.setRotation(freelook.renderYaw(), freelook.renderPitch());
		}
	}

	/**
	 * Put vanilla's detached-camera move behind Freecam's virtual eye rather
	 * than behind the physical player.  This is intentionally before
	 * {@code getMaxZoom}: F5 keeps normal terrain clipping and ViewClip's
	 * distance/through-block settings without duplicating either implementation.
	 */
	@Inject(method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private void unlucky$freecamThirdPerson(float partialTicks, CallbackInfo ci) {
		Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
		if (!freecam.isEnabled()) {
			return;
		}

		CameraType type = Minecraft.getInstance().options.getCameraType();
		float yaw = freecam.getYaw();
		float pitch = freecam.getPitch();
		if (type.isMirrored()) {
			yaw += 180.0f;
			pitch = -pitch;
		}
		this.setRotation(yaw, pitch);
		this.setPosition(freecam.advance());
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
		// Detached modes are handled above so vanilla can place the F5 camera
		// behind the virtual head.  First person has no getMaxZoom call, so it
		// needs its virtual eye installed here instead.
		if (freecam.isEnabled() && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
			this.setRotation(freecam.getYaw(), freecam.getPitch());
			this.setPosition(freecam.advance());
		}
	}
}
