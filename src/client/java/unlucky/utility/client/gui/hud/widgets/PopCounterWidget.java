package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.SessionTracker;

/** Totem pops for you and, optionally, your last combat target. */
public class PopCounterWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("PopCounter", "Totem pops for you and your target", false));
	public final BooleanSetting bg = add(new BooleanSetting("Pops bg", "Backing behind the pop counter", true));
	public final BooleanSetting target = add(new BooleanSetting("Pops target", "Show the last target's pops", true));
	public final BooleanSetting announce = add(new BooleanSetting("Announce pops", "Toast whenever a totem pops", false));

	public PopCounterWidget() {
		super("PopCounter");
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.55);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		SessionTracker session = UnluckyClient.INSTANCE.session;
		boolean preview = editing && HudManager.isPreviewData();
		int selfPops = preview && session.selfPops() == 0 ? 2 : session.selfPops();

		java.util.List<TextLine> lines = new java.util.ArrayList<>();
		lines.add(new TextLine("Pops " + selfPops, Theme.text));
		if (target.get() && (session.hasTarget() || preview)) {
			int targetPops = preview && !session.hasTarget() ? 3 : session.targetPops();
			lines.add(new TextLine("Target " + targetPops, accentAt(1, 2)));
		}
		sortBySize(lines, l -> Render2D.width(l.text()));

		int width = 0;
		for (TextLine line : lines) {
			width = Math.max(width, Render2D.width(line.text()));
		}
		width += 10;
		int rowHeight = styledLineHeight(9);
		int height = lines.size() * rowHeight + 4;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		for (int i = 0; i < lines.size(); i++) {
			TextLine line = lines.get(i);
			Render2D.text(g, line.text(), alignedX(Render2D.width(line.text()), 5), getY() + 3 + i * rowHeight, line.color());
		}
	}
}
