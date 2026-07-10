package unlucky.utility.client.settings;

import org.lwjgl.glfw.GLFW;

/** Stores a GLFW key code, {@link GLFW#GLFW_KEY_UNKNOWN} when unbound. */
public class KeybindSetting extends Setting<Integer> {
	public KeybindSetting(String name, String description, int defaultKey) {
		super(name, description, defaultKey);
	}

	public boolean isBound() {
		return get() != GLFW.GLFW_KEY_UNKNOWN;
	}
}
