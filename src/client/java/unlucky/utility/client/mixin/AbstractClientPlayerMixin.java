package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.player.Cape;

/** Swaps the cape/elytra on your own skin so the vanilla layers render it 1:1. */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void unlucky$cape(CallbackInfoReturnable<PlayerSkin> cir) {
		Cape cape = UnluckyClient.INSTANCE.modules.get(Cape.class);
		if (!cape.isEnabled() || (Object) this != Minecraft.getInstance().player) {
			return;
		}
		PlayerSkin base = cir.getReturnValue();
		PlayerSkin modified = cape.apply(base);
		if (modified != base) {
			cir.setReturnValue(modified);
		}
	}
}
