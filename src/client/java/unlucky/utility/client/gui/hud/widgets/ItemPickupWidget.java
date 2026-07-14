package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * A sliding list of items the local player just picked up: icon, colored name
 * and a running count. Repeat pickups bump the count and refresh the timer.
 * Newest sits at the top and older rows stack below; rows slide in/out toward
 * whichever screen edge the widget is docked against.
 */
public class ItemPickupWidget extends HudWidget {
	private static final int ROW_H = 20;
	private static final int GAP = 2;
	private static final int MAX = 10;

	private final List<Pickup> pickups = new ArrayList<>();

	public ItemPickupWidget() {
		super("ItemPickups");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return hud().itemPickups.get();
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
			while (pickups.size() > MAX) {
				pickups.remove(0);
			}
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		long lifetime = (long) (hud.itemPickupsDuration.get() * 1000);
		List<Pickup> live;
		synchronized (pickups) {
			long now = System.currentTimeMillis();
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
			drawPlaceholder(g, hud); // keep the empty widget grabbable in the editor
			return;
		}

		int width = 0;
		int height = 0;
		for (Pickup p : live) {
			width = Math.max(width, rowWidth(p));
			height += (int) ((ROW_H + GAP) * p.slide.value());
		}
		setSize(width, Math.max(height, 1));

		boolean right = anchorRight();
		int bg = Theme.hudBg(hud.itemPickupsBg.get());
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

			Render2D.roundedRect(g, x, y, rowW, ROW_H, 4, ColorUtil.multiplyAlpha(bg, slide));
			int edge = right ? x + rowW - 1 : x;
			Render2D.rect(g, edge, y + 3, 1, ROW_H - 6, ColorUtil.withAlpha(Theme.hudAccent(0.5f), alpha));

			Component name = p.stack.getHoverName();
			int nameW = Render2D.font().width(name);
			int textY = y + (ROW_H - Render2D.FONT_HEIGHT) / 2 + 1;
			g.text(Render2D.font(), name, x + 5, textY, ColorUtil.withAlpha(Theme.text, alpha), true);
			g.item(p.stack, x + 5 + nameW + 4, y + 2);
			Render2D.text(g, Integer.toString(p.count), x + 5 + nameW + 4 + 16 + 4, textY,
					ColorUtil.withAlpha(Theme.textDim, alpha));
			y += (int) ((ROW_H + GAP) * slide);
		}
	}

	private void drawPlaceholder(GuiGraphicsExtractor g, HudModule hud) {
		ItemStack sample = new ItemStack(Items.DIAMOND);
		String label = "Item Pickups";
		int nameW = Render2D.width(label);
		int width = 5 + nameW + 4 + 16 + 4 + Render2D.width("1") + 5;
		setSize(width, ROW_H);
		Render2D.roundedRect(g, getX(), getY(), width, ROW_H, 4, Theme.hudBg(hud.itemPickupsBg.get()));
		int textY = getY() + (ROW_H - Render2D.FONT_HEIGHT) / 2 + 1;
		Render2D.text(g, label, getX() + 5, textY, Theme.textDim);
		g.item(sample, getX() + 5 + nameW + 4, getY() + 2);
		Render2D.text(g, "1", getX() + 5 + nameW + 4 + 16 + 4, textY, Theme.textDim);
	}

	private static int rowWidth(Pickup p) {
		int nameW = Render2D.font().width(p.stack.getHoverName());
		return 5 + nameW + 4 + 16 + 4 + Render2D.width(Integer.toString(p.count)) + 5;
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
