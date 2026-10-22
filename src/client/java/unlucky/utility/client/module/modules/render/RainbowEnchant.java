package unlucky.utility.client.module.modules.render;

import net.minecraft.util.Mth;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;

/**
 * The enchantment glint, in a colour you picked — or in all of them.
 *
 * <p><b>It tints; it does not repaint.</b> The glint is vanilla's own scrolling texture, and what
 * this hands the renderer is that texture multiplied by the colour ({@code GlintTint}) — the sweep,
 * the speed, the way it wraps a model and reads under fog are all vanilla's, and only the hue
 * differs. It was the shader's {@code ColorModulator} until 26.3 folded the glint into the item's
 * own pass, where the modulator tints the whole item instead; that is the bug this replaced.
 *
 * <p><b>Rainbow is a phase, not a random walk.</b> The hue is a function of the clock and nothing
 * else, so every enchanted item on screen is the same colour at the same instant and stays smooth
 * across a frame drop. Offsetting per item would look busier and read as a bug the first time two
 * halves of one model disagreed.
 *
 * <p><b>Everything with a glint.</b> {@code RenderSetupMixin} swaps the texture where every render
 * type resolves it, so held items, the hotbar, inventories, dropped stacks, item frames, entities,
 * worn armour, shields and trims are all covered by the one hook.
 */
public class RainbowEnchant extends Module {
	/** Milliseconds for one full trip around the wheel at speed 1. */
	private static final float CYCLE_MS = 4000.0f;

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"One fixed colour, or a smooth cycle through the spectrum",
			"Rainbow", "Static", "Rainbow"));

	public final ColorSetting color = add(new ColorSetting("Color",
			"The glint colour", 0xFFB478FF), () -> mode.is("Static"));

	public final NumberSetting speed = add(new NumberSetting("Speed",
			"Cycles per four seconds", 1.0, 0.1, 5.0, 0.1), () -> mode.is("Rainbow"));
	public final NumberSetting saturation = add(new NumberSetting("Saturation",
			"How strong the colours are", 80, 0, 100, 1), () -> mode.is("Rainbow"));
	public final NumberSetting brightness = add(new NumberSetting("Brightness",
			"How bright the colours are", 100, 10, 100, 1), () -> mode.is("Rainbow"));

	public final NumberSetting opacity = add(new NumberSetting("Opacity",
			"How strongly the glint shows. 100 is vanilla's own strength.", 100, 10, 100, 1));

	public final BooleanSetting hideGlint = add(new BooleanSetting("Hide glint",
			"Draw no glint at all. Wins over every colour setting.", false));

	public RainbowEnchant() {
		super("RainbowEnchant", "Recolours the enchantment glint", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	/**
	 * The colour the glint texture is multiplied by, or 0 to leave vanilla's glint alone.
	 *
	 * <p>Called for every glint texture a draw binds, from {@code GlintTint}. Everything here is
	 * arithmetic on settings; there is no allocation and no lookup.
	 *
	 * @return an opaque ARGB tint, or 0 for "not ours"
	 */
	public int glintColor() {
		if (!isEnabled()) {
			return 0;
		}
		if (hideGlint.get()) {
			// Opaque black, not literal zero, which is "not ours". A black glint adds nothing in
			// either pass: the item shaders add its square, the glint pass blends it onto itself.
			return 0xFF000000;
		}
		int base = mode.is("Static")
				? color.get()
				: ColorUtil.hsb(hue(), saturation.getFloat() / 100.0f,
						brightness.getFloat() / 100.0f, 255);
		float fraction = Mth.clamp(opacity.getFloat() / 100.0f, 0.0f, 1.0f);
		if (fraction >= 1.0f) {
			// Full opacity has to come back as the colour itself: scaling rounds, and a glint one
			// unit under full is a visible dulling on a pale item.
			return ColorUtil.withAlpha(base, 255);
		}
		// Opacity has to ride on the colour, not on the alpha: the item shaders add the glint's
		// colour squared and ignore its alpha, and BlendFunction.GLINT is (SRC_COLOR, ONE), which
		// squares it too. Dimming the colour is the only lever, and because it is squared either
		// way the root here is what makes half the slider look like half the glint.
		float scale = Mth.sqrt(fraction);
		return ColorUtil.argb(255,
				Math.round((base >> 16 & 0xFF) * scale),
				Math.round((base >> 8 & 0xFF) * scale),
				Math.round((base & 0xFF) * scale));
	}

	/** Wall-clock phase, so every glint on screen agrees and a frame drop does not jump it. */
	private float hue() {
		float period = CYCLE_MS / Math.max(0.1f, speed.getFloat());
		return (System.currentTimeMillis() % (long) period) / period;
	}
}
