package unlucky.utility.client.settings;

import unlucky.utility.client.util.Keys;

/** Stores a key code, {@link Keys#NONE} when unbound. */
public class KeybindSetting extends Setting<Integer> {
	public KeybindSetting(String name, String description, int defaultKey) {
		super(name, description, defaultKey);
	}

	public boolean isBound() {
		return get() != Keys.NONE;
	}
}
