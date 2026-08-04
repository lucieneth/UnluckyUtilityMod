package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** Counts a chosen item across the inventory (hotbar, main and offhand). */
public class ItemCounterWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("ItemCounter", "Count a chosen item across your inventory", false));
	public final BooleanSetting bg = add(new BooleanSetting("Item count bg", "Backing behind the item counter", true));
	public final ModeSetting item = add(new ModeSetting("Counted item", "Which item to count", "Totem", "Totem", "XP Bottle", "Obsidian", "Ender Pearl", "Gapple", "Held"));
	public final BooleanSetting icon = add(new BooleanSetting("Item count icon", "Show the item icon", true));
	public final NumberSetting warnBelow = add(new NumberSetting("Item count warn", "Turn red below this count (0 = off)", 0, 0, 64, 1));

	public ItemCounterWidget() {
		super("ItemCounter");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.7);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		if (mc().player == null) {
			setSize(0, 0);
			return;
		}
		ItemStack iconStack = iconStack(item.get());
		Item target = iconStack.isEmpty() ? null : iconStack.getItem();
		if (target == null && !editing) {
			setSize(0, 0);
			return;
		}

		int count = target == null ? 0 : countOf(target);
		String text = Integer.toString(count);
		boolean showIcon = icon.get() && !iconStack.isEmpty();
		int warn = warnBelow.getInt();

		int iconW = showIcon ? 18 : 0;
		int content = iconW + Render2D.width(text);
		int height = showIcon ? 18 : 13;
		int width = content + 10;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());

		int x = alignedX(content, 5);
		if (showIcon) {
			g.item(iconStack, x, getY() + (height - 16) / 2);
			x += 18;
		}
		int color = Theme.text;
		if (warn > 0 && count < warn) {
			int pulse = (int) (150 + 105 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 150.0)));
			color = ColorUtil.withAlpha(0xFFE04545, pulse);
		}
		Render2D.text(g, text, x, getY() + (height - Render2D.FONT_HEIGHT) / 2 + 1, color);
	}

	private ItemStack iconStack(String mode) {
		return switch (mode) {
			case "XP Bottle" -> new ItemStack(Items.EXPERIENCE_BOTTLE);
			case "Obsidian" -> new ItemStack(Items.OBSIDIAN);
			case "Ender Pearl" -> new ItemStack(Items.ENDER_PEARL);
			case "Gapple" -> new ItemStack(Items.GOLDEN_APPLE);
			case "Held" -> mc().player.getMainHandItem();
			default -> new ItemStack(Items.TOTEM_OF_UNDYING);
		};
	}

	private int countOf(Item target) {
		Inventory inv = mc().player.getInventory();
		int count = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() == target) {
				count += stack.getCount();
			}
		}
		ItemStack offhand = mc().player.getItemBySlot(EquipmentSlot.OFFHAND);
		if (!offhand.isEmpty() && offhand.getItem() == target) {
			count += offhand.getCount();
		}
		return count;
	}
}
