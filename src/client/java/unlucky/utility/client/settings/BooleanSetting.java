package unlucky.utility.client.settings;

public class BooleanSetting extends Setting<Boolean> {
	// optional mob submenu: right-clicking this row in the GUI opens the picker
	private EntityListSetting mobList;
	private boolean mobListHostile;

	public BooleanSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	public BooleanSetting withMobList(EntityListSetting list, boolean hostile) {
		this.mobList = list;
		this.mobListHostile = hostile;
		return this;
	}

	public EntityListSetting getMobList() {
		return mobList;
	}

	public boolean isMobListHostile() {
		return mobListHostile;
	}

	public void toggle() {
		set(!get());
	}
}
