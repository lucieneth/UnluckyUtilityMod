package unlucky.utility.client.gui.clickgui.component;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * Mode selector. With 4+ options it opens a dropdown list (click an option to
 * pick it); with 2–3 options clicking just cycles. Long lists show at most
 * {@link #MAX_VISIBLE} rows and scroll inside the dropdown itself.
 */
public class ModeComponent extends GuiComponent {
	public static final int HEIGHT = 26;
	private static final int OPTION_H = 11;
	private static final int DROPDOWN_MIN = 4; // options needed before it becomes a dropdown
	private static final int MAX_VISIBLE = 6;  // rows shown before the list scrolls

	/** Only one dropdown is open at a time across the whole GUI. */
	private static ModeComponent openDropdown;

	private final ModeSetting setting;
	private final Animation expand = new Animation(150, false, Easing.CUBIC_OUT);
	private boolean open;
	private int scroll; // index of the first visible option

	public ModeComponent(ModeSetting setting) {
		this.setting = setting;
	}

	private boolean isDropdown() {
		return setting.getModes().size() >= DROPDOWN_MIN;
	}

	private int visibleRows() {
		return Math.min(setting.getModes().size(), MAX_VISIBLE);
	}

	private int maxScroll() {
		return Math.max(0, setting.getModes().size() - MAX_VISIBLE);
	}

	@Override
	public int getHeight() {
		return HEIGHT + (int) (expand.value() * visibleRows() * OPTION_H);
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		expand.setDirection(open);

		Render2D.textNoShadow(g, setting.getName(), x, y + 2, Theme.textDim);

		int boxY = y + 12;
		int boxHeight = 12;
		boolean headerHover = Render2D.hovered(mouseX, mouseY, x, boxY, width, boxHeight);
		Render2D.rect(g, x - 1, boxY - 1, width + 2, boxHeight + 2, Theme.borderDark);
		Render2D.rect(g, x, boxY, width, boxHeight, Theme.surface);
		Render2D.textNoShadow(g, setting.get(), x + 4, boxY + 2, headerHover ? Theme.text : Theme.textDim);
		Render2D.textNoShadow(g, isDropdown() ? (open ? "^" : "v") : "»", x + width - 9, boxY + 2, Theme.textDim);

		int extra = getHeight() - HEIGHT;
		if (extra <= 0) {
			return;
		}
		List<String> modes = setting.getModes();
		scroll = Math.clamp(scroll, 0, maxScroll());
		int rows = visibleRows();
		int listTop = y + 24;
		g.enableScissor(x, listTop, x + width, listTop + extra + 2);
		Render2D.rect(g, x - 1, listTop, width + 2, rows * OPTION_H + 1, Theme.borderDark);
		for (int i = 0; i < rows; i++) {
			String mode = modes.get(scroll + i);
			int rowY = listTop + i * OPTION_H;
			boolean hover = Render2D.hovered(mouseX, mouseY, x, rowY, width, OPTION_H);
			boolean selected = setting.is(mode);
			Render2D.rect(g, x, rowY, width, OPTION_H, hover ? Theme.window : Theme.surface);
			if (selected) {
				Render2D.rect(g, x, rowY, 1, OPTION_H, Theme.accent1);
			}
			Render2D.textNoShadow(g, mode, x + 5, rowY + 2,
					selected ? Theme.accent2 : hover ? Theme.text : Theme.textDim);
		}
		// scrollbar when the list overflows
		if (maxScroll() > 0) {
			int trackH = rows * OPTION_H;
			int thumbH = Math.max(6, trackH * rows / modes.size());
			int thumbY = listTop + (trackH - thumbH) * scroll / maxScroll();
			Render2D.rect(g, x + width - 2, listTop, 2, trackH, Theme.borderDark);
			Render2D.rect(g, x + width - 2, thumbY, 2, thumbH, Theme.accent1);
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) {
			return false;
		}
		if (Render2D.hovered(mouseX, mouseY, x, y + 12, width, 12)) {
			if (isDropdown()) {
				setOpen(!open);
			} else {
				setting.cycle();
			}
			return true;
		}
		if (open) {
			int listTop = y + 24;
			int rows = visibleRows();
			for (int i = 0; i < rows; i++) {
				if (Render2D.hovered(mouseX, mouseY, x, listTop + i * OPTION_H, width, OPTION_H)) {
					setting.set(setting.getModes().get(scroll + i));
					setOpen(false);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || maxScroll() <= 0) {
			return false;
		}
		int listTop = y + 24;
		if (!Render2D.hovered(mouseX, mouseY, x, listTop, width, visibleRows() * OPTION_H)) {
			return false;
		}
		scroll = Math.clamp(scroll - (int) Math.signum(amount), 0, maxScroll());
		return true;
	}

	/** Opens or closes this dropdown, collapsing any other that was open. */
	private void setOpen(boolean value) {
		if (value) {
			if (openDropdown != null && openDropdown != this) {
				openDropdown.open = false;
			}
			openDropdown = this;
			// scroll so the current selection is visible
			scroll = Math.clamp(setting.index(), 0, maxScroll());
		} else if (openDropdown == this) {
			openDropdown = null;
		}
		open = value;
	}
}
