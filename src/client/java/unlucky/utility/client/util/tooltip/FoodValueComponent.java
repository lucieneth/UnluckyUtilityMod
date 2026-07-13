package unlucky.utility.client.util.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import unlucky.utility.client.util.Render2D;

/**
 * AppleSkin's food tooltip line: a row of drumsticks for hunger restored
 * (dark outline behind each, half icon for odd points) over a row of 7px
 * saturation icons (their icons.png v=27 strip, quarter-fill buckets; red
 * v=34 strip for rotten food). Over 10 icons collapses to one icon + "x N",
 * like theirs.
 */
public class FoodValueComponent implements ClientTooltipComponent {
	private static final Identifier OUTLINE = Identifier.fromNamespaceAndPath("unlucky", "food/tooltip_hunger_outline");
	private static final Identifier SATURATION = Identifier.fromNamespaceAndPath("unlucky", "food/tooltip_saturation");
	private static final Identifier SATURATION_ROTTEN = Identifier.fromNamespaceAndPath("unlucky", "food/tooltip_saturation_rotten");
	private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
	private static final Identifier FOOD_FULL_ROTTEN = Identifier.withDefaultNamespace("hud/food_full_hunger");
	private static final Identifier FOOD_HALF_ROTTEN = Identifier.withDefaultNamespace("hud/food_half_hunger");
	private static final int MAX_ICONS = 10;
	private static final int TEXT_COLOR = 0xFFA0A0A0;

	private final FoodTooltipData data;

	public FoodValueComponent(FoodTooltipData data) {
		this.data = data;
	}

	private int hungerIcons() {
		return Math.min((int) Math.ceil(data.nutrition() / 2.0f), MAX_ICONS);
	}

	private int saturationIcons() {
		return Math.min((int) Math.ceil(Math.abs(data.saturation()) / 2.0f), MAX_ICONS);
	}

	private String hungerText() {
		return data.nutrition() > MAX_ICONS * 2 ? "x " + data.nutrition() / 2.0f : null;
	}

	private String saturationText() {
		return Math.abs(data.saturation()) > MAX_ICONS * 2 ? "x " + Math.abs(data.saturation()) / 2.0f : null;
	}

	@Override
	public int getWidth(Font font) {
		int hunger = (hungerText() != null ? 1 : hungerIcons()) * 9
				+ (hungerText() != null ? 2 + Render2D.width(hungerText()) : 0);
		int sat = (saturationText() != null ? 1 : saturationIcons()) * 7
				+ (saturationText() != null ? 2 + Render2D.width(saturationText()) : 0);
		return Math.max(hunger, sat);
	}

	@Override
	public int getHeight(Font font) {
		return 9 + 1 + 7 + 3; // AppleSkin: hunger row + gap + saturation row + pad
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		Identifier full = data.rotten() ? FOOD_FULL_ROTTEN : FOOD_FULL;
		Identifier half = data.rotten() ? FOOD_HALF_ROTTEN : FOOD_HALF;
		String hungerText = hungerText();
		int icons = hungerText != null ? 1 : hungerIcons();
		for (int i = 0; i < icons; i++) {
			int ix = x + i * 9;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, OUTLINE, ix, y, 9, 9);
			boolean halfIcon = hungerText == null && i == icons - 1 && data.nutrition() % 2 != 0;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, halfIcon ? half : full, ix, y, 9, 9);
		}
		if (hungerText != null) {
			Render2D.text(g, hungerText, x + 9 + 2, y + 1, TEXT_COLOR);
		}

		int satY = y + 10;
		Identifier strip = data.rotten() ? SATURATION_ROTTEN : SATURATION;
		String satText = saturationText();
		int satIcons = satText != null ? 1 : saturationIcons();
		float sat = Math.abs(data.saturation());
		for (int i = 0; i < satIcons; i++) {
			float frac = satText != null ? 1.0f : sat / 2.0f - i;
			int u = frac >= 1.0f ? 21 : frac > 0.5f ? 14 : frac > 0.25f ? 7 : 0;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, strip, 35, 7, u, 0, x + i * 7, satY, 7, 7);
		}
		if (satText != null) {
			Render2D.text(g, satText, x + 7 + 2, satY, TEXT_COLOR);
		}
	}
}
