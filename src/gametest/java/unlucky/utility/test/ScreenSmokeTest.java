package unlucky.utility.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.alts.AltsScreen;
import unlucky.utility.client.gui.clickgui.BlockPickerPopup;
import unlucky.utility.client.gui.clickgui.BrewQueuePopup;
import unlucky.utility.client.gui.clickgui.ClickGuiScreen;
import unlucky.utility.client.gui.clickgui.ItemPickerPopup;
import unlucky.utility.client.gui.clickgui.MobPickerPopup;
import unlucky.utility.client.gui.configs.ConfigsScreen;
import unlucky.utility.client.gui.console.ConsoleScreen;
import unlucky.utility.client.gui.friends.FriendsScreen;
import unlucky.utility.client.gui.hud.HudEditorScreen;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.gui.skins.SkinsScreen;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.module.modules.combat.Aura;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.modules.render.XRay;
import unlucky.utility.client.module.modules.world.AutoBrew;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Opens every client screen and renders it, with no world and then inside one.
 *
 * <p>This exists because that is exactly the shape of bug we keep shipping. Of the
 * 16 crash reports collected up to v2.0, 11 were ours and 10 of those were a screen
 * or a widget throwing while rendering — including one
 * ({@code ItemPickupWidget.drawPlaceholder} on the title screen, where 26.2 has not
 * bound item components yet) that produced four reports across three weeks and
 * survived three releases, because opening the HUD editor from the main menu is not
 * something anyone thinks to do before tagging.
 *
 * <p>A screen that renders is not a screen that <em>works</em>, and this test claims
 * nothing about layout or behaviour — it claims the frame does not throw. That is the
 * cheap half, and it is the half that has been costing us releases.
 *
 * <p>Failure mode is deliberately blunt: an exception during rendering takes the
 * client down, which fails {@code runClientGameTest}. The log line printed before each
 * screen names the one that did it.
 */
public class ScreenSmokeTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("unlucky-test");

	/** Ticks each screen stays up. Frames render between ticks — the frames are the test. */
	private static final int DWELL = 5;

	@Override
	public void runTest(ClientGameTestContext context) {
		sweep(context, "no world");

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			sweep(context, "in world");
			renderEveryWidget(context);
			chatCommandUi(context);
		}

		LOGGER.info("[smoke] all screens rendered clean");
	}

	/** Every screen, in both ClickGUI styles, in whatever context the caller set up. */
	private void sweep(ClientGameTestContext context, String phase) {
		for (String style : new String[]{"Skeet", "Future"}) {
			context.runOnClient(mc -> theme().clickGuiStyle.set(style));

			show(context, phase, "ClickGUI " + style, () -> ClickGuiScreen.create(null));
			show(context, phase, "ClickGUI " + style + " + block picker",
					() -> {
						BlockPickerPopup.open(UnluckyClient.INSTANCE.modules.get(XRay.class).blocks);
						return ClickGuiScreen.create(null);
					});
			show(context, phase, "ClickGUI " + style + " + item picker",
					() -> {
						ItemPickerPopup.open(UnluckyClient.INSTANCE.modules.get(AutoEat.class).blacklist);
						return ClickGuiScreen.create(null);
					});
			show(context, phase, "ClickGUI " + style + " + mob picker",
					() -> {
						MobPickerPopup.open(UnluckyClient.INSTANCE.modules.get(Aura.class).hostileMobs, true);
						return ClickGuiScreen.create(null);
					});
			show(context, phase, "ClickGUI " + style + " + brew queue",
					() -> {
						BrewQueuePopup.open(UnluckyClient.INSTANCE.modules.get(AutoBrew.class).queue);
						return ClickGuiScreen.create(null);
					});
			closePopups(context);
		}

		show(context, phase, "HUD editor", HudEditorScreen::new);
		show(context, phase, "Configs", ConfigsScreen::new);
		show(context, phase, "Console", ConsoleScreen::new);
		show(context, phase, "Friends", FriendsScreen::new);
		show(context, phase, "Alts", () -> new AltsScreen(null));
		show(context, phase, "Skins", () -> new SkinsScreen(null));

		// Leave the client on something sane for the next phase rather than on our
		// own screen: with no world, a null screen is not a state vanilla expects.
		context.setScreen(TitleScreen::new);
		context.waitTick();
	}

	/**
	 * Turns every HUD widget on and renders the plain in-game HUD for a moment. The
	 * editor draws widgets with {@code editing = true}, which is a different path —
	 * placeholders instead of real content for anything that reads the world — so
	 * without this pass the code that actually runs while you play goes untested.
	 */
	private void renderEveryWidget(ClientGameTestContext context) {
		LOGGER.info("[smoke] in world — every HUD widget enabled");
		List<BooleanSetting> flipped = new ArrayList<>();

		context.runOnClient(mc -> {
			for (HudWidget widget : UnluckyClient.INSTANCE.hud.widgets()) {
				BooleanSetting toggle = widget.toggle();
				if (toggle != null && !toggle.get()) {
					toggle.set(true);
					flipped.add(toggle);
				}
			}
		});
		context.setScreen(() -> null);
		context.waitTicks(DWELL * 4);
		context.runOnClient(mc -> flipped.forEach(toggle -> toggle.set(false)));
		context.waitTick();
	}

	/**
	 * The dot-command chat UI: the accent and the completion list are ours, drawn over
	 * a vanilla screen, and they engage on the bare "." — so typing one character is
	 * enough to exercise the whole path.
	 */
	private void chatCommandUi(ClientGameTestContext context) {
		LOGGER.info("[smoke] in world — chat command completions");
		context.setScreen(() -> new ChatScreen("", false));
		context.waitTicks(5);
		context.getInput().typeChars(".");
		context.waitTicks(DWELL);
		context.getInput().typeChars("fr");
		context.waitTicks(DWELL);
		context.setScreen(() -> null);
		context.waitTick();
	}

	private void show(ClientGameTestContext context, String phase, String name, Supplier<Screen> screen) {
		LOGGER.info("[smoke] {} — {}", phase, name);
		context.setScreen(screen::get);
		context.waitTicks(DWELL);
	}

	private void closePopups(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			BlockPickerPopup.close();
			ItemPickerPopup.close();
			MobPickerPopup.close();
			BrewQueuePopup.close();
		});
	}

	private ThemeModule theme() {
		return UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
	}
}
