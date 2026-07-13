package unlucky.utility.client.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay under Sodium. Sodium's chunk mesher never touches the vanilla paths our
 * other XRay hooks live on ({@code Block.shouldRenderFace}, SectionCompiler),
 * so with Sodium installed XRay did nothing. Its per-face cull decision is
 * {@code AbstractBlockRenderContext.shouldDrawSide(Direction)} (self state/pos
 * are fields on the context), which lets one hook do both jobs:
 * <ul>
 *   <li>meshing a hidden block → cull every face (the block vanishes);</li>
 *   <li>meshing a kept block → draw every face, even against "opaque"
 *       neighbors that XRay is about to hide.</li>
 * </ul>
 *
 * <p>String target + {@code require = 0}: without Sodium the class never loads
 * and the mixin silently never applies; if a Sodium update renames the method
 * we log instead of crashing (XRay then just doesn't work under Sodium again).
 * Verified against sodium-fabric-0.9.1-beta.3+mc26.2 (26.2 ships mojmap names
 * at runtime, so the Direction descriptor matches dev and prod alike).
 * Sodium's own section occlusion graph still culls fully-enclosed caves —
 * distant hidden pockets may pop in as you approach; fluid faces keep vanilla
 * behavior only without Sodium (its DefaultFluidRenderer is separate, unhooked).
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext")
public abstract class SodiumBlockRenderContextMixin {
	@Shadow(remap = false)
	protected BlockState state;
	@Shadow(remap = false)
	protected net.minecraft.core.BlockPos pos;

	// *At variants: the plain active()/hides() gates sit behind a ThreadLocal only
	// the vanilla section compiler sets — permanently false on Sodium's threads.
	@Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void unlucky$xraySides(Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (XRay.activeAt(pos)) {
			cir.setReturnValue(!XRay.hidesAt(state, pos));
		}
	}

	/**
	 * Belt and braces with shouldDrawSide: terrain culling flows through
	 * prepareCulling's cached mask and this query — declared HERE, not on
	 * BlockRenderer (targeting it there made the whole sibling mixin fail to
	 * apply, which is why the first two attempts did nothing in game: one
	 * invalid injection aborts the entire mixin, and require=0 hid it).
	 * All three hooks log-verified alive on the Chunk Render Task Executor
	 * threads with sodium-fabric-0.9.1-beta.3 (2026-07-12).
	 */
	@Inject(method = "isFaceCulled", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void unlucky$xrayFaces(Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (XRay.activeAt(pos) && !XRay.hidesAt(state, pos)) {
			cir.setReturnValue(false);
		}
	}
}
