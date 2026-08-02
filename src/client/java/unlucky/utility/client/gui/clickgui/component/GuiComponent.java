package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import unlucky.utility.client.util.Render2D;

/** A row inside a ClickGUI panel. */
public abstract class GuiComponent {
	protected int x;
	protected int y;
	protected int width;
	private unlucky.utility.client.settings.Setting<?> owner;

	public void setBounds(int x, int y, int width) {
		this.x = x;
		this.y = y;
		this.width = width;
	}

	/**
	 * Records the setting this row draws, so the panel can ask it whether to show
	 * the row at all. Set once by {@code GroupBox} for every component it builds —
	 * subclasses don't have to do anything, which is why a new component type gets
	 * conditional visibility for free.
	 */
	public void owns(unlucky.utility.client.settings.Setting<?> setting) {
		this.owner = setting;
	}

	/** False while this row's setting is hidden by its condition; the row is then skipped entirely. */
	public boolean isVisible() {
		return owner == null || owner.isVisible();
	}

	/**
	 * True while this row has slid something open below itself — a dropdown list, a
	 * color picker. The panel stops folding the box while any row says yes, so what
	 * you just opened can't end up hidden behind the expander dots.
	 */
	public boolean isExpanded() {
		return false;
	}

	/** Current (possibly animated) height used for layout. */
	public abstract int getHeight();

	public abstract void render(GuiGraphicsExtractor g, int mouseX, int mouseY);

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	public void mouseReleased() {
	}

	public void mouseDragged(double mouseX, double mouseY) {
	}

	/** Return true to consume the scroll (e.g. an open dropdown scrolling internally). */
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		return false;
	}

	public boolean charTyped(CharacterEvent event) {
		return false;
	}

	/** True while this row owns the keyboard, so InventoryMove doesn't read WASD as movement. */
	public boolean typing() {
		return false;
	}

	protected boolean hovered(double mouseX, double mouseY) {
		return Render2D.hovered(mouseX, mouseY, x, y, width, getHeight());
	}
}
