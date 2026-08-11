package unlucky.utility.client.settings;

/**
 * A collapsible header. Its value is whether the group is open, and the settings that
 * belong to it are registered with {@code add(setting, group::get)} so the existing
 * visibility filter hides them while it is shut.
 *
 * <p>Nothing else is needed: both ClickGUI renderers already skip components whose
 * {@code isVisible()} is false, so a group is just a shared condition with a row of its
 * own to toggle it. Modules with long target lists — the ESP-style ones — become
 * readable without a separate submenu screen.
 */
public class GroupSetting extends Setting<Boolean> {
	public GroupSetting(String name, String description) {
		this(name, description, false);
	}

	public GroupSetting(String name, String description, boolean expandedByDefault) {
		super(name, description, expandedByDefault);
	}

	public boolean isExpanded() {
		return get();
	}

	public void toggle() {
		set(!get());
	}
}
