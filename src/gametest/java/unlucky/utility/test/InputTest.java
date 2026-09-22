package unlucky.utility.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.clickgui.ClickGuiScreen;
import unlucky.utility.client.gui.clickgui.FutureClickGuiScreen;
import unlucky.utility.client.gui.hud.HudEditorScreen;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.module.modules.movement.AutoWalk;
import unlucky.utility.client.util.Keys;

/**
 * Drives real key and mouse presses through vanilla's {@code KeyboardHandler} and
 * {@code MouseHandler}, and checks the client reacts to the button that was pressed.
 *
 * <p>26.3 moved input from GLFW to SDL and renumbered everything: the port compiled
 * clean and a smoke test that only renders screens passed, while every ClickGUI
 * comparison still read GLFW's {@code 0}/{@code 1}. SDL's left button is {@code 1},
 * so a left click ran the right-click branch and a right click ({@code 3}) matched
 * nothing. Only a press delivered the way a player delivers it shows that.
 */
public class InputTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("unlucky-test");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			clientKeys(context);
			moduleBind(context);
			clickGuiButtons(context);
		}
		LOGGER.info("[input] keys and mouse buttons reach the client");
	}

	/** The default client binds, pressed in game with no screen open. */
	private void clientKeys(ClientGameTestContext context) {
		expectScreen(context, InputConstants.KEY_RCONTROL, "the HUD editor",
				screen -> screen instanceof HudEditorScreen);
		expectScreen(context, InputConstants.KEY_RSHIFT, "the ClickGUI",
				screen -> screen instanceof ClickGuiScreen || screen instanceof FutureClickGuiScreen);
	}

	private void moduleBind(ClientGameTestContext context) {
		context.runOnClient(mc -> UnluckyClient.INSTANCE.modules.get(AutoWalk.class).setKeyBind(InputConstants.KEY_K));
		context.getInput().pressKey(InputConstants.KEY_K);
		context.waitTick();
		boolean toggled = context.computeOnClient(mc -> {
			Module walk = UnluckyClient.INSTANCE.modules.get(AutoWalk.class);
			boolean enabled = walk.isEnabled();
			walk.setKeyBind(Keys.NONE);
			if (enabled) {
				walk.toggle();
			}
			return enabled;
		});
		if (!toggled) {
			throw new AssertionError("Pressing a module's bind did not toggle it");
		}
	}

	/**
	 * Left-clicks a module row in the Future ClickGUI and expects that module to
	 * toggle; right-clicks it and expects no module to toggle (right expands).
	 */
	private void clickGuiButtons(ClientGameTestContext context) {
		JsonObject savedPositions = context.computeOnClient(mc -> {
			UnluckyClient.INSTANCE.modules.get(ThemeModule.class).clickGuiStyle.set("Future");
			return FutureClickGuiScreen.positionsJson();
		});
		context.runOnClient(mc -> mc.gui.setScreen(ClickGuiScreen.create(null)));
		context.waitTicks(2);

		// Panels overlap on a narrow window, and an earlier panel wins a click, so the
		// Render column is moved clear of the others rather than trusting the default strip.
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			JsonObject layout = FutureClickGuiScreen.positionsJson();
			for (Category category : Category.values()) {
				JsonObject point = new JsonObject();
				boolean target = category == Category.RENDER;
				point.addProperty("x", target ? 10 : screen.width);
				point.addProperty("y", target ? 40 : screen.height);
				layout.add(category.name(), point);
			}
			JsonObject search = new JsonObject();
			search.addProperty("x", screen.width);
			search.addProperty("y", screen.height);
			layout.add("search", search);
			FutureClickGuiScreen.loadPositions(layout);
		});
		context.waitTicks(2);
		// first module row: panel y + 12 (header) + 1, 14 tall — aim for its middle
		moveCursorToGui(context, 40, 40 + 13 + 7);

		List<Boolean> before = enabledStates(context);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		List<Module> leftToggled = changed(context, before);
		if (leftToggled.size() != 1 || leftToggled.getFirst().getCategory() != Category.RENDER) {
			throw new AssertionError("Left click on a Render module row toggled " + names(leftToggled)
					+ " — expected exactly one Render module");
		}

		before = enabledStates(context);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTick();
		List<Module> rightToggled = changed(context, before);
		if (!rightToggled.isEmpty()) {
			throw new AssertionError("Right click on a module row toggled " + names(rightToggled)
					+ " — right click expands, it must not toggle");
		}
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT); // collapse again

		context.runOnClient(mc -> {
			leftToggled.getFirst().toggle();
			mc.gui.setScreen(null);
			FutureClickGuiScreen.loadPositions(savedPositions);
		});
		context.waitTick();
	}

	private static void expectScreen(ClientGameTestContext context, int key, String expected,
			Predicate<Screen> matches) {
		context.runOnClient(mc -> mc.gui.setScreen(null));
		context.waitTick();
		context.getInput().pressKey(key);
		context.waitTicks(2);
		String wrong = context.computeOnClient(mc -> {
			Screen screen = mc.gui.screen();
			mc.gui.setScreen(null);
			return screen == null ? "nothing" : matches.test(screen) ? null : screen.getClass().getSimpleName();
		});
		context.waitTick();
		if (wrong != null) {
			throw new AssertionError("Key " + key + " opened " + wrong + ", expected " + expected);
		}
	}

	private static void moveCursorToGui(ClientGameTestContext context, int guiX, int guiY) {
		double[] window = context.computeOnClient(mc -> {
			Window w = mc.getWindow();
			return new double[]{
					(guiX + 0.5) * w.getScreenWidth() / w.getGuiScaledWidth(),
					(guiY + 0.5) * w.getScreenHeight() / w.getGuiScaledHeight()};
		});
		context.getInput().setCursorPos(window[0], window[1]);
		context.waitTick();
	}

	private static List<Boolean> enabledStates(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			List<Boolean> states = new ArrayList<>();
			for (Module module : UnluckyClient.INSTANCE.modules.all()) {
				states.add(module.isEnabled());
			}
			return states;
		});
	}

	private static List<Module> changed(ClientGameTestContext context, List<Boolean> before) {
		return context.computeOnClient(mc -> {
			List<Module> modules = UnluckyClient.INSTANCE.modules.all();
			List<Module> result = new ArrayList<>();
			for (int i = 0; i < modules.size(); i++) {
				if (modules.get(i).isEnabled() != before.get(i)) {
					result.add(modules.get(i));
				}
			}
			return result;
		});
	}

	private static String names(List<Module> modules) {
		return modules.isEmpty() ? "nothing" : modules.stream().map(Module::getName).toList().toString();
	}
}
