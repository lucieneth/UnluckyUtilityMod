package unlucky.utility.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;

import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.util.EspUniforms;

/**
 * Feeds the ESP composite live settings.
 *
 * <p>Custom post-chain uniforms are uploaded once when the PostPass is constructed, so
 * the values in {@code entity_outline.json} are frozen for the life of the chain. This
 * swaps our own per-frame slice in for the {@code EspConfig} block as it is bound,
 * leaving every other block — including vanilla's own — untouched.
 *
 * <p>Targets the synthetic lambda inside {@code addToFrame} that binds the uniforms.
 * That name is stable for a given Minecraft build but is not API, so it is the first
 * thing to check if the ESP settings stop responding after a version bump.
 */
@Mixin(PostPass.class)
public class PostPassMixin {
	private static final String ESP_UNIFORM_BLOCK = "EspConfig";

	@Redirect(method = "lambda$addToFrame$1", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"),
			require = 0)
	private void unlucky$liveEspUniforms(RenderPass pass, String name, GpuBuffer buffer) {
		if (ESP_UNIFORM_BLOCK.equals(name)) {
			GpuBufferSlice live = EspUniforms.current();
			if (live != null) {
				pass.setUniform(name, live);
				return;
			}
		}
		pass.setUniform(name, buffer);
	}
}
