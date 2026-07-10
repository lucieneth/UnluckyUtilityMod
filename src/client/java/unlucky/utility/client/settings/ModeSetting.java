package unlucky.utility.client.settings;

import java.util.List;

public class ModeSetting extends Setting<String> {
	private List<String> modes;

	public ModeSetting(String name, String description, String defaultValue, String... modes) {
		super(name, description, defaultValue);
		this.modes = List.of(modes);
	}

	public List<String> getModes() {
		return modes;
	}

	/** Replaces the option list at runtime (e.g. a dynamically discovered set). */
	public void setModes(List<String> newModes) {
		this.modes = List.copyOf(newModes);
	}

	public int index() {
		return modes.indexOf(get());
	}

	public void cycle() {
		if (modes.isEmpty()) {
			return;
		}
		set(modes.get((index() + 1) % modes.size()));
	}

	public boolean is(String mode) {
		return get().equals(mode);
	}

	@Override
	public void set(String value) {
		// Stored even if not currently in the list, so a value can be restored
		// from config before a dynamic option list has been populated.
		super.set(value);
	}
}
