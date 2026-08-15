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
import unlucky.utility.client.module.modules.combat.Reach;
import unlucky.utility.client.module.modules.movement.NoSlow;
import unlucky.utility.client.module.modules.movement.SafeWalk;
import unlucky.utility.client.module.modules.player.InfiniteInteract;
import unlucky.utility.client.module.modules.world.Scaffold;

/** NoSlow's block-side penalties: cobwebs and the soul sand / honey drag. */
@Mixin(Player.class)
public class PlayerMixin {
	/**
	 * Scaffold and SafeWalk join vanilla's one edge-backoff decision rather than rewriting movement.
	 *
	 * <p>Bridge's SafeWalk says "stay on the surface" even without sneak. Descend says the
	 * opposite, but only after its offset lower platform exists; until then the ordinary sneak
	 * lock remains the guard rail. Keeping both answers in this one RETURN hook avoids a second
	 * mixin competing for the same method and preserves vanilla's own collision/backoff math.
	 *
	 * <p>The standalone SafeWalk module is the third opinion and it is reconciled the same way —
	 * inside {@link SafeWalk#edgePolicy}, next to the precedence setting that describes the rule —
	 * rather than by adding an injection that would race this one.
	 */
	@Inject(method = "isStayingOnGroundSurface", at = @At("RETURN"), cancellable = true)
	private void unlucky$scaffoldEdgePolicy(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this != net.minecraft.client.Minecraft.getInstance().player) {
			return;
		}
		net.minecraft.client.player.LocalPlayer player =
				(net.minecraft.client.player.LocalPlayer) (Object) this;
		Scaffold scaffold = UnluckyClient.INSTANCE.modules.get(Scaffold.class);
		int override = UnluckyClient.INSTANCE.modules.get(SafeWalk.class)
				.edgePolicy(player, cir.getReturnValueZ(), scaffold.groundSurfaceOverride(player));
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
		if (unlucky$isSelf() && noSlow.cancelStuck(state)) {
			ci.cancel();
		}
	}

	@Inject(method = "getBlockSpeedFactor", at = @At("RETURN"), cancellable = true)
	private void unlucky$noBlockDrag(CallbackInfoReturnable<Float> cir) {
		NoSlow noSlow = UnluckyClient.INSTANCE.modules.get(NoSlow.class);
		// only lift the drag — soul speed and other boosts return > 1 and stay
		if (unlucky$isSelf() && cir.getReturnValueF() < 1.0f) {
			Player self = (Player) (Object) this;
			BlockState state = self.level().getBlockState(self.blockPosition().below());
			if (noSlow.cancelBlockDrag(state)) {
				cir.setReturnValue(noSlow.multiplier());
			}
		}
	}

	/**
	 * One owner for the interaction ranges. InfiniteInteract deliberately wins while enabled:
	 * applying Reach after its 128-block value would make the two distance modules compose.
	 */
	@Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
	private void unlucky$infiniteBlockTargeting(CallbackInfoReturnable<Double> cir) {
		if (unlucky$isSelf()) {
			InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
			if (infinite.isEnabled()) {
				cir.setReturnValue(infinite.targetingRange(cir.getReturnValueD()));
			} else {
				cir.setReturnValue(UnluckyClient.INSTANCE.modules.get(Reach.class)
						.blockRange(cir.getReturnValueD()));
			}
		}
	}

	@Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
	private void unlucky$infiniteEntityTargeting(CallbackInfoReturnable<Double> cir) {
		if (unlucky$isSelf()) {
			InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
			if (infinite.isEnabled()) {
				cir.setReturnValue(infinite.targetingRange(cir.getReturnValueD()));
			} else {
				cir.setReturnValue(UnluckyClient.INSTANCE.modules.get(Reach.class)
						.entityRange(cir.getReturnValueD()));
			}
		}
	}
}
