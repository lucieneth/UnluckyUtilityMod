package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.gui.clickgui.component.BindComponent;
import unlucky.utility.client.gui.clickgui.component.BooleanComponent;
import unlucky.utility.client.gui.clickgui.component.ColorComponent;
import unlucky.utility.client.gui.clickgui.component.GuiComponent;
import unlucky.utility.client.gui.clickgui.component.ModeComponent;
import unlucky.utility.client.gui.clickgui.component.SliderComponent;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.Setting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * Skeet-style group box for one module: bordered box with the module name on
 * the border, an Enabled checkbox, all settings, and a bind row.
 */
public class GroupBox {
	private static final int ROW = 13;
	private static final int PAD = 7;
	/** The expander: three dots, sitting on the bottom border like the title on the top. */
	private static final int DOT = 3;
	private static final int DOT_GAP = 3;
	private static final int DOTS_W = 3 * DOT + 2 * DOT_GAP;

	private final Module module;
	private final List<GuiComponent> components = new ArrayList<>();
	private final Animation enabledAnim;
	private boolean listeningForBind;
	/** Whether a box past the row limit is currently showing everything. */
	private boolean expanded;
	private int x;
	private int y;
	private int width;

	public GroupBox(Module module) {
		this.module = module;
		this.enabledAnim = new Animation(160, module.isEnabled(), Easing.QUAD_OUT);
		for (Setting<?> setting : module.getSettings()) {
			GuiComponent component = switch (setting) {
				case BooleanSetting s -> new BooleanComponent(s);
				case NumberSetting s -> new SliderComponent(s);
				case ModeSetting s -> new ModeComponent(s);
				case ColorSetting s -> new ColorComponent(s);
				case KeybindSetting s -> new BindComponent(s);
				case unlucky.utility.client.settings.BlockListSetting s ->
						new unlucky.utility.client.gui.clickgui.component.BlockListComponent(s);
				case unlucky.utility.client.settings.ItemListSetting s ->
						new unlucky.utility.client.gui.clickgui.component.ItemListComponent(s);
				case unlucky.utility.client.settings.BrewQueueSetting s ->
						new unlucky.utility.client.gui.clickgui.component.BrewQueueComponent(s);
				case unlucky.utility.client.settings.StringSetting s ->
						new unlucky.utility.client.gui.clickgui.component.StringComponent(s);
				default -> null;
			};
			if (component != null) {
				// the one place a component learns its setting, so hiding works for
				// every row type without each of them knowing about it
				component.owns(setting);
				components.add(component);
			}
		}
	}

	public Module getModule() {
		return module;
	}

	/** True when the mouse is over the module's title on the box border. */
	public boolean titleHovered(double mouseX, double mouseY) {
		return Render2D.hovered(mouseX, mouseY, x + 7, y, Render2D.width(module.getName()) + 6, 9);
	}

	public void setBounds(int x, int y, int width) {
		this.x = x;
		this.y = y;
		this.width = width;
	}

	/** Every row the box would draw given the space: Enabled, the settings, Bind. */
	private int contentHeight() {
		int height = ROW;
		for (GuiComponent component : components) {
			if (component.isVisible()) {
				height += component.getHeight();
			}
		}
		return height + ROW;
	}

