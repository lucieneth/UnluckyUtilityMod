package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.UnluckyUsers;
import unlucky.utility.client.module.modules.player.Capes;

/** Swaps the cape/elytra on your own skin so the vanilla layers render it 1:1. */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void unlucky$cape(CallbackInfoReturnable<PlayerSkin> cir) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		PlayerSkin base = cir.getReturnValue();
		if (base == null) {
			return;
		}

		if (self == Minecraft.getInstance().player) {
			// your own cape: the local Capes module wins, it's the more specific choice
			Capes cape = UnluckyClient.INSTANCE.modules.get(Capes.class);
			if (cape.isEnabled()) {
				PlayerSkin modified = cape.apply(base);
				if (modified != base) {
					cir.setReturnValue(modified);
					return;
				}
			}
		}

		// everyone else: whatever they registered with the Unlucky registry. Cape and
		// elytra share the texture, exactly as official capes do.
		ClientAsset.Texture registered = UnluckyClient.INSTANCE.modules
				.get(UnluckyUsers.class).capeFor(self.getUUID());
		if (registered != null) {
			cir.setReturnValue(new PlayerSkin(base.body(), registered, registered,
					base.model(), base.secure()));
		}
	}
}
