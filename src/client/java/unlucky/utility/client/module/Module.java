package unlucky.utility.client.module;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.Setting;

public abstract class Module {
	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private final BooleanSetting hidden = new BooleanSetting("Hidden",
			"Run without showing up in the ArrayList.", false);
	private boolean enabled;
	private int keyBind;

	protected Module(String name, String description, Category category) {
		this(name, description, category, GLFW.GLFW_KEY_UNKNOWN);
	}

	protected Module(String name, String description, Category category, int defaultKey) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.keyBind = defaultKey;
	}

	protected static Minecraft mc() {
		return Minecraft.getInstance();
	}

	protected <T extends Setting<?>> T add(T setting) {
		settings.add(setting);
		return setting;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Category getCategory() {
		return category;
	}

	public List<Setting<?>> getSettings() {
		return settings;
	}

	/**
	 * Appended by {@link ModuleManager#register} rather than by this constructor, so it
	 * lands <i>after</i> the module's own settings instead of jumping the queue in front
	 * of them — subclass fields are only added once the subclass constructor has run.
	 */
	void registerHiddenSetting() {
		add(hidden);
	}

	/** Enabled but kept off the ArrayList. Affects display only; the module still runs. */
	public boolean isHidden() {
		return hidden.get();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getKeyBind() {
		return keyBind;
	}

	public void setKeyBind(int keyBind) {
		this.keyBind = keyBind;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
		UnluckyClient.INSTANCE.notifications.onModuleToggle(this);
	}

	/** Used by config loading so no notifications fire. */
	public void setEnabledSilently(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	/** What pressing the module's keybind does. Default: toggle. */
	public void onKeyBind() {
		toggle();
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	public void onTick() {
	}
}
