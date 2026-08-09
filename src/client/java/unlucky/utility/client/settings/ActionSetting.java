package unlucky.utility.client.settings;

/** A one-shot action exposed as a button in the ClickGUI. */
public final class ActionSetting extends Setting<Void> {
	private final Runnable action;

	public ActionSetting(String name, String description, Runnable action) {
		super(name, description, null);
		this.action = action;
	}

	/** Runs this setting's action. It has no persistent value. */
	public void run() {
		action.run();
	}
}
