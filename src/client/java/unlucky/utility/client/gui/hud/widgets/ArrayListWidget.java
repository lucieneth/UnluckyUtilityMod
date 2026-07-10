package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/** The classic enabled-modules list with slide animations and gradient colors. */
public class ArrayListWidget extends HudWidget {
	private static final int LINE_HEIGHT = Render2D.FONT_HEIGHT + 2;

	private final Map<Module, Animation> animations = new HashMap<>();

	public ArrayListWidget() {
		super("ArrayList");
	}

	@Override
	public boolean isVisible() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class).arrayList.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.0);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		List<Module> visible = new ArrayList<>();
		for (Module module : UnluckyClient.INSTANCE.modules.all()) {
			Animation animation = animations.computeIfAbsent(module, m -> new Animation(220, m.isEnabled(), Easing.CUBIC_OUT));
			animation.setDirection(module.isEnabled());
			if (module.isEnabled() || !animation.isCollapsed()) {
				visible.add(module);
			}
		}
		// widest line hugs the docked vertical edge — narrowest on top when docked
		// low, widest on top when docked high, matching every other widget
		sortBySize(visible, m -> Render2D.width(m.getName()));

		int maxWidth = 0;
		for (Module module : visible) {
			maxWidth = Math.max(maxWidth, Render2D.width(module.getName()) + 6);
		}
		if (editing && visible.isEmpty()) {
			maxWidth = Render2D.width("ArrayList") + 6;
			Render2D.text(g, "ArrayList", getX() + 3, getY() + 2, Theme.textDim);
		}
		setSize(Math.max(maxWidth, 10), Math.max(visible.size() * LINE_HEIGHT, LINE_HEIGHT));

		boolean right = alignsRight(g.guiWidth());
		int y = getY();
		int index = 0;
		for (Module module : visible) {
			float slide = animations.get(module).value();
			String name = module.getName();
			int textWidth = Render2D.width(name);
			int lineWidth = textWidth + 6;
			int color = Theme.hudScrollingAccent(index, Math.max(visible.size(), 1));
			int alpha = (int) (255 * slide);
			if (alpha <= 4) {
				index++;
				continue;
			}

			int slideOffset = (int) ((1.0f - slide) * (lineWidth + 4));
			int lineX = right
					? getX() + getWidth() - lineWidth + slideOffset
					: getX() - slideOffset;

			Render2D.rect(g, lineX, y, lineWidth, LINE_HEIGHT, ColorUtil.multiplyAlpha(
					Theme.hudBg(UnluckyClient.INSTANCE.modules.get(HudModule.class).arrayBg.get()), slide));
			// accent bar hugs the outer edge
			if (right) {
				Render2D.rect(g, lineX + lineWidth - 1, y, 1, LINE_HEIGHT, ColorUtil.withAlpha(color, alpha));
				Render2D.text(g, name, lineX + 2, y + 2, ColorUtil.withAlpha(color, alpha));
			} else {
				Render2D.rect(g, lineX, y, 1, LINE_HEIGHT, ColorUtil.withAlpha(color, alpha));
				Render2D.text(g, name, lineX + 4, y + 2, ColorUtil.withAlpha(color, alpha));
			}

			y += (int) (LINE_HEIGHT * slide);
			index++;
		}
	}
}
