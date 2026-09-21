package unlucky.utility.client.mixin;

import net.minecraft.client.player.ItemActivation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoRender;

/**
 * NoRender's totem pop animation.
 *
 * <p>26.3 moved this off the screen-effect renderer: the animation is now its own
 * player-owned state object, started by {@code LocalPlayer.displayItemActivation}
 * and drawn from it. Cancelling the start is still the whole of the feature — the
 * renderer draws nothing while the activation is inactive.
 */
@Mixin(ItemActivation.class)
public class ItemActivationMixin {
	@Inject(method = "activate", at = @At("HEAD"), cancellable = true)
	private void unlucky$noTotemAnimation(ItemStack stack, RandomSource random, CallbackInfo ci) {
		NoRender module = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		if (module.isEnabled() && module.totemAnimation.get()) {
			ci.cancel();
		}
	}
}
