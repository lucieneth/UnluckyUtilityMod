package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;

/** Records every entity the local player attacks so the session tracker can approximate kills. */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void unlucky$trackAttack(Player player, Entity target, CallbackInfo ci) {
		if (player == Minecraft.getInstance().player) {
			UnluckyClient.INSTANCE.session.onAttack(target);
		}
	}
}
