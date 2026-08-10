package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoRender;
import unlucky.utility.client.util.WeatherOverrideManager;

/** NoWeather (rain particles + ambient sound) and NoRender (block-break particles). */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	/** Spawns the rain particles and plays the downpour ambience. */
	@Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
	private void unlucky$noRainEffects(CallbackInfo ci) {
		if (!WeatherOverrideManager.weatherEffectsAllowed()) {
			ci.cancel();
		}
	}

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void unlucky$noBreakParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
		NoRender module = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		if (module.isEnabled() && module.breakParticles.get()) {
			ci.cancel();
		}
	}
}
