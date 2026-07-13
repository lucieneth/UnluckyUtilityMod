package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay: with the module active no block is opaque to the section-visibility
 * graph, so fully-enclosed caves stay renderable instead of being
 * occlusion-culled. This is the engine-agnostic root: vanilla resolves
 * section visibility from VisGraph inside SectionCompiler, and <b>Sodium's
 * meshing task uses this same vanilla class</b> — so one hook opens the
 * graph for both pipelines (our SectionCompiler-side isSolidRender redirect
 * only covered vanilla). Sections rebuild on toggle via the module's
 * {@code allChanged()}, so the graph refreshes immediately.
 */
@Mixin(VisGraph.class)
public class VisGraphMixin {
	@Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true)
	private void unlucky$xraySeeThrough(BlockPos pos, CallbackInfo ci) {
		// enabled(), not active(): the range ThreadLocal is never set on Sodium's
		// meshing threads, and the vis graph should open everywhere regardless of
		// the XRay distance cap (distance limits hiding, not sight lines)
		if (XRay.enabled()) {
			ci.cancel();
		}
	}
}
