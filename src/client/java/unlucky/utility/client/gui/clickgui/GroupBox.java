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

	private final Module module;
	private final List<GuiComponent> components = new ArrayList<>();
	private final Animation enabledAnim;
	private boolean listeningForBind;
	private int x;
	private int y;
	private int width;

	public GroupBox(Module module) {
		this.module = module;
		this.enabledAnim = new Animation(160, module.isEnabled(), Easing.QUAD_OUT);
		for (Setting<?> setting : module.getSettings()) {
			switch (setting) {
				case BooleanSetting s -> components.add(new BooleanComponent(s));
				case NumberSetting s -> components.add(new SliderComponent(s));
				case ModeSetting s -> components.add(new ModeComponent(s));
				case ColorSetting s -> components.add(new ColorComponent(s));
				case KeybindSetting s -> components.add(new BindComponent(s));
				case unlucky.utility.client.settings.BlockListSetting s ->
						components.add(new unlucky.utility.client.gui.clickgui.component.BlockListComponent(s));
				case unlucky.utility.client.settings.StringSetting s ->
						components.add(new unlucky.utility.client.gui.clickgui.component.StringComponent(s));
				default -> {
				}
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

	public int getHeight() {
		int height = 4 + PAD + ROW; // border offset + padding + enabled row
		for (GuiComponent component : components) {
			height += component.getHeight();
		}
		return height + ROW + PAD; // bind row + bottom padding
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

		// enabled row
		boolean hoverEnabled = Render2D.hovered(mouseX, mouseY, innerX, rowY, innerWidth, ROW);
		Render2D.checkbox(g, innerX, rowY + 2, 8, enabledAnim.value());
		int offColor = hoverEnabled ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim;
		int labelColor = ColorUtil.lerp(offColor, Theme.flowingAccent(0.15f), enabledAnim.value());
		Render2D.textNoShadow(g, "Enabled", innerX + 12, rowY + 2, labelColor);
		rowY += ROW;

		for (GuiComponent component : components) {
			component.setBounds(innerX, rowY, innerWidth);
			component.render(g, mouseX, mouseY);
			rowY += component.getHeight();
		}

		// bind row
		Render2D.textNoShadow(g, "Bind", innerX, rowY + 2, Theme.textDim);
		String bind = listeningForBind ? "[...]" : "[" + BindComponent.keyName(module.getKeyBind()) + "]";
		Render2D.textNoShadow(g, bind, innerX + innerWidth - Render2D.width(bind), rowY + 2,
				listeningForBind ? Theme.accent2 : Theme.textDim);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int innerX = x + PAD;
		int innerWidth = width - 2 * PAD;
		int rowY = y + 4 + PAD;

		if (button == 0 && Render2D.hovered(mouseX, mouseY, innerX, rowY, innerWidth, ROW)) {
			module.toggle();
			return true;
		}
		rowY += ROW;

		for (GuiComponent component : components) {
			component.setBounds(innerX, rowY, innerWidth);
			if (component.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
			rowY += component.getHeight();
		}

		if (button == 0 && Render2D.hovered(mouseX, mouseY, innerX, rowY, innerWidth, ROW)) {
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
		for (GuiComponent component : components) {
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
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				module.setKeyBind(GLFW.GLFW_KEY_UNKNOWN);
			} else {
				module.setKeyBind(event.key());
			}
			listeningForBind = false;
			return true;
		}
		for (GuiComponent component : components) {
			if (component.keyPressed(event)) {
				return true;
			}
		}
		return false;
	}

	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		for (GuiComponent component : components) {
			if (component.charTyped(event)) {
				return true;
			}
		}
		return false;
	}
}
