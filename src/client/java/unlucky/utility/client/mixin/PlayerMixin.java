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

/** NoSlow's block-side penalties: cobwebs and the soul sand / honey drag. */
@Mixin(Player.class)
public class PlayerMixin {
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
}
