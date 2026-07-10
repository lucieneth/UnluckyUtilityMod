package unlucky.utility.client.settings;

/** A free-text setting, edited in the ClickGUI. */
public class StringSetting extends Setting<String> {
	private final int maxLength;

	public StringSetting(String name, String description, String defaultValue) {
		this(name, description, defaultValue, 64);
	}

	public StringSetting(String name, String description, String defaultValue, int maxLength) {
		super(name, description, defaultValue);
		this.maxLength = maxLength;
	}

	@Override
	public void set(String value) {
		super.set(value.length() > maxLength ? value.substring(0, maxLength) : value);
	}
}
