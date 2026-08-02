package unlucky.utility.client.settings;

import java.util.List;

public class ModeSetting extends Setting<String> {
	private List<String> modes;
	private java.util.function.UnaryOperator<String> labels;

	public ModeSetting(String name, String description, String defaultValue, String... modes) {
		super(name, description, defaultValue);
		this.modes = List.of(modes);
	}

	/**
	 * Draws each option through {@code labels} instead of showing its raw name —
	 * how a font picker shows every style written in that style. Display only: the
	 * value stored, compared with {@link #is} and written to the config is always
	 * the plain mode name, so a label change can never orphan a saved setting.
	 */
	public ModeSetting withLabels(java.util.function.UnaryOperator<String> labels) {
		this.labels = labels;
		return this;
	}

	/** How {@code mode} should be drawn. */
	public String label(String mode) {
		return labels == null ? mode : labels.apply(mode);
	}

	/** How the current value should be drawn. */
	public String label() {
		return label(get());
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
