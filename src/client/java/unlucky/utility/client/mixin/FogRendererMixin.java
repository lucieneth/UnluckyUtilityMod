package unlucky.utility.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoFog;
import unlucky.utility.client.module.modules.render.NoRender;

/**
 * Fog removal, shared by NoFog and NoRender. {@code FogData} carries two
 * independent channels, and they must be cleared separately — clearing both for
 * any reason (as this mixin used to) means switching off water fog also wipes
 * your render-distance fog:
 * <ul>
 *   <li><b>renderDistance*</b> — the far fog {@code setupFog} writes directly.
 *       Pulled in close, it's also what makes the Nether/End feel closed in.</li>
 *   <li><b>environmental*</b> — written by vanilla's {@code FogEnvironment}
 *       list: water, lava, powder snow, blindness, darkness.</li>
 * </ul>
 *
 * <p>Ownership: <b>NoFog</b> = fog from <i>where you are</i> (distance, Nether,
 * End). <b>NoRender</b> = fog from <i>what's happening to you</i>.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
	@Unique
	private static final float UNLUCKY$FAR = 1.0e7f;

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void unlucky$noFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level,
			CallbackInfoReturnable<FogData> cir) {
		boolean clearDistance = false;
		boolean clearEnvironmental = false;

		NoFog noFog = UnluckyClient.INSTANCE.modules.get(NoFog.class);
		if (noFog.isEnabled() && level != null) {
			clearDistance |= noFog.distance.get();
			// A hazy dimension is the distance fog pulled in close plus the
			// atmospheric layer on top — clear both or it stays murky.
			boolean hazyDimension = (noFog.nether.get() && level.dimension() == Level.NETHER)
					|| (noFog.end.get() && level.dimension() == Level.END);
			clearDistance |= hazyDimension;
			clearEnvironmental |= hazyDimension;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		NoRender noRender = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		if (noRender.isEnabled() && player != null) {
			clearEnvironmental |= noRender.waterFog.get() && player.isUnderWater();
			clearEnvironmental |= noRender.lavaFog.get() && player.isInLava();
			clearEnvironmental |= noRender.powderSnowFog.get() && player.isInPowderSnow;
			clearEnvironmental |= noRender.blindnessFog.get() && player.hasEffect(MobEffects.BLINDNESS);
			clearEnvironmental |= noRender.darknessFog.get() && player.hasEffect(MobEffects.DARKNESS);
		}

		if (!clearDistance && !clearEnvironmental) {
			return;
		}
		FogData fog = cir.getReturnValue();
		if (clearDistance) {
			fog.renderDistanceStart = UNLUCKY$FAR;
			fog.renderDistanceEnd = UNLUCKY$FAR;
		}
		if (clearEnvironmental) {
			fog.environmentalStart = UNLUCKY$FAR;
			fog.environmentalEnd = UNLUCKY$FAR;
		}
	}
}
