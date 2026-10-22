package unlucky.utility.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.RainbowEnchant;

/**
 * RainbowEnchant's glint: tinted copies of the two glint textures, handed to every render type
 * that samples one ({@code RenderSetupMixin}).
 *
 * <p><b>Why the texture and not the colour modulator.</b> 26.2 drew the glint as its own pass
 * ({@code glint.fsh}), so the draw's {@code ColorModulator} tinted the glint and nothing else.
 * 26.3 folds it into the item's own pass: {@code item.fsh} and {@code entity.fsh} sample a
 * {@code GlintSampler} and add {@code (GlintAlpha * glint)²} onto a colour that has already been
 * multiplied by {@code ColorModulator}. The modulator now tints the item and leaves the glint
 * vanilla purple — which is exactly what RainbowEnchant did on 26.3 until this. A tinted texture
 * adds {@code (GlintAlpha * glint * tint)²}, which is 26.2's result under
 * {@code BlendFunction.GLINT} to the pixel, and the separate glint pass that shields and armour
 * trims still use samples the same texture, so one mechanism covers both.
 *
 * <p><b>Source pixels come from the resource manager</b>, so a resource pack's glint is the one
 * tinted. The copy is rebuilt when the vanilla texture object changes (a resource reload) and
 * recoloured only when the tint does: once for a static colour, once a frame while it cycles —
 * 128×128 pixels, twice.
 */
public final class GlintTint {
	private static final Map<Identifier, Tinted> TINTED = new HashMap<>();

	private GlintTint() {
	}

	/** Called for every texture a render type binds; everything but the two glints passes through. */
	public static AbstractTexture swap(Identifier location, AbstractTexture original) {
		if (!location.equals(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
				&& !location.equals(ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR)) {
			return original;
		}
		int tint = UnluckyClient.INSTANCE.modules.get(RainbowEnchant.class).glintColor();
		if (tint == 0) {
			return original;
		}
		Tinted tinted = TINTED.get(location);
		if (tinted == null || tinted.source != original) {
			if (tinted != null) {
				tinted.close();
				TINTED.remove(location);
			}
			tinted = Tinted.load(location, original);
			if (tinted == null) {
				return original; // unreadable: an untinted glint beats a missing one
			}
			TINTED.put(location, tinted);
		}
		tinted.recolor(tint);
		return tinted.texture;
	}

	/** One glint's copy: the pixels it was made from, and the texture the tint lives in. */
	private static final class Tinted {
		/** Vanilla's texture object when this was built; a different one means a reload. */
		final AbstractTexture source;
		final NativeImage original;
		final TintedTexture texture;
		int tint;

		private Tinted(AbstractTexture source, NativeImage original, TintedTexture texture) {
			this.source = source;
			this.original = original;
			this.texture = texture;
		}

		static Tinted load(Identifier location, AbstractTexture source) {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
			if (resource.isEmpty()) {
				return null;
			}
			try (InputStream in = resource.get().open()) {
				NativeImage original = NativeImage.read(in);
				NativeImage pixels = new NativeImage(original.getWidth(), original.getHeight(), false);
				pixels.copyFrom(original);
				return new Tinted(source, original,
						new TintedTexture("unlucky tinted " + location, pixels, source.getSampler()));
			} catch (IOException e) {
				return null;
			}
		}

		/**
		 * Each pixel's brightness times the tint, alpha untouched.
		 *
		 * <p>Brightness — the strongest channel — rather than the colour channel by channel,
		 * because the vanilla glint is purple: multiplied through, green keeps only the glint's
		 * thin green channel and comes out near black (measured: a sixth of the purple glint's
		 * strength). Taking the brightness keeps the glint's pattern and strength and lets the
		 * tint be the hue.
		 */
		void recolor(int argb) {
			if (argb == tint) {
				return;
			}
			tint = argb;
			int red = argb >> 16 & 0xFF;
			int green = argb >> 8 & 0xFF;
			int blue = argb & 0xFF;
			NativeImage pixels = texture.getPixels();
			for (int y = 0; y < original.getHeight(); y++) {
				for (int x = 0; x < original.getWidth(); x++) {
					int source = original.getPixel(x, y);
					int brightness = Math.max(source >> 16 & 0xFF, Math.max(source >> 8 & 0xFF, source & 0xFF));
					pixels.setPixel(x, y, ColorUtil.argb(source >>> 24,
							brightness * red / 255, brightness * green / 255, brightness * blue / 255));
				}
			}
			texture.upload();
		}

		void close() {
			texture.close();
			original.close();
		}
	}

	/** A DynamicTexture sampled the way the glint it copies is: repeating, and blurred. */
	private static final class TintedTexture extends DynamicTexture {
		TintedTexture(String label, NativeImage pixels, GpuSampler sampler) {
			super(() -> label, pixels);
			this.sampler = sampler;
		}
	}
}
