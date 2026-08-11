package unlucky.utility.client.util;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;

import net.minecraft.client.renderer.DynamicUniformStorage;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Shader;

/**
 * Per-frame values for the ESP composite's {@code EspConfig} block.
 *
 * <p>A post chain's JSON uniforms are baked into a GpuBuffer once, in the PostPass
 * constructor, so anything declared there is frozen for the life of the chain — which
 * made every setting that fed it dead on arrival. Writing the block ourselves each frame
 * through {@link DynamicUniformStorage} is how Meteor drives its own outline uniforms,
 * and it is what lets width, fill and mode be live settings instead of constants.
 *
 * <p>The JSON still declares the block: its values act as the fallback for any frame
 * that renders before {@link PostPassMixin} substitutes ours.
 */
public final class EspUniforms {
	private static final int UNIFORM_SIZE = new Std140SizeCalculator()
			.putFloat()
			.putFloat()
			.putFloat()
			.putFloat()
			.get();

	private static final DynamicUniformStorage<Params> STORAGE =
			new DynamicUniformStorage<>("Unlucky - ESP composite UBO", UNIFORM_SIZE, 8);

	private EspUniforms() {
	}

	/** Releases the frame's slices. Must be called once per frame. */
	public static void endFrame() {
		STORAGE.endFrame();
	}

	/** Current settings as a buffer slice, or null when the Shader module is off. */
	public static GpuBufferSlice current() {
		Shader shader = UnluckyClient.INSTANCE.modules.get(Shader.class);
		if (shader == null || !shader.isEnabled()) {
			return null;
		}
		return STORAGE.writeUniform(new Params(
				shader.widthTexels(),
				1.0f,
				shader.fillOpacity(),
				shader.mode.is("Glow") ? 1.0f : 0.0f));
	}

	private record Params(float width, float borderAlpha, float fillOpacity, float mode)
			implements DynamicUniformStorage.DynamicUniform {
		@Override
		public void write(ByteBuffer buffer) {
			Std140Builder.intoBuffer(buffer)
					.putFloat(width)
					.putFloat(borderAlpha)
					.putFloat(fillOpacity)
					.putFloat(mode);
		}
	}
}
