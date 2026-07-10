package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.SessionTracker;

/** Totem pops for you and, optionally, your last combat target. */
public class PopCounterWidget extends HudWidget {
	public PopCounterWidget() {
		super("PopCounter");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().popCounter.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.55);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		SessionTracker session = UnluckyClient.INSTANCE.session;

		java.util.List<TextLine> lines = new java.util.ArrayList<>();
		lines.add(new TextLine("Pops " + session.selfPops(), Theme.text));
		if (hud.popCounterTarget.get() && (session.hasTarget() || editing)) {
			lines.add(new TextLine("Target " + session.targetPops(), Theme.hudAccent(0.5f)));
		}
		sortBySize(lines, l -> Render2D.width(l.text()));

		int width = 0;
		for (TextLine line : lines) {
			width = Math.max(width, Render2D.width(line.text()));
		}
		width += 10;
		int height = lines.size() * 9 + 4;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud.popCounterBg.get()));
		for (int i = 0; i < lines.size(); i++) {
			TextLine line = lines.get(i);
			Render2D.text(g, line.text(), alignedX(Render2D.width(line.text()), 5), getY() + 3 + i * 9, line.color());
		}
	}
}
