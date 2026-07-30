package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
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

	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int CELL = 18;
	private static final int RED = 0xFFE04545;
	private static final int YELLOW = 0xFFE0C020;
	private static final int GREEN = 0xFF3FD46A;

	public ArmorHudWidget() {
		super("ArmorHUD");
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
		boolean vertical = verticalLayout.get();
		boolean pct = percent.get();
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
		int shown = editing ? stacks.size() : count;

		int cellH = CELL + 3 + (pct ? 9 : 0);
		int width = vertical ? CELL : shown * CELL;
		int height = vertical ? shown * cellH : cellH;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), Math.max(width, 1), Math.max(height, 1), 4,
				Theme.hudBg(bg.get()));

		long now = System.currentTimeMillis();
		int i = 0;
		for (ItemStack s : stacks) {
			if (s.isEmpty() && !editing) {
				continue;
			}
			int cx = getX() + (vertical ? 0 : i * CELL);
			int cy = getY() + (vertical ? i * cellH : 0);
			if (!s.isEmpty()) {
				g.item(s, cx + 1, cy + 1);
				if (s.isDamageableItem() && s.getMaxDamage() > 0) {
					float rem = Math.clamp(1.0f - (float) s.getDamageValue() / s.getMaxDamage(), 0.0f, 1.0f);
					if (vanillaBar) {
						// Minecraft's own durability bar under the icon (its exact geometry
						// and hue from getBarColor) — no stack-count clutter
						if (s.isBarVisible()) {
							Render2D.rect(g, cx + 3, cy + 14, 13, 2, 0xFF000000);
							Render2D.rect(g, cx + 3, cy + 14, s.getBarWidth(), 1, 0xFF000000 | s.getBarColor());
						}
					} else {
						int alpha = 255;
						if (blink > 0 && rem < blink) {
							alpha = (int) (110 + 145 * (0.5 + 0.5 * Math.sin(now / 150.0)));
						}
						int col = ColorUtil.withAlpha(durColor(rem), alpha);
						int barW = CELL - 2;
						Render2D.rect(g, cx + 1, cy + CELL, barW, 2, 0xA0000000);
						Render2D.rect(g, cx + 1, cy + CELL, Math.round(barW * rem), 2, col);
					}
					if (pct) {
						String t = (int) (rem * 100) + "%";
						Render2D.text(g, t, cx + (CELL - Render2D.width(t)) / 2, cy + CELL + 3, durColor(rem));
					}
				}
			}
			i++;
		}
	}

	private static int durColor(float rem) {
		return rem < 0.5f
				? ColorUtil.lerp(RED, YELLOW, rem * 2)
				: ColorUtil.lerp(YELLOW, GREEN, (rem - 0.5f) * 2);
	}
}
