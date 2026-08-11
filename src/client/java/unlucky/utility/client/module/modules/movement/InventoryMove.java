package unlucky.utility.client.module.modules.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.gui.clickgui.ClickGuiScreen;
import unlucky.utility.client.gui.console.ConsoleScreen;
import unlucky.utility.client.mixin.KeyMappingAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.ModeSetting;

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
	/** Entity.turn's mouse-delta multiplier; divide by it to request degrees directly. */
	private static final float TURN_SCALE = 0.15f;
	public final BooleanSetting arrowLook = add(new BooleanSetting("Arrow look", "Turn with the arrow keys while a screen is open", true));
	public final NumberSetting arrowSpeed = add(new NumberSetting("Arrow speed", "Degrees turned per tick", 5.0, 1.0, 20.0, 0.5));
	public final BooleanSetting portals = add(new BooleanSetting("Portals", "Keep screens open inside nether portals", true));
	public final ModeSetting screens = add(new ModeSetting("Screens", "Which non-typing screens keep movement active",
			"All", "All", "Containers", "Click GUI"));
	public final BooleanSetting movement = add(new BooleanSetting("Movement keys", "Enable WASD while a selected screen is open", true));
	public final BooleanSetting jump = add(new BooleanSetting("Jump", "Enable the jump key while a selected screen is open", true));
	public final BooleanSetting sneak = add(new BooleanSetting("Sneak", "Enable the sneak key while a selected screen is open", true));
	public final BooleanSetting sprint = add(new BooleanSetting("Sprint", "Enable the sprint key while a selected screen is open", true));

	public InventoryMove() {
		super("InventoryMove", "Move and look around while a screen is open", Category.MOVEMENT, ServerVisibility.CONDITIONAL);
	}

	/**
	 * Only a screen makes this module do anything at all — with none open, vanilla's own
	 * key state is already what drives movement and nothing here is in the path. So Panic
	 * Minimal leaves it alone in the case that is true nearly all the time, and takes it
	 * down in the one where your walking is coming from somewhere a player's hands are not.
	 */
	@Override
	public boolean isServerObservableNow() {
		return active();
	}

	/** True while we should be feeding hardware key state into the movement input. */
	public boolean active() {
		if (!isEnabled() || mc().player == null) {
			return false;
		}
		Screen screen = mc().gui.screen();
		if (screen == null || isTyping(screen)) return false;
		boolean clickGui = screen instanceof ClickGuiScreen
				|| screen instanceof unlucky.utility.client.gui.clickgui.FutureClickGuiScreen;
		return screens.is("All") || (screens.is("Containers") && screen instanceof AbstractContainerScreen<?>)
				|| (screens.is("Click GUI") && clickGui);
	}

	/** Any screen where the keyboard belongs to a text field, not to movement. */
	private boolean isTyping(Screen screen) {
		if (screen instanceof ChatScreen || screen instanceof ConsoleScreen) {
			return true;
		}
		if (screen instanceof ClickGuiScreen clickGui && clickGui.isTyping()) {
			return true;
		}
		if (screen instanceof unlucky.utility.client.gui.clickgui.FutureClickGuiScreen clickGui && clickGui.isTyping()) {
			return true;
		}
		return screen.getFocused() instanceof EditBox box && box.isFocused();
	}

	/** Raw hardware state of a mapping's bound key, bypassing vanilla's release-on-screen. */
	public boolean isDown(KeyMapping mapping) {
		if (mapping == mc().options.keyUp || mapping == mc().options.keyDown || mapping == mc().options.keyLeft || mapping == mc().options.keyRight) {
			if (!movement.get()) return false;
		} else if (mapping == mc().options.keyJump && !jump.get()) return false;
		else if (mapping == mc().options.keyShift && !sneak.get()) return false;
		else if (mapping == mc().options.keySprint && !sprint.get()) return false;
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

	/**
	 * Applies arrow-look once per rendered frame. The old tick-only path visibly
	 * snapped the camera at 20 Hz because LocalPlayer's view rotation is not
	 * interpolated by the camera. {@code realtimeDeltaTicks} keeps Arrow speed's
	 * existing "degrees per tick" meaning regardless of frame rate.
	 */
	public void updateFrame(float realtimeDeltaTicks) {
		if (!arrowLook.get() || !active()) {
			return;
		}
		var window = mc().getWindow();
		float step = arrowSpeed.getFloat() * realtimeDeltaTicks;
		float yaw = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT) ? step : 0)
				- (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT) ? step : 0);
		float pitch = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_DOWN) ? step : 0)
				- (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_UP) ? step : 0);
		if (yaw == 0.0f && pitch == 0.0f) {
			return;
		}
		// Use vanilla's rotation route for its normal pitch clamp and mount/body
		// bookkeeping, rather than writing raw yaw/pitch values ourselves.
		mc().player.turn(yaw / TURN_SCALE, pitch / TURN_SCALE);
	}
}
