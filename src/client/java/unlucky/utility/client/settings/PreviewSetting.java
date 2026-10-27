package unlucky.utility.client.settings;

import java.util.function.Supplier;

import net.minecraft.resources.Identifier;

/**
 * A live, drag-to-rotate model preview inside a module's option list. Like
 * {@link ActionSetting} it has no value and is never saved; it only exists so both
 * ClickGUIs pick up the preview row through their usual setting-to-component switch.
 */
public final class PreviewSetting extends Setting<Void> {
	private final Supplier<Identifier> cape;

	/** @param cape the cape to show on your skin, or null for none — read every frame */
	public PreviewSetting(String name, String description, Supplier<Identifier> cape) {
		super(name, description, null);
		this.cape = cape;
	}

	public Supplier<Identifier> cape() {
		return cape;
	}
}
