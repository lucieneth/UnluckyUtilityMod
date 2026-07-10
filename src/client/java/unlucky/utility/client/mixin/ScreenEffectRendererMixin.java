package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoRender;

/**
 * NoRender's screen overlays. {@code submit} dispatches to one submitter per
 * overlay (view-blocking block, water, fire), so each toggle cancels exactly
 * one of them. The totem's item-activation animation lives here too.
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
	@Unique
	private static NoRender unlucky$noRender() {
		return UnluckyClient.INSTANCE.modules.get(NoRender.class);
	}

	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void unlucky$noFireOverlay(PoseStack poseStack, SubmitNodeCollector collector,
			TextureAtlasSprite sprite, CallbackInfo ci) {
		NoRender module = unlucky$noRender();
		if (module.isEnabled() && module.fireOverlay.get()) {
			ci.cancel();
		}
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void unlucky$noBlockOverlay(TextureAtlasSprite sprite, PoseStack poseStack,
			SubmitNodeCollector collector, int light, CallbackInfo ci) {
		NoRender module = unlucky$noRender();
		if (module.isEnabled() && module.blockOverlay.get()) {
			ci.cancel();
		}
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void unlucky$noWaterOverlay(Minecraft minecraft, PoseStack poseStack,
			SubmitNodeCollector collector, CallbackInfo ci) {
		NoRender module = unlucky$noRender();
		if (module.isEnabled() && module.waterOverlay.get()) {
			ci.cancel();
		}
	}

	@Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
	private void unlucky$noTotemAnimation(ItemStack stack, RandomSource random, CallbackInfo ci) {
		NoRender module = unlucky$noRender();
		if (module.isEnabled() && module.totemAnimation.get()) {
			ci.cancel();
		}
	}
}
