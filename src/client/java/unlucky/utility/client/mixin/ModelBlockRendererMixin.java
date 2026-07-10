package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay fullbright: force the non-AO tesselation path for listed blocks so their
 * faces render flat, with no ambient-occlusion corner darkening. Together with
 * full light coords and flat directional shade this gives the evenly-lit look.
 */
@Mixin(ModelBlockRenderer.class)
public class ModelBlockRendererMixin {
	@Redirect(method = "tesselateBlock",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;useAmbientOcclusion()Z"))
	private boolean unlucky$xrayNoAo(BlockStateModelPart part) {
		return part.useAmbientOcclusion() && !XRay.fullbrightActive();
	}
}
