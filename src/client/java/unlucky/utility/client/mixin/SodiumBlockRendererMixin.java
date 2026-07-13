package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay on Sodium's TERRAIN path. The first attempt hooked
 * {@code AbstractBlockRenderContext.shouldDrawSide}, but terrain meshing never
 * reaches it — {@code BlockRenderer} (the chunk-mesh pipeline) has its own
 * {@code isFaceCulled} fed by {@code prepareCulling}'s cached bitmask, so the
 * hook only affected the non-terrain FRAPI path. Verified in the 0.9.1-beta.3
 * bytecode. The terrain fix is two hooks here:
 * <ul>
 *   <li>{@code renderModel} HEAD: a hidden state cancels outright — the block
 *       contributes no quads at all (covers non-cube models too);</li>
 *   <li>{@code isFaceCulled} HEAD: with XRay active nothing that survived is
 *       culled — kept blocks draw every face, including those pressed against
 *       "opaque" neighbors that just vanished.</li>
 * </ul>
 * Same safety posture as the sibling mixin: string target so the class simply
 * never loads without Sodium, {@code require = 0} so a Sodium rename logs
 * instead of crashing. See also {@code VisGraphMixin} — without it Sodium's
 * occlusion culler (which builds on vanilla VisGraph) still hides every
 * fully-enclosed cave section, which reads as "XRay does nothing" underground.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer")
public abstract class SodiumBlockRendererMixin {
	/**
	 * Uses the {@code *At} XRay checks: {@code active()}/{@code hides()} gate on
	 * a ThreadLocal only the VANILLA section compiler sets, so on Sodium's
	 * meshing threads they are always false — that dead gate is what made the
	 * first working-hook version still hide nothing in game. Hide-proof
	 * verified 2026-07-13: cancel logged from Chunk Render Task Executor.
	 */
	@Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void unlucky$xrayHide(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
			CallbackInfo ci) {
		if (XRay.hidesAt(state, pos)) {
			ci.cancel();
		}
	}
}
