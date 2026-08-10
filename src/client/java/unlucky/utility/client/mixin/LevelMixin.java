package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.util.WeatherOverrideManager;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.TimeChanger;

/**
 * NoWeather. Zeroing the rain/thunder levels hides the falling rain, the sky
 * darkening and the weather fog tint at once.
 *
 * <p><b>{@code Level} is a common class</b> — in singleplayer the integrated
 * server's {@code ServerLevel} runs through this same code, so every hook is
 * gated on "am I the client's level". Without that, we'd be lying to the server
 * about its own weather.
 */
@Mixin(Level.class)
public class LevelMixin {
	@Unique
	private boolean unlucky$isClientLevel() {
		return (Object) this == Minecraft.getInstance().level;
	}

	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
	private void unlucky$noRain(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (unlucky$isClientLevel()) {
			cir.setReturnValue(WeatherOverrideManager.rainLevel(cir.getReturnValue()));
		}
	}

	@Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
	private void unlucky$noThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (unlucky$isClientLevel()) {
			cir.setReturnValue(WeatherOverrideManager.thunderLevel(cir.getReturnValue()));
		}
	}

	@Inject(method = "getDefaultClockTime", at = @At("RETURN"), cancellable = true)
	private void unlucky$clientTime(CallbackInfoReturnable<Long> cir) {
		if (unlucky$isClientLevel()) {
			cir.setReturnValue(UnluckyClient.INSTANCE.modules.get(TimeChanger.class)
					.override(cir.getReturnValue()));
		}
	}

	/** The white full-screen flash a lightning strike triggers. */
	@Inject(method = "setSkyFlashTime", at = @At("HEAD"), cancellable = true)
	private void unlucky$noLightningFlash(int time, CallbackInfo ci) {
		if (unlucky$isClientLevel() && !WeatherOverrideManager.skyFlashAllowed()) {
			ci.cancel();
		}
	}
}
