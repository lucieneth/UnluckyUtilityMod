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

	public void setBounds(int x, int y, int width) {
		this.x = x;
		this.y = y;
		this.width = width;
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

	protected boolean hovered(double mouseX, double mouseY) {
		return Render2D.hovered(mouseX, mouseY, x, y, width, getHeight());
	}
}
