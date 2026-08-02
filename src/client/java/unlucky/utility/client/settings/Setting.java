package unlucky.utility.client.settings;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
	private final String name;
	private final String description;
	protected T value;
	private BooleanSupplier visible;

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.value = defaultValue;
	}

	/**
	 * Hides the row in the ClickGUI and the HUD editor while {@code condition} is
	 * false — for settings that only mean something in one mode. Purely cosmetic:
	 * the value is still live, still saved and still read by the module, so hiding
	 * one never changes behaviour. Set through {@code Module.add(setting, condition)}.
	 */
	public void showWhen(BooleanSupplier condition) {
		this.visible = condition;
	}

	public boolean isVisible() {
		return visible == null || visible.getAsBoolean();
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}
}