	/** Rows a box may show before it folds, from the Theme module's Module lines. */
	private int lineLimit() {
		return unlucky.utility.client.UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.client.ThemeModule.class)
				.moduleLines.getInt() * ROW;
	}

	/** Whether this box has more to show than the limit allows. */
	public boolean collapsible() {
		return contentHeight() > lineLimit();
	}

	/** True while any row has a dropdown or picker slid open below it. */
	private boolean anyExpanded() {
		for (GuiComponent component : components) {
			if (component.isVisible() && component.isExpanded()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Content height actually drawn: the whole thing, or the limit while folded.
	 *
	 * <p>A row that's currently slid open unfolds the box for as long as it's open.
	 * Without that, opening a dropdown near the bottom of a long module grew the
	 * content past the limit and the list simply vanished behind the expander dots —
	 * the click registered, the setting was reachable by scrolling the list blind,
	 * but nothing appeared to happen.
	 */
	private int shownHeight() {
		return expanded || !collapsible() || anyExpanded() ? contentHeight() : lineLimit();
	}

	public int getHeight() {
		return 4 + PAD + shownHeight() + PAD; // border offset + padding + rows + padding
	}

	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		enabledAnim.setDirection(module.isEnabled());
		int boxTop = y + 4;
		int height = getHeight();

		// opaque interior so the content-area hatching only shows in the gaps between
		// boxes — the boxes themselves stay clean
		Render2D.rect(g, x, boxTop, width, height - 4, Theme.group);
		g.outline(x, boxTop, width, height - 4, Theme.border);
		// title patch breaks the top border; an enabled module glows into the
		// flowing accent, fading in as it turns on
		String title = module.getName();
		Render2D.rect(g, x + 7, y, Render2D.width(title) + 6, 9, Theme.window);
		Render2D.textNoShadow(g, title, x + 10, y,
				ColorUtil.lerp(Theme.text, Theme.flowingAccent(0.0f), enabledAnim.value()));

		int innerX = x + PAD;
		int innerWidth = width - 2 * PAD;
		int rowY = y + 4 + PAD;
		int limit = rowY + shownHeight();

		// enabled row
		boolean toggleable = module.isToggleable();
		boolean hoverEnabled = toggleable && Render2D.hovered(mouseX, mouseY, innerX, rowY, innerWidth, ROW);
		Render2D.checkbox(g, innerX, rowY + 2, 8, enabledAnim.value());
		int offColor = hoverEnabled ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim;
		int labelColor = ColorUtil.lerp(offColor, Theme.flowingAccent(0.15f), enabledAnim.value());
		Render2D.textNoShadow(g, toggleable ? "Enabled" : "Always enabled", innerX + 12, rowY + 2, labelColor);
		rowY += ROW;

		boolean truncated = false;
		for (GuiComponent component : components) {
			if (!component.isVisible()) {
				continue;
			}
			// whole rows only: a setting half-drawn under the border would read as a
			// rendering bug rather than as "there is more here"
			if (rowY + component.getHeight() > limit) {
				truncated = true;
				break;
			}
			component.setBounds(innerX, rowY, innerWidth);
			component.render(g, mouseX, mouseY);
			rowY += component.getHeight();
		}

		// bind row
		if (!truncated && rowY + ROW <= limit) {
			Render2D.textNoShadow(g, "Bind", innerX, rowY + 2, Theme.textDim);
			String bind = listeningForBind ? "[...]" : "[" + BindComponent.keyName(module.getKeyBind()) + "]";
			Render2D.textNoShadow(g, bind, innerX + innerWidth - Render2D.width(bind), rowY + 2,
					listeningForBind ? Theme.accent2 : Theme.textDim);
		}

		if (collapsible()) {
			drawExpander(g, mouseX, mouseY, height);
		}
	}

	/**
	 * The three dots that fold the box open and shut, drawn over the bottom border with
	 * a patch behind them — the same trick the title uses on the top border, so the
	 * affordance costs no height and reads as part of the frame.
	 */
	private void drawExpander(GuiGraphicsExtractor g, int mouseX, int mouseY, int height) {
		int dotsX = x + width - PAD - DOTS_W;
		int patchY = y + height - 5;
		Render2D.rect(g, dotsX - 3, patchY, DOTS_W + 6, 9, Theme.window);
		int color = expanderHovered(mouseX, mouseY) ? Theme.flowingAccent(0.0f) : Theme.textDim;
		for (int i = 0; i < 3; i++) {
			Render2D.rect(g, dotsX + i * (DOT + DOT_GAP), patchY + 3, DOT, DOT, color);
		}
	}

	private boolean expanderHovered(double mouseX, double mouseY) {
		int dotsX = x + width - PAD - DOTS_W;
		return Render2D.hovered(mouseX, mouseY, dotsX - 3, y + getHeight() - 5, DOTS_W + 6, 9);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int innerX = x + PAD;
		int innerWidth = width - 2 * PAD;
		int rowY = y + 4 + PAD;
		int limit = rowY + shownHeight();

		// the expander sits on the border, outside the rows, so it is asked first
		if (button == 0 && collapsible() && expanderHovered(mouseX, mouseY)) {
			expanded = !expanded;
			return true;
		}
		if (button == 0 && module.isToggleable()
				&& Render2D.hovered(mouseX, mouseY, innerX, rowY, innerWidth, ROW)) {
			module.toggle();
			return true;
		}
		rowY += ROW;

		for (GuiComponent component : components) {
			if (!component.isVisible()) {
				continue;
			}
			if (rowY + component.getHeight() > limit) {
				return false; // folded away: not drawn, so not clickable
			}
			component.setBounds(innerX, rowY, innerWidth);
			if (component.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
			rowY += component.getHeight();
		}

		if (button == 0 && rowY + ROW <= limit
				&& Render2D.hovered(mouseX, mouseY, innerX, rowY, innerWidth, ROW)) {
			listeningForBind = !listeningForBind;
			return true;
		}
		return false;
	}

	public void mouseDragged(double mouseX, double mouseY) {
		for (GuiComponent component : components) {
			component.mouseDragged(mouseX, mouseY);
		}
	}

	/** Lets a component (e.g. an open dropdown) consume the scroll before the panel does. */
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		int innerX = x + PAD;
		int innerWidth = width - 2 * PAD;
		int rowY = y + 4 + PAD + ROW; // past the enabled row
		int limit = y + 4 + PAD + shownHeight();
		for (GuiComponent component : components) {
			if (!component.isVisible()) {
				continue;
			}
			if (rowY + component.getHeight() > limit) {
				return false;
			}
			component.setBounds(innerX, rowY, innerWidth);
			if (component.mouseScrolled(mouseX, mouseY, amount)) {
				return true;
			}
			rowY += component.getHeight();
		}
		return false;
	}

	public void mouseReleased() {
		for (GuiComponent component : components) {
			component.mouseReleased();
		}
	}

	public boolean keyPressed(KeyEvent event) {
		if (listeningForBind) {
			int key = event.key();
			// GLFW cannot identify some media/consumer keys. Do not turn one of
			// those presses into an accidental unbind; wait for a usable key.
			if (key == GLFW.GLFW_KEY_UNKNOWN) {
				return true;
			}
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				module.setKeyBind(GLFW.GLFW_KEY_UNKNOWN);
			} else {
				module.setKeyBind(key);
			}
			listeningForBind = false;
			BindComponent.markBound(); // swallow the trailing charTyped so search doesn't type it
			return true;
		}
		for (GuiComponent component : components) {
			// a hidden row can't be clicked, so it must not keep the keyboard either
			if (component.isVisible() && component.keyPressed(event)) {
				return true;
			}
		}
		return false;
	}

	/** True when one of this box's rows has a focused text field. */
	public boolean typing() {
		for (GuiComponent component : components) {
			if (component.isVisible() && component.typing()) {
				return true;
			}
		}
		return false;
	}

	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		for (GuiComponent component : components) {
			if (component.isVisible() && component.charTyped(event)) {
				return true;
			}
		}
		return false;
	}
}
