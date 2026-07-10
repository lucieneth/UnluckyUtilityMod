package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;

@Mixin(Minecraft.class)
public abstract class MinecraftTitleMixin {
	@Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	private void unlucky$title(CallbackInfoReturnable<String> cir) {
		Minecraft mc = Minecraft.getInstance();
		String base = cir.getReturnValue();
		ServerData server = mc.getCurrentServer();
		if (server != null && !server.isRealm() && mc.getConnection() != null) {
			base += " [" + server.ip + "]";
		}
		cir.setReturnValue(base + " - " + UnluckyClient.NAME + " Client " + UnluckyClient.VERSION);
	}
}
