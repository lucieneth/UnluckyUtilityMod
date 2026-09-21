package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.util.FreecamRenderProxy;
import net.minecraft.client.renderer.state.level.PlayerRenderState;

/**
 * The real player can be far outside Freecam's frustum.  Keep it extracted at
 * its true world location, then append one independent state for the nearby
 * translucent spectator-head proxy.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void unlucky$freecamSpectatorHead(Entity entity, Frustum frustum, double cameraX, double cameraY,
			double cameraZ, float partialTicks, long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir) {
		if (entity == Minecraft.getInstance().player
				&& UnluckyClient.INSTANCE.modules.get(Freecam.class).shouldRenderSpectatorHead()) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * The whole outline pass — mask render, post chain, composite — is skipped
	 * unless something in the frame asked for an outline. Mob and player ESP go
	 * through vanilla's per-entity outline color so they set this themselves, but
	 * storage feeds the mask directly and would otherwise render into a target that
	 * never gets composited.
	 */
	// static, and taking the player render state: 26.3 made shouldShowEntityOutlines a
	// static helper and started passing the extracted player state into it. A non-static
	// handler on a static target is refused outright at apply time.
	@Inject(method = "shouldShowEntityOutlines", at = @At("RETURN"), cancellable = true)
	private static void unlucky$forceOutlinesForEsp(Camera camera, PlayerRenderState playerRenderState,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()
				&& UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.render.Shader.class)
						.isEnabled()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "extractVisibleEntities", at = @At("TAIL"))
	private void unlucky$appendFreecamSpectatorHead(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
			LevelRenderState output, CallbackInfo ci) {
		Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
		if (!freecam.isEnabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}

		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(
				!minecraft.level.tickRateManager().isEntityFrozen(player));
		if (minecraft.options.getCameraType().isFirstPerson()) {
			// Vanilla excludes the camera entity in first person.  Freecam's
			// camera is virtual, though, so explicitly submit the ordinary state
			// to leave the real body visible at the position where it was left.
			output.entityRenderStates.add(
					minecraft.levelRenderer.entityRenderDispatcher().extractEntity(player, partialTick));
			output.lastEntityRenderStateCount = output.entityRenderStates.size();
			return;
		}

		if (!freecam.shouldRenderSpectatorHead()) {
			return;
		}
		EntityRenderState proxy = FreecamRenderProxy.extract(
				() -> minecraft.levelRenderer.entityRenderDispatcher().extractEntity(player, partialTick));
		output.entityRenderStates.add(proxy);
		output.lastEntityRenderStateCount = output.entityRenderStates.size();
	}
}
