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

	/**
	 * Adds a setting that only shows while {@code condition} holds — typically
	 * {@code () -> mode.is("Something")}, so a mode switch hides the rows that don't
	 * apply to it. The condition runs every frame the row would be drawn, and it can
	 * safely read other settings of this module: it isn't evaluated during field
	 * initialisation. Hiding is cosmetic only, see {@link Setting#showWhen}.
	 */
	protected <T extends Setting<?>> T add(T setting, java.util.function.BooleanSupplier condition) {
		setting.showWhen(condition);
		return add(setting);
	}

	/**
	 * The standard "Pause on AutoEat" switch, so every module that has one words it, defaults
	 * it and behaves it identically.
	 *
	 * <p>Eating is unusually invasive for something so mundane: AutoEat picks a hotbar slot
	 * and then <em>holds the use key down</em> until you are full. Anything else that chooses
	 * slots or right-clicks is therefore fighting it for the same two controls — the printer
	 * equipping a block, AutoBrew moving a bottle, BlockAirPlace reading the very key AutoEat
	 * is holding. Standing down for a second and a half is always the better trade.
	 */
	protected BooleanSetting addPauseOnEat() {
		return add(new BooleanSetting("Pause on AutoEat",
				"Stand down while AutoEat is eating. It takes over your hotbar and holds the "
						+ "use key, so anything that also picks slots or right-clicks will "
						+ "fight it for them.", true));
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
