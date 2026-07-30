package unlucky.utility.client.module.modules.hud;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.widgets.ArrayListWidget;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
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
	public final BooleanSetting notifications = add(new BooleanSetting("Notifications", "Achievement-style toast when a module is toggled", true));
	public final ColorSetting notificationColor = add(new ColorSetting("Notif name color", "Color of the client name in notifications", Theme.accent1));
	public final BooleanSetting notificationSound = add(new BooleanSetting("Notif sound", "Play the advancement sound when a notification appears", true));

	public HudModule() {
		super("HUD", "Draws the client HUD", Category.MISC);
		setEnabledSilently(true);
	}

	@Override
	public void onTick() {
		Theme.hudAccent1 = accent1.get();
		Theme.hudAccent2 = accent2.get();
		// the flow animation is the ArrayList's own setting, but Theme is read from
		// everywhere that draws an accent, so it is pushed once a tick from here
		ArrayListWidget list = UnluckyClient.INSTANCE.hud.get(ArrayListWidget.class);
		if (list != null) {
			Theme.hudArrayAnimate = list.animate.get();
			Theme.hudArraySpeed = list.speed.getFloat();
			Theme.hudArrayDown = list.direction.is("Down");
		}
	}
}
