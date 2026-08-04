package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Turns Minecraft's full-frame menu blur into a clipped Future-panel blur.
 *
 * <p>The vanilla GUI renderer gives us one blur operation for a frame. We
	 * snapshot the world just before that operation, save its blurred result, and
	 * restore the sharp world. We then render the saved result back through a
	 * scissor rectangle for each category column before the glass is composited.
 */
public final class FuturePanelBlur {
	private static final List<PanelBounds> PANELS = new ArrayList<>();
	private static TextureTarget sharpCopy;
	private static TextureTarget blurredCopy;
	private static boolean requested;
	private static boolean capturedThisCall;

	private FuturePanelBlur() {
	}

	/** Called while the Future screen extracts its background, before GUI draws are deferred. */
	public static void beginFrame() {
		requested = true;
		capturedThisCall = false;
		PANELS.clear();
		ensureTargets(Minecraft.getInstance().gameRenderer.mainRenderTarget());
	}

	/** Records a GUI-scaled rectangle whose world backdrop should remain blurred. */
	public static void registerPanel(int x, int y, int width, int height) {
		if (requested && width > 0 && height > 0) {
			PANELS.add(new PanelBounds(x, y, width, height));
		}
	}

	/** Invoked immediately before GuiRenderer sends the world through vanilla's blur chain. */
	public static void beforeVanillaBlur() {
		capturedThisCall = false;
		if (!requested || PANELS.isEmpty()) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		ensureTargets(main);
		copyWhole(main, sharpCopy);
		capturedThisCall = true;
	}

	/**
	 * Invoked immediately after vanilla's blur chain. Keep the blurred snapshot
	 * and restore the crisp world. A fullscreen texture pass scissored to each
	 * panel puts the blur back exactly where it belongs.
	 */
	public static void afterVanillaBlur() {
		if (!requested || !capturedThisCall || PANELS.isEmpty() || sharpCopy == null || blurredCopy == null) {
			requested = false;
			capturedThisCall = false;
			PANELS.clear();
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		copyWhole(main, blurredCopy);
		copyWhole(sharpCopy, main);
		blitBlurredPanels(main, minecraft.getWindow().getGuiScale());
		requested = false;
		capturedThisCall = false;
		PANELS.clear();
	}

	private static void ensureTargets(RenderTarget main) {
		if (sharpCopy != null && sharpCopy.width == main.width && sharpCopy.height == main.height) return;
		if (sharpCopy != null) sharpCopy.destroyBuffers();
		if (blurredCopy != null) blurredCopy.destroyBuffers();
		sharpCopy = new TextureTarget("Unlucky Future sharp backdrop", main.width, main.height, false, GpuFormat.RGBA8_UNORM);
		blurredCopy = new TextureTarget("Unlucky Future blurred backdrop", main.width, main.height, false, GpuFormat.RGBA8_UNORM);
	}

	private static void copyWhole(RenderTarget source, RenderTarget destination) {
		int width = Math.min(source.width, destination.width);
		int height = Math.min(source.height, destination.height);
		copy(source, destination, 0, 0, 0, 0, width, height);
	}

	private static void copy(RenderTarget source, RenderTarget destination, int sourceX, int sourceY, int destinationX,
			int destinationY, int width, int height) {
		GpuTexture sourceTexture = source.getColorTexture();
		GpuTexture destinationTexture = destination.getColorTexture();
		if (sourceTexture == null || destinationTexture == null || width <= 0 || height <= 0) return;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(sourceTexture, destinationTexture,
				0, destinationX, destinationY, sourceX, sourceY, width, height);
	}

	/**
	 * Draw the complete blurred texture once per panel but restrict writes with a
	 * framebuffer-space scissor. Unlike texture-to-texture subrect copies, this
	 * is robust on 26.2's OpenGL backend, whose subrect copy endpoint handling is
	 * incorrect for non-zero origins.
	 */
	private static void blitBlurredPanels(RenderTarget main, int guiScale) {
		GpuTextureView mainTexture = main.getColorTextureView();
		GpuTextureView blurTexture = blurredCopy == null ? null : blurredCopy.getColorTextureView();
		if (mainTexture == null || blurTexture == null) return;

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "Unlucky Future panel blur", mainTexture, Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderPipelines.ENTITY_OUTLINE_BLIT);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("InSampler", blurTexture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			for (PanelBounds panel : PANELS) {
				int left = Math.clamp(panel.x * guiScale, 0, main.width);
				int right = Math.clamp((panel.x + panel.width) * guiScale, 0, main.width);
				int top = Math.clamp(panel.y * guiScale, 0, main.height);
				int bottom = Math.clamp((panel.y + panel.height) * guiScale, 0, main.height);
				if (right > left && bottom > top) {
					pass.enableScissor(left, main.height - bottom, right - left, bottom - top);
					pass.draw(3, 1, 0, 0);
				}
			}
		}
	}

	private record PanelBounds(int x, int y, int width, int height) {
	}
}
