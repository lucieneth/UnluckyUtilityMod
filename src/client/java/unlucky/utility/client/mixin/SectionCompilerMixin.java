package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.module.modules.render.XRay;

/** XRay: skip rendering hidden blocks and open up section occlusion. */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
	// bracket each section's compilation so the xray checks know whether this
	// section is inside the configured radius (distance-capped for performance)
	@org.spongepowered.asm.mixin.injection.Inject(method = "compile", at = @At("HEAD"))
	private void unlucky$xrayBegin(net.minecraft.core.SectionPos sectionPos,
			net.minecraft.client.renderer.chunk.RenderSectionRegion region,
			com.mojang.blaze3d.vertex.VertexSorting vertexSorting,
			net.minecraft.client.renderer.SectionBufferBuilderPack builders,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> cir) {
		// pure math from the section position; the camera comparison happens
		// inside XRay against a main-thread snapshot (no render state on workers)
		XRay.beginSection(sectionPos.minBlockX() + 8, sectionPos.minBlockY() + 8, sectionPos.minBlockZ() + 8);
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "compile", at = @At("RETURN"))
	private void unlucky$xrayEnd(net.minecraft.core.SectionPos sectionPos,
			net.minecraft.client.renderer.chunk.RenderSectionRegion region,
			com.mojang.blaze3d.vertex.VertexSorting vertexSorting,
			net.minecraft.client.renderer.SectionBufferBuilderPack builders,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> cir) {
		XRay.endSection();
	}
	// fabric-renderer-api redirects the tesselateBlock call itself, so we hook
	// one step earlier: hidden blocks report INVISIBLE and never reach either
	// the vanilla or the fabric rendering pipeline
	@Redirect(method = "compile",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
	private RenderShape unlucky$xrayBlocks(BlockState state) {
		return XRay.hides(state) ? RenderShape.INVISIBLE : state.getRenderShape();
	}

	@Redirect(method = "compile",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void unlucky$xrayFluids(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos,
			FluidRenderer.Output output, BlockState state, FluidState fluidState) {
		if (!XRay.hidesFluids()) {
			renderer.tesselate(level, pos, output, state, fluidState);
		}
	}

	@Redirect(method = "compile",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"))
	private boolean unlucky$xrayVisibility(BlockState state) {
		// with xray on, nothing is opaque to the visibility graph — you can
		// see caves and sections that vanilla would occlusion-cull away
		return !XRay.active() && state.isSolidRender();
	}
}
