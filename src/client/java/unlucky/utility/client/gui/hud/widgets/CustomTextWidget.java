package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** A line of user-defined text, styled to match Info (accent bar + padding). */
public class CustomTextWidget extends HudWidget {
	private static final int PAD = 7; // clears the accent bar

	public CustomTextWidget() {
		super("CustomText");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return hud().customText.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.3);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		String text = hud.customTextValue.get();
		if (text.isEmpty()) {
			if (!editing) {
				setSize(0, 0);
				return;
			}
			text = "Custom text"; // placeholder so it can be positioned before you set it
		}
		int textWidth = Render2D.width(text);
		int width = textWidth + PAD + 5;
		setSize(width, 13);
		Render2D.roundedRect(g, getX(), getY(), width, 13, 4, Theme.hudBg(hud.customTextBg.get()));
		int barX = anchorRight() ? getX() + width - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, 9,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
		Render2D.text(g, text, alignedX(textWidth, PAD), getY() + 3, hud.customTextColor.get());
	}
}
