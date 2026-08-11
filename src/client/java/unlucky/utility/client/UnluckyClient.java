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
import unlucky.utility.client.util.PerfDebug;

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
	/** Opens the console, CS-style. */
	public int consoleKey = GLFW.GLFW_KEY_SEMICOLON;

	private UnluckyClient() {
	}

	void init() {
		unlucky.utility.client.util.ChamsRenderType.init(); // register the chams pipeline early
		modules.init();
		hud.init();
		config.load();
		// InventoryInfo tooltips: map each data carrier to its renderer
		net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback.EVENT.register(data -> {
			if (data instanceof unlucky.utility.client.util.tooltip.ContainerTooltipData container) {
				return new unlucky.utility.client.util.tooltip.ContainerPreviewComponent(container.items(),
						unlucky.utility.client.module.modules.misc.InventoryInfo.guiPreviewStyle(), container.enderChest());
			}
			if (data instanceof unlucky.utility.client.util.tooltip.MapTooltipData map) {
				return new unlucky.utility.client.util.tooltip.MapPreviewComponent(map.mapId());
			}
			if (data instanceof unlucky.utility.client.util.tooltip.BannerTooltipData banner) {
				return new unlucky.utility.client.util.tooltip.BannerPreviewComponent(banner.banner());
			}
			if (data instanceof unlucky.utility.client.util.tooltip.BookTooltipData book) {
				return new unlucky.utility.client.util.tooltip.BookPreviewComponent(book.firstPage());
			}
			if (data instanceof unlucky.utility.client.util.tooltip.FoodTooltipData food) {
				return new unlucky.utility.client.util.tooltip.FoodValueComponent(food);
			}
			return null;
		});
		Runtime.getRuntime().addShutdownHook(new Thread(config::save, "unlucky-config-save"));
	}

	void tick() {
		modules.tick();
		session.onTick();
		// The shared owners resolve after every module has had its say, so "highest priority
		// wins" is true regardless of who ticked first. Offhand before inventory: the swap it
		// decides on is itself an inventory click the coordinator then tidies up after.
		unlucky.utility.client.util.MovementActionCoordinator.onTickEnd();
		unlucky.utility.client.util.PacketQueueManager.onTickEnd();
		unlucky.utility.client.util.OffhandManager.onTickEnd();
		unlucky.utility.client.util.InventoryActionCoordinator.onTickEnd();
		unlucky.utility.client.util.RotationManager.onTickEnd();
	}

	void renderHud(GuiGraphicsExtractor graphics, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		unlucky.utility.client.util.Render3D.beginFrame(); // fresh view-projection for this frame
		unlucky.utility.client.util.EspUniforms.endFrame(); // release last frame's ESP uniform slices
		// ESP overlays draw beneath the HUD widgets
		if (mc.level != null) {
			long start = PerfDebug.ENABLED ? PerfDebug.begin() : 0L;
			modules.get(PlayerESP.class).renderOverlay(graphics, partialTick);
			if (PerfDebug.ENABLED) {
				PerfDebug.end("overlay.PlayerESP", start);
				start = PerfDebug.begin();
			}
			modules.get(unlucky.utility.client.module.modules.render.NameTags.class).renderOverlay(graphics, partialTick);
			if (PerfDebug.ENABLED) {
				PerfDebug.end("overlay.NameTags", start);
				start = PerfDebug.begin();
			}
			modules.get(unlucky.utility.client.module.modules.render.LogoutSpots.class)
					.renderOverlay(graphics, partialTick);
			if (PerfDebug.ENABLED) {
				PerfDebug.end("overlay.LogoutSpots", start);
				start = PerfDebug.begin();
			}
			modules.get(unlucky.utility.client.module.modules.render.HealthIndicators.class)
					.renderOverlay(graphics, partialTick);
			if (PerfDebug.ENABLED) {
				PerfDebug.end("overlay.HealthIndicators", start);
			}
		}
		// The HUD editor renders the widgets itself so they stay interactive.
		if (!(mc.gui.screen() instanceof HudEditorScreen)) {
			hud.render(graphics, false);
		}
		if (PerfDebug.ENABLED) {
			PerfDebug.flushIfDue();
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
		// Defense in depth: KEY_UNKNOWN is the unbound sentinel for module and
		// client keybinds, so it must never be dispatched as a real key press.
		if (key == GLFW.GLFW_KEY_UNKNOWN) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) {
			// Binds are dropped while a screen has focus, or every letter typed into a search
			// box would also toggle a module. Panic is the one exception worth making: the
			// ClickGUI is exactly where you are standing when you decide you want everything
			// off, and it can say for itself whether the key is currently a character.
			unlucky.utility.client.module.modules.misc.Panic panic =
					modules.get(unlucky.utility.client.module.modules.misc.Panic.class);
			if (key == panic.getKeyBind() && panic.reachableFrom(mc.gui.screen())) {
				panic.onKeyBind();
				return true;
			}
			return false;
		}
		if (key == clickGuiKey) {
			mc.gui.setScreen(ClickGuiScreen.create(null));
			return true;
		}
		if (key == hudEditorKey) {
			mc.gui.setScreen(new HudEditorScreen());
			return true;
		}
		if (key == consoleKey || typesSemicolon(key)) {
			mc.gui.setScreen(new unlucky.utility.client.gui.console.ConsoleScreen());
			return true;
		}
		modules.onKeyPress(key);
		return false;
	}

	/**
	 * Layout fallback for the console: GLFW keycodes are (mostly) US-physical, so
	 * on layouts where {@code ;} lives elsewhere (Czech has it on the grave key)
	 * key 59 never arrives. When the console is on its default bind, any key
	 * whose current-layout character is {@code ;} opens it too.
	 */
	private boolean typesSemicolon(int key) {
		if (consoleKey != GLFW.GLFW_KEY_SEMICOLON) {
			return false; // custom binds stay exact
		}
		String name = GLFW.glfwGetKeyName(key, 0);
		return ";".equals(name);
	}
}
