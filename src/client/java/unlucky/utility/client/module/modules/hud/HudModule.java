package unlucky.utility.client.module.modules.hud;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.widgets.ArrayListWidget;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;

/**
 * Master switch for the HUD, plus the styling every widget shares.
 *
 * <p>Per-widget options are <i>not</i> here: each widget declares its own and the HUD
 * editor's right-click popup lists them. This module is only what is genuinely global —
 * the accent gradient, and the toast notifications that are not a widget at all.
 */
public class HudModule extends Module {
	public final ColorSetting accent1 = add(new ColorSetting("Accent 1", "HUD gradient start", Theme.hudAccent1));
	public final ColorSetting accent2 = add(new ColorSetting("Accent 2", "HUD gradient end", Theme.hudAccent2));
	public final BooleanSetting accentAnimation = add(new BooleanSetting("HUD accent animation",
			"Animate every shared HUD accent in one screen-space sweep", true));
	public final NumberSetting accentSpeed = add(new NumberSetting("HUD accent speed",
			"Speed of the shared HUD accent sweep", 1.0, 0.1, 5.0, 0.1));
	public final ModeSetting accentDirection = add(new ModeSetting("HUD accent direction",
			"Direction of the shared HUD accent sweep", "Down", "Down", "Up"));
	public final NumberSetting panelOpacity = add(new NumberSetting("HUD panel opacity",
			"Opacity shared by every HUD widget background", 100, 0, 100, 1));
	public final NumberSetting panelRadius = add(new NumberSetting("HUD panel radius",
			"Corner radius shared by HUD widget panels", 4, 0, 8, 1));
	public final BooleanSetting panelBorder = add(new BooleanSetting("HUD panel border",
			"Draw a shared animated accent outline around HUD widget panels", false));
	public final NumberSetting panelBorderOpacity = add(new NumberSetting("HUD border opacity",
			"Opacity of the shared HUD panel outline", 45, 0, 100, 1));
	public final NumberSetting screenPadding = add(new NumberSetting("Screen padding",
			"Gap every freely-placed HUD widget keeps from the screen edges", 8, 0, 24, 1));
	public final BooleanSetting editorGrid = add(new BooleanSetting("Editor grid", "Show the placement grid in the HUD editor", true));
	public final BooleanSetting editorSafeAreas = add(new BooleanSetting("Editor safe areas", "Show chat, hotbar, boss-bar and potion safe areas", true));
	public final BooleanSetting notifications = add(new BooleanSetting("Notifications", "Achievement-style toast when a module is toggled", true));
	public final ColorSetting notificationColor = add(new ColorSetting("Notif name color", "Color of the client name in notifications", Theme.accent1));
	public final BooleanSetting notificationSound = add(new BooleanSetting("Notif sound", "Play the advancement sound when a notification appears", true));

	public HudModule() {
		super("HUD", "Draws the client HUD", Category.MISC, ServerVisibility.CLIENT_ONLY);
		accentSpeed.showWhen(accentAnimation::get);
		accentDirection.showWhen(accentAnimation::get);
		panelBorderOpacity.showWhen(panelBorder::get);
		setEnabledSilently(true);
	}

	@Override
	public void onTick() {
		Theme.hudAccent1 = accent1.get();
		Theme.hudAccent2 = accent2.get();
		Theme.hudAccentAnimation = accentAnimation.get();
		Theme.hudAccentSpeed = accentSpeed.getFloat();
		Theme.hudAccentDown = accentDirection.is("Down");
		Theme.hudPanelOpacity = panelOpacity.getFloat() / 100.0f;
		Theme.hudPanelRadius = panelRadius.getInt();
		Theme.hudPanelBorder = panelBorder.get();
		Theme.hudPanelBorderOpacity = panelBorderOpacity.getFloat() / 100.0f;
		Theme.hudScreenMargin = screenPadding.getInt();
		// The ArrayList intentionally retains its own color source and animation
		// controls. Its legacy gradient is still driven here for existing configs;
		// all shared HUD chrome uses the controls above instead.
		ArrayListWidget list = UnluckyClient.INSTANCE.hud.get(ArrayListWidget.class);
		if (list != null) {
			Theme.hudArrayAnimate = list.animate.get();
			Theme.hudArraySpeed = list.speed.getFloat();
			Theme.hudArrayDown = list.direction.is("Down");
		}
	}
}
