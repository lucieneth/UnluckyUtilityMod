package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.NoSlow;
import unlucky.utility.client.module.modules.player.InfiniteInteract;
import unlucky.utility.client.module.modules.world.Scaffold;

/** NoSlow's block-side penalties: cobwebs and the soul sand / honey drag. */
@Mixin(Player.class)
public class PlayerMixin {
	/**
	 * Scaffold joins vanilla's one edge-backoff decision rather than rewriting movement.
	 *
	 * <p>Bridge's SafeWalk says "stay on the surface" even without sneak. Descend says the
	 * opposite, but only after its offset lower platform exists; until then the ordinary sneak
	 * lock remains the guard rail. Keeping both answers in this one RETURN hook avoids a second
	 * mixin competing for the same method and preserves vanilla's own collision/backoff math.
	 */
	@Inject(method = "isStayingOnGroundSurface", at = @At("RETURN"), cancellable = true)
	private void unlucky$scaffoldEdgePolicy(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this != net.minecraft.client.Minecraft.getInstance().player) {
			return;
		}
		Scaffold scaffold = UnluckyClient.INSTANCE.modules.get(Scaffold.class);
		int override = scaffold.groundSurfaceOverride(
				(net.minecraft.client.player.LocalPlayer) (Object) this);
		if (override >= 0) {
			cir.setReturnValue(override == 1);
		}
	}

	private boolean unlucky$isSelf() {
		return (Object) this == Minecraft.getInstance().player;
	}

	@Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
	private void unlucky$noWeb(BlockState state, Vec3 multiplier, CallbackInfo ci) {
		NoSlow noSlow = UnluckyClient.INSTANCE.modules.get(NoSlow.class);
		if (noSlow.isEnabled() && noSlow.web.get() && unlucky$isSelf()) {
			ci.cancel();
		}
	}

	@Inject(method = "getBlockSpeedFactor", at = @At("RETURN"), cancellable = true)
	private void unlucky$noBlockDrag(CallbackInfoReturnable<Float> cir) {
		NoSlow noSlow = UnluckyClient.INSTANCE.modules.get(NoSlow.class);
		// only lift the drag — soul speed and other boosts return > 1 and stay
		if (noSlow.isEnabled() && noSlow.blocks.get() && unlucky$isSelf() && cir.getReturnValueF() < 1.0f) {
			cir.setReturnValue(1.0f);
		}
	}

	/** InfiniteInteract includes its own client-side reach so no second module is required. */
	@Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
	private void unlucky$infiniteBlockTargeting(CallbackInfoReturnable<Double> cir) {
		if (unlucky$isSelf()) {
			InfiniteInteract module = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
			cir.setReturnValue(module.targetingRange(cir.getReturnValueD()));
		}
	}

	@Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
	private void unlucky$infiniteEntityTargeting(CallbackInfoReturnable<Double> cir) {
		if (unlucky$isSelf()) {
			InfiniteInteract module = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
			cir.setReturnValue(module.targetingRange(cir.getReturnValueD()));
		}
	}
}
