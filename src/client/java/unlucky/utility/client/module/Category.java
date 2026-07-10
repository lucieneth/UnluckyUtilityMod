package unlucky.utility.client.module;

public enum Category {
	COMBAT("Combat", "CB"),
	PLAYER("Player", "PL"),
	MOVEMENT("Movement", "MV"),
	RENDER("Render", "RN"),
	WORLD("World", "WD"),
	MISC("Misc", "MS");

	private final String displayName;
	private final String iconPlaceholder;

	Category(String displayName, String iconPlaceholder) {
		this.displayName = displayName;
		this.iconPlaceholder = iconPlaceholder;
	}

	public String displayName() {
		return displayName;
	}

	/** Letter placeholder until real tab icons exist. */
	public String iconPlaceholder() {
		return iconPlaceholder;
	}
}
