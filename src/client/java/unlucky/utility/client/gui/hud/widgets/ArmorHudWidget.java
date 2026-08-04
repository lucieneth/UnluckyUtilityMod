package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Worn armor (and optionally the held item) as icons with a durability bar
 * colored green→yellow→red by remaining. Low pieces can pulse below a threshold.
 */
public class ArmorHudWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("ArmorHUD", "Worn armor with durability bars", false));
	public final BooleanSetting bg = add(new BooleanSetting("Armor bg", "Backing behind the armor row", true));
	public final BooleanSetting verticalLayout = add(new BooleanSetting("Armor vertical", "Stack the pieces vertically", false));
	public final BooleanSetting held = add(new BooleanSetting("Armor held item", "Include the main-hand item", true));
	public final BooleanSetting offhand = add(new BooleanSetting("Armor offhand", "Include the off-hand item", true));
	public final BooleanSetting vanillaBars = add(new BooleanSetting("Armor vanilla bar", "Use Minecraft's default durability bar instead of the colored one", true));
	public final BooleanSetting percent = add(new BooleanSetting("Armor percent", "Durability percentage under each piece", false));
	public final NumberSetting blinkPercent = add(new NumberSetting("Armor blink %", "Pulse pieces below this durability (0 = off)", 10, 0, 50, 1));
	public final ModeSetting layout = add(new ModeSetting("Armor layout", "Horizontal row, vertical list, or grid", "Horizontal", "Horizontal", "Vertical", "Grid"));
	public final NumberSetting gridColumns = add(new NumberSetting("Armor grid columns", "Columns used by grid layout", 3, 2, 4, 1));
	public final ModeSetting durabilityText = add(new ModeSetting("Armor durability text", "Text displayed below damageable items", "Off", "Off", "Percent", "Remaining"));
	public final BooleanSetting itemNames = add(new BooleanSetting("Armor item names", "Show compact item names", false));
	public final BooleanSetting enchantAbbreviations = add(new BooleanSetting("Armor enchants", "Show the first abbreviated enchantment", false));

	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int CELL = 18;
	private static final int RED = 0xFFE04545;
	private static final int YELLOW = 0xFFE0C020;
	private static final int GREEN = 0xFF3FD46A;

	public ArmorHudWidget() {
		super("ArmorHUD");
		gridColumns.showWhen(() -> layout.is("Grid"));
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.82);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		if (mc().player == null) {
			setSize(0, 0);
			return;
		}
		boolean forcedHorizontal = horizontalLayout();
		boolean forcedVertical = verticalLayout();
		boolean vertical = forcedVertical || (!forcedHorizontal && (layout.is("Vertical") || (layout.is("Horizontal") && verticalLayout.get())));
		boolean grid = !forcedHorizontal && !forcedVertical && layout.is("Grid");
		boolean pct = percent.get() || durabilityText.is("Percent");
		boolean vanillaBar = vanillaBars.get();
		float blink = (float) (blinkPercent.get() / 100.0);

		List<ItemStack> stacks = new ArrayList<>();
		for (EquipmentSlot slot : ARMOR) {
			stacks.add(mc().player.getItemBySlot(slot));
		}
		if (held.get()) {
			stacks.add(mc().player.getItemBySlot(EquipmentSlot.MAINHAND));
		}
		if (offhand.get()) {
			stacks.add(mc().player.getItemBySlot(EquipmentSlot.OFFHAND));
		}

		int count = 0;
		for (ItemStack s : stacks) {
			if (!s.isEmpty()) {
				count++;
			}
		}
		if (count == 0 && !editing) {
			setSize(0, 0);
			return;
		}
		if (count == 0 && HudManager.isPreviewData()) {
			// Useful editor preview even before the player has equipped any armor.
			stacks.clear();
			stacks.add(new ItemStack(Items.DIAMOND_HELMET));
			stacks.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
			stacks.add(new ItemStack(Items.DIAMOND_LEGGINGS));
			stacks.add(new ItemStack(Items.DIAMOND_BOOTS));
			count = stacks.size();
		}
		if (count == 0) {
			setSize(0, 0);
			return;
		}
		int shown = editing ? stacks.size() : count;

		int extraLines = (pct || durabilityText.is("Remaining") ? 1 : 0) + (itemNames.get() ? 1 : 0) + (enchantAbbreviations.get() ? 1 : 0);
		int textLineHeight = Math.max(9, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int cellW = itemNames.get() || enchantAbbreviations.get() ? 58 : CELL;
		int cellH = CELL + 3 + extraLines * textLineHeight;
		int columns = vertical ? 1 : grid ? Math.min(gridColumns.getInt(), Math.max(shown, 1)) : Math.max(shown, 1);
		int rows = (shown + columns - 1) / columns;
		int width = columns * cellW;
		int height = rows * cellH;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), Math.max(width, 1), Math.max(height, 1), bg.get());

		long now = System.currentTimeMillis();
		int i = 0;
		for (ItemStack s : stacks) {
			if (s.isEmpty() && !editing) {
				continue;
			}
			int cx = getX() + (i % columns) * cellW;
			int cy = getY() + (i / columns) * cellH;
			if (!s.isEmpty()) {
				int iconX = cx + (cellW - 16) / 2;
				g.item(s, iconX, cy + 1);
				int lineY = cy + CELL + 3;
				if (s.isDamageableItem() && s.getMaxDamage() > 0) {
					float rem = Math.clamp(1.0f - (float) s.getDamageValue() / s.getMaxDamage(), 0.0f, 1.0f);
					if (vanillaBar) {
						// Minecraft's own durability bar under the icon (its exact geometry
						// and hue from getBarColor) — no stack-count clutter
						if (s.isBarVisible()) {
							Render2D.rect(g, iconX + 2, cy + 14, 13, 2, 0xFF000000);
							Render2D.rect(g, iconX + 2, cy + 14, s.getBarWidth(), 1, 0xFF000000 | s.getBarColor());
						}
					} else {
						int alpha = 255;
						if (blink > 0 && rem < blink) {
							alpha = (int) (110 + 145 * (0.5 + 0.5 * Math.sin(now / 150.0)));
						}
						int col = ColorUtil.withAlpha(durColor(rem), alpha);
						int barW = CELL - 2;
						Render2D.rect(g, iconX, cy + CELL, barW, 2, 0xA0000000);
						Render2D.rect(g, iconX, cy + CELL, Math.round(barW * rem), 2, col);
					}
					if (pct || durabilityText.is("Remaining")) {
						String t = durabilityText.is("Remaining")
								? Integer.toString(s.getMaxDamage() - s.getDamageValue())
								: (int) (rem * 100) + "%";
						Render2D.text(g, t, cx + (cellW - Render2D.width(t)) / 2, lineY, durColor(rem));
						lineY += textLineHeight;
					}
				}
				if (itemNames.get()) {
					String name = fit(s.getHoverName().getString(), cellW - 2);
					Render2D.text(g, name, cx + (cellW - Render2D.width(name)) / 2, lineY, Theme.textDim);
					lineY += textLineHeight;
				}
				if (enchantAbbreviations.get()) {
					java.util.List<String> chips = unlucky.utility.client.util.GearUtil.enchantChips(s);
					String chip = chips.isEmpty() ? "-" : fit(chips.getFirst(), cellW - 2);
					Render2D.text(g, chip, cx + (cellW - Render2D.width(chip)) / 2, lineY, accentAt(1, 2));
				}
			}
			i++;
		}
	}

	private static String fit(String text, int maxWidth) {
		if (Render2D.width(text) <= maxWidth) return text;
		String out = text;
		while (out.length() > 1 && Render2D.width(out + "…") > maxWidth) out = out.substring(0, out.length() - 1);
		return out + "…";
	}

	private static int durColor(float rem) {
		return rem < 0.5f
				? ColorUtil.lerp(RED, YELLOW, rem * 2)
				: ColorUtil.lerp(YELLOW, GREEN, (rem - 0.5f) * 2);
	}
}
