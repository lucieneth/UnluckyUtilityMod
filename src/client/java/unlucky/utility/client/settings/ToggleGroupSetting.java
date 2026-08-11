package unlucky.utility.client.settings;

/**
 * A toggle that also hides a submenu. The value is the on/off state, exactly like a
 * {@link BooleanSetting}; expansion is tracked separately so opening the submenu to
 * configure something never changes whether it is switched on.
 *
 * <p>Children register with {@code add(child, group::isExpanded)}, so the existing
 * visibility filter does the hiding. This keeps a long option list readable: the
 * per-container toggles and colours only appear once you ask for them.
 *
 * <p>Deliberately extends {@link BooleanSetting} so anything that already understands a
 * boolean setting — config serialization above all — keeps working untouched. The GUI
 * dispatch switches must match this type <em>before</em> {@code BooleanSetting}, or the
 * more general case wins and the submenu becomes unreachable.
 */
public class ToggleGroupSetting extends BooleanSetting {
	private boolean expanded;

	public ToggleGroupSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	public boolean isExpanded() {
		return expanded;
	}

	public void toggleExpanded() {
		this.expanded = !this.expanded;
	}
}
