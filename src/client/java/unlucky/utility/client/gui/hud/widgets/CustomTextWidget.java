package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** A line of user-defined text, styled to match Info (accent bar + padding). */
public class CustomTextWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("CustomText", "A line of your own text on the HUD", false));
	public final StringSetting value = add(new StringSetting("Text", "The text to display (edit here)", "Unlucky"));
	public final BooleanSetting bg = add(new BooleanSetting("Text bg", "Backing behind the custom text", true));
	public final ColorSetting color = add(new ColorSetting("Text color", "Custom text color", Theme.text));

	private static final int PAD = 7; // clears the accent bar

	public CustomTextWidget() {
		super("CustomText");
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
		setFractions(0.5, 0.3);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		String text = value.get();
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
		Render2D.roundedRect(g, getX(), getY(), width, 13, 4, Theme.hudBg(bg.get()));
		int barX = anchorRight() ? getX() + width - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, 9,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
		Render2D.text(g, text, alignedX(textWidth, PAD), getY() + 3, color.get());
	}
}
