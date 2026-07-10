package unlucky.utility.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.config.ConfigManager;
import unlucky.utility.client.gui.clickgui.ClickGuiScreen;
import unlucky.utility.client.gui.hud.HudEditorScreen;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.notifications.NotificationManager;
import unlucky.utility.client.module.ModuleManager;
import unlucky.utility.client.module.modules.render.PlayerESP;

/**
 * Central singleton holding every manager of the client.
 */
public final class UnluckyClient {
	public static final UnluckyClient INSTANCE = new UnluckyClient();

	public static final String NAME = "Unlucky";
	/**
	 * Read from the jar's own metadata, so there is exactly one version source:
	 * local builds say "dev", release jars carry the number CI took from the git
	 * tag (see ARCHITECTURE.md §9). Never hardcode a number here.
	 */
	public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer("unlucky")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");

	public final ModuleManager modules = new ModuleManager();
	public final HudManager hud = new HudManager();
	public final NotificationManager notifications = new NotificationManager();
	public final unlucky.utility.client.util.SessionTracker session = new unlucky.utility.client.util.SessionTracker();
	public final ConfigManager config = new ConfigManager();

	/** Opens the ClickGUI. Rebindable from the GUI itself. */
	public int clickGuiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;
	/** Opens the HUD editor. */
	public int hudEditorKey = GLFW.GLFW_KEY_RIGHT_CONTROL;

	private UnluckyClient() {
	}

	void init() {
		unlucky.utility.client.util.ChamsRenderType.init(); // register the chams pipeline early
		modules.init();
		hud.init();
		config.load();
		Runtime.getRuntime().addShutdownHook(new Thread(config::save, "unlucky-config-save"));
	}

	void tick() {
		modules.tick();
		session.onTick();
		unlucky.utility.client.util.RotationManager.onTickEnd();
	}

	void renderHud(GuiGraphicsExtractor graphics, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		// ESP overlays draw beneath the HUD widgets
		if (mc.level != null) {
			modules.get(PlayerESP.class).renderOverlay(graphics, partialTick);
		}
		// The HUD editor renders the widgets itself so they stay interactive.
		if (!(mc.gui.screen() instanceof HudEditorScreen)) {
			hud.render(graphics, false);
		}
		// module notifications render through the vanilla ToastManager;
		// item pickups render as a HUD widget in hud.render above
	}

	/**
	 * Called from the keyboard mixin for raw key presses while no screen is open.
	 * Returns true when the event must not reach vanilla — otherwise the same
	 * press would be delivered to a GUI we just opened and instantly close it.
	 */
	public boolean onKeyPress(int key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) {
			return false;
		}
		if (key == clickGuiKey) {
			mc.gui.setScreen(new ClickGuiScreen());
			return true;
		}
		if (key == hudEditorKey) {
			mc.gui.setScreen(new HudEditorScreen());
			return true;
		}
		modules.onKeyPress(key);
		return false;
	}
}
