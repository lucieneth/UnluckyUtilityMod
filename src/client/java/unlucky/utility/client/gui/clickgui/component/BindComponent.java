package unlucky.utility.client.gui.clickgui.component;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Click, then press a key to bind. Escape unbinds. Shown as [KEY]. */
public class BindComponent extends GuiComponent {
	public static final int HEIGHT = 13;

	private final KeybindSetting setting;
	private boolean listening;

	// Capturing a keybind fires keyPressed, which vanilla immediately follows with a
	// charTyped for the same physical key. Record when a bind is swallowed so a focused
	// text field (e.g. the ClickGUI module search) can ignore that trailing char instead
	// of typing the letter. Shared with the module-bind path in GroupBox.
	private static long lastBindNanos = Long.MIN_VALUE;

	public BindComponent(KeybindSetting setting) {
		this.setting = setting;
	}

	/** Marks that a keybind was just captured (this component or a module bind). */
	public static void markBound() {
		lastBindNanos = System.nanoTime();
	}

	/** True for a brief window after a bind, long enough to catch the trailing charTyped. */
	public static boolean recentlyBound() {
		// guard the sentinel: nanoTime() - Long.MIN_VALUE overflows to a tiny value that would
		// (wrongly) always read as "recent", which swallowed every char and broke the search box
		return lastBindNanos != Long.MIN_VALUE && System.nanoTime() - lastBindNanos < 60_000_000L;
	}

	public static String keyName(int key) {
		if (key == GLFW.GLFW_KEY_UNKNOWN) {
			return "None";
		}
		return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Render2D.textNoShadow(g, setting.getName(), x, y + 2, Theme.textDim);
		String value = listening ? "[...]" : "[" + keyName(setting.get()) + "]";
		Render2D.textNoShadow(g, value, x + width - Render2D.width(value), y + 2,
				listening ? Theme.accent2 : Theme.textDim);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && hovered(mouseX, mouseY)) {
			listening = !listening;
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!listening) {
			return false;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			setting.set(GLFW.GLFW_KEY_UNKNOWN);
		} else {
			setting.set(event.key());
		}
		listening = false;
		markBound();
		return true;
	}
}
