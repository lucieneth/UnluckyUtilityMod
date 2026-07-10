package unlucky.utility.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoFog;

@Mixin(FogRenderer.class)
public class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void unlucky$noFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level,
			CallbackInfoReturnable<FogData> cir) {
		NoFog noFog = UnluckyClient.INSTANCE.modules.get(NoFog.class);
		if (!noFog.isEnabled()) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		boolean clear = noFog.distance.get();
		if (player != null) {
			clear |= noFog.water.get() && player.isUnderWater();
			clear |= noFog.lava.get() && player.isInLava();
			clear |= noFog.powderSnow.get() && player.isInPowderSnow;
			clear |= noFog.blindness.get()
					&& (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS));
		}
		if (clear) {
			FogData fog = cir.getReturnValue();
			fog.environmentalStart = 1.0e7f;
			fog.environmentalEnd = 1.0e7f;
			fog.renderDistanceStart = 1.0e7f;
			fog.renderDistanceEnd = 1.0e7f;
		}
	}
}
