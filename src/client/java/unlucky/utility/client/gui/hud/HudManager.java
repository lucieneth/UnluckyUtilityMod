package unlucky.utility.client.gui.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.widgets.ArrayListWidget;
import unlucky.utility.client.gui.hud.widgets.InfoWidget;
import unlucky.utility.client.gui.hud.widgets.WatermarkWidget;
import unlucky.utility.client.module.modules.hud.HudModule;

public final class HudManager {
	private final List<HudWidget> widgets = new ArrayList<>();
	private final unlucky.utility.client.gui.hud.widgets.ItemPickupWidget itemPickups =
			new unlucky.utility.client.gui.hud.widgets.ItemPickupWidget();

	public void init() {
		widgets.add(new WatermarkWidget());
		widgets.add(new ArrayListWidget());
		widgets.add(new InfoWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.TargetHudWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.PlayerModelWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.KeystrokesWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.ArmorHudWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.PotionHudWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.CoordsWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.SpeedometerWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.InventoryViewerWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.PopCounterWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.SessionInfoWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.ItemCounterWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.RadarWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.CustomTextWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.GreeterWidget());
		widgets.add(itemPickups);
	}

	public List<HudWidget> widgets() {
		return widgets;
	}

	public unlucky.utility.client.gui.hud.widgets.ItemPickupWidget itemPickups() {
		return itemPickups;
	}

	public void render(GuiGraphicsExtractor g, boolean editing) {
		if (!editing && !UnluckyClient.INSTANCE.modules.get(HudModule.class).isEnabled()) {
			return;
		}
		for (HudWidget widget : widgets) {
			if (editing || widget.isVisible()) {
				widget.render(g, editing);
			}
		}
	}
}
