package unlucky.utility.client.util;

import java.nio.ByteBuffer;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;

import net.minecraft.client.renderer.DynamicGpuDataStorage;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Shader;
import unlucky.utility.client.module.modules.world.ChestManager;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

/**
 * Per-frame values for the ESP composite's {@code EspConfig} block.
 *
 * <p>A post chain's JSON uniforms are baked into a GpuBuffer once, in the PostPass
 * constructor, so anything declared there is frozen for the life of the chain — which
 * made every setting that fed it dead on arrival. Writing the block ourselves each frame
 * through {@link DynamicGpuDataStorage} is how Meteor drives its own outline uniforms,
 * and it is what lets width, fill and mode be live settings instead of constants.
 *
 * <p>The JSON still declares the block: its values act as the fallback for any frame
 * that renders before {@link PostPassMixin} substitutes ours.
 */
public final class EspUniforms {
	private static final int UNIFORM_SIZE = new Std140SizeCalculator()
			.putVec4()
			.putVec4()
			.get();

	// 26.3 turned DynamicGpuDataStorage into an interface with two implementations and
	// made the buffer usage explicit. Mapped is what vanilla uses for every one of its
	// own UBOs, and USAGE_UNIFORM is what this buffer is bound as.
	private static final DynamicGpuDataStorage<Params> STORAGE =
			new DynamicGpuDataStorageMapped<>("Unlucky - ESP composite UBO", UNIFORM_SIZE,
					GpuBuffer.USAGE_UNIFORM, 8);

	private EspUniforms() {
	}

	/** Releases the frame's slices. Must be called once per frame. */
	public static void endFrame() {
		STORAGE.endFrame();
	}

	/**
	 * Current settings as a buffer slice. With the Shader module off, its part is the JSON's
	 * one-pixel outline, so ChestManager's glow still gets its extra width.
	 */
	public static GpuBufferSlice current() {
		Shader shader = UnluckyClient.INSTANCE.modules.get(Shader.class);
		ChestManager chests = UnluckyClient.INSTANCE.modules.get(ChestManager.class);
		if (shader == null || chests == null) {
			return null;
		}
		boolean on = shader.isEnabled();
		return STORAGE.writeData(new Params(
				on ? shader.widthTexels() : 1.0f,
				1.0f,
				on ? shader.fillOpacity() : 0.0f,
				on && shader.mode.is("Glow") ? 1.0f : 0.0f,
				chests.glowExtra.getFloat()));
	}

	private record Params(float width, float borderAlpha, float fillOpacity, float mode, float extraWidth)
			implements DynamicGpuDataStorage.DynamicGpuData {
		@Override
		public void write(ByteBuffer buffer) {
			Std140Builder.intoBuffer(buffer)
					.putFloat(width)
					.putFloat(borderAlpha)
					.putFloat(fillOpacity)
					.putFloat(mode)
					.putFloat(extraWidth)
					.putFloat(0.0f)
					.putFloat(0.0f)
					.putFloat(0.0f);
		}
	}
}
