package unlucky.utility.client.module.modules.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.gui.clickgui.ClickGuiScreen;
import unlucky.utility.client.gui.console.ConsoleScreen;
import unlucky.utility.client.mixin.KeyMappingAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Keep walking while a screen is open — inventories, chests, the ClickGUI, the
 * HUD editor, anything.
 *
 * <p>Vanilla releases every {@link KeyMapping} the moment a screen opens, so
 * {@code KeyboardInput.tick} reads them all as up. We poll the hardware instead
 * ({@link #isDown}), which the input mixin substitutes for {@code isDown()}.
 *
 * <p>Two extras:
 * <ul>
 *   <li><b>Arrow look</b> — the mouse belongs to the screen while it's open, so
 *       the arrow keys turn the player instead.</li>
 *   <li><b>Portals</b> — standing in a nether portal, {@code LocalPlayer} force-closes
 *       every screen that isn't the pause menu ({@code Screen.isAllowedInPortal}
 *       is just {@code isPauseScreen}). We answer that check with "yes" so your
 *       inventory and the ClickGUI stay open while the portal charges.</li>
 * </ul>
 *
 * <p>Typing always wins: with a text field focused (chat, an anvil, the ClickGUI
 * search, the console) the keys stay text, never movement.
 */
public class InventoryMove extends Module {
	public final BooleanSetting arrowLook = add(new BooleanSetting("Arrow look", "Turn with the arrow keys while a screen is open", true));
	public final NumberSetting arrowSpeed = add(new NumberSetting("Arrow speed", "Degrees turned per tick", 5.0, 1.0, 20.0, 0.5));
	public final BooleanSetting portals = add(new BooleanSetting("Portals", "Keep screens open inside nether portals", true));

	public InventoryMove() {
		super("InventoryMove", "Move and look around while a screen is open", Category.MOVEMENT);
	}

	/** True while we should be feeding hardware key state into the movement input. */
	public boolean active() {
		if (!isEnabled() || mc().player == null) {
			return false;
		}
		Screen screen = mc().gui.screen();
		return screen != null && !isTyping(screen);
	}

	/** Any screen where the keyboard belongs to a text field, not to movement. */
	private boolean isTyping(Screen screen) {
		if (screen instanceof ChatScreen || screen instanceof ConsoleScreen) {
			return true;
		}
		if (screen instanceof ClickGuiScreen clickGui && clickGui.isTyping()) {
			return true;
		}
		return screen.getFocused() instanceof EditBox box && box.isFocused();
	}

	/** Raw hardware state of a mapping's bound key, bypassing vanilla's release-on-screen. */
	public boolean isDown(KeyMapping mapping) {
		InputConstants.Key key = ((KeyMappingAccessor) mapping).unlucky$key();
		if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() == GLFW.GLFW_KEY_UNKNOWN) {
			return false; // mouse-bound movement keys can't be polled this way
		}
		return InputConstants.isKeyDown(mc().getWindow(), key.getValue());
	}

	/** Keeps screens alive inside a portal — read from the LocalPlayer mixin. */
	public boolean allowInPortal() {
		return isEnabled() && portals.get();
	}

	@Override
	public void onTick() {
		if (!arrowLook.get() || !active()) {
			return;
		}
		var window = mc().getWindow();
		float step = arrowSpeed.getFloat();
		float yaw = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT) ? step : 0)
				- (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT) ? step : 0);
		float pitch = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_DOWN) ? step : 0)
				- (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_UP) ? step : 0);
		if (yaw == 0.0f && pitch == 0.0f) {
			return;
		}
		mc().player.setYRot(mc().player.getYRot() + yaw);
		mc().player.setXRot(Mth.clamp(mc().player.getXRot() + pitch, -90.0f, 90.0f));
	}
}
