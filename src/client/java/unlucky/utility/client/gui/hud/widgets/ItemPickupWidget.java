package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.Render2D;

/**
 * A sliding list of items the local player just picked up: icon, colored name
 * and a running count. Repeat pickups bump the count and refresh the timer.
 * Newest sits at the top and older rows stack below; rows slide in/out toward
 * whichever screen edge the widget is docked against.
 */
public class ItemPickupWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Item pickups", "Sliding list of items you pick up", false));
	public final BooleanSetting backing = add(new BooleanSetting("Pickups bg", "Backing behind each pickup row", true));
	public final NumberSetting duration = add(new NumberSetting("Pickups duration", "Seconds each pickup row stays", 3, 1, 10, 1));
	public final NumberSetting historySize = add(new NumberSetting("Pickups history", "Maximum pickup rows kept on screen", 6, 1, 20, 1));
	public final ModeSetting style = add(new ModeSetting("Pickups style", "Full item cards or compact text notifications", "Cards", "Cards", "Compact"));
	public final ColorSetting textColor = add(new ColorSetting("Pickups text color", "Color of pickup names", Theme.text));

	private static final int ROW_H = 20;
	private static final int GAP = 2;

	private final List<Pickup> pickups = new ArrayList<>();

	public ItemPickupWidget() {
		super("ItemPickups");
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.62);
	}

	/** Called from the pickup packet hook. */
	public void onPickup(ItemStack stack, int amount) {
		if (stack.isEmpty() || amount <= 0) {
			return;
		}
		synchronized (pickups) {
			for (Pickup p : pickups) {
				if (p.slide.direction() && ItemStack.isSameItemSameComponents(p.stack, stack)) {
					p.count += amount;
					p.createdAt = System.currentTimeMillis();
					return;
				}
			}
			pickups.add(new Pickup(stack.copy(), amount));
			while (pickups.size() > historySize.getInt()) {
				pickups.remove(0);
			}
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		int rowHeight = Math.max(style.is("Compact") ? 14 : ROW_H,
				(int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 5);
		long lifetime = (long) (duration.get() * 1000);
		List<Pickup> live;
			synchronized (pickups) {
			long now = System.currentTimeMillis();
			while (pickups.size() > historySize.getInt()) {
				pickups.removeFirst();
			}
			Iterator<Pickup> it = pickups.iterator();
			while (it.hasNext()) {
				Pickup p = it.next();
				if (now - p.createdAt > lifetime) {
					p.slide.setDirection(false);
				}
				if (!p.slide.direction() && p.slide.isCollapsed()) {
					it.remove();
				}
			}
			live = new ArrayList<>(pickups);
		}

		if (live.isEmpty()) {
			if (!editing) {
				setSize(0, 0);
				return;
			}
			drawPlaceholder(g); // keep the empty widget grabbable in the editor
			return;
		}

		int width = 0;
		int height = 0;
		for (Pickup p : live) {
			width = Math.max(width, rowWidth(p));
			height += (int) ((rowHeight + GAP) * p.slide.value());
		}
		setSize(width, Math.max(height, 1));

		boolean right = anchorRight();
		int bg = Theme.hudBg(backing.get());
		int y = getY();
		for (int i = live.size() - 1; i >= 0; i--) { // newest (last) at the top
			Pickup p = live.get(i);
			float slide = p.slide.value();
			if (slide <= 0.01f) {
				continue;
			}
			int alpha = (int) (255 * slide);
			int rowW = rowWidth(p);
			int slideOffset = (int) ((1.0f - slide) * (rowW + 10));
			int x = right ? getX() + width - rowW + slideOffset : getX() - slideOffset;

			Render2D.hudPanel(g, x, y, rowW, rowHeight, ColorUtil.multiplyAlpha(bg, slide));
			int edge = right ? x + rowW - 1 : x;
			Render2D.hudAccentBar(g, edge, y + 3, 1, rowHeight - 6, slide);

			Component name = p.stack.getHoverName();
			String nameText = name.getString();
			int nameW = Render2D.width(nameText);
			int textY = y + (rowHeight - Render2D.FONT_HEIGHT) / 2 + 1;
			Render2D.text(g, nameText, x + 5, textY, ColorUtil.withAlpha(textColor.get(), alpha));
			int iconWidth = style.is("Compact") ? 0 : 20;
			if (!style.is("Compact")) {
				g.item(p.stack, x + 5 + nameW + 4, y + 2);
			}
			Render2D.text(g, Integer.toString(p.count), x + 5 + nameW + 4 + iconWidth, textY,
					ColorUtil.withAlpha(Theme.textDim, alpha));
			y += (int) ((rowHeight + GAP) * slide);
		}
	}

	private void drawPlaceholder(GuiGraphicsExtractor g) {
		boolean preview = HudManager.isPreviewData();
		// empty in the main menu, where no world has bound the item components yet
		ItemStack sample = ItemUtil.icon(Items.DIAMOND);
		String label = "Item Pickups";
		int nameW = Render2D.width(label);
		boolean compact = style.is("Compact");
		int rowHeight = Math.max(compact ? 14 : ROW_H,
				(int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 5);
		int iconWidth = preview && !compact && !sample.isEmpty() ? 20 : 0;
		int countWidth = preview ? Render2D.width("1") + 4 : 0;
		int width = 5 + nameW + 4 + iconWidth + countWidth + 5;
		setSize(width, rowHeight);
		Render2D.hudPanel(g, getX(), getY(), width, rowHeight, backing.get());
		int textY = getY() + (rowHeight - Render2D.FONT_HEIGHT) / 2 + 1;
		Render2D.text(g, label, getX() + 5, textY, textColor.get());
		if (iconWidth > 0) {
			g.item(sample, getX() + 5 + nameW + 4, getY() + 2);
		}
		if (preview) {
			Render2D.text(g, "1", getX() + 5 + nameW + 4 + iconWidth, textY, Theme.textDim);
		}
	}

	private int rowWidth(Pickup p) {
		int nameW = Render2D.width(p.stack.getHoverName().getString());
		int iconWidth = style.is("Compact") ? 0 : 20;
		return 5 + nameW + 4 + iconWidth + Render2D.width(Integer.toString(p.count)) + 5;
	}

	private static final class Pickup {
		final ItemStack stack;
		int count;
		long createdAt;
		final Animation slide = new Animation(260, false, Easing.CUBIC_OUT);

		Pickup(ItemStack stack, int count) {
			this.stack = stack;
			this.count = count;
			this.createdAt = System.currentTimeMillis();
			this.slide.setDirection(true);
		}
	}
}
