package unlucky.utility.client.gui.hud.widgets;

import java.time.LocalTime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/**
 * A friendly greeting like {@code Good morning, Steve! :^)}. Behaves exactly like
 * CustomText (accent bar + padding, same styling), but the text is generated from the
 * time of day and your username, so there's nothing to edit.
 */
public class GreeterWidget extends HudWidget {
	private static final int PAD = 7; // clears the accent bar
	private static final String SMILEY = " :^)"; // relies on UTF-8 source encoding (set in build.gradle)

	public GreeterWidget() {
		super("Greeter");
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
		return hud().greeter.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.12);
	}

	/** Rolls the greeting over the day: morning / afternoon / evening / night. */
	private String partOfDay() {
		int hour = LocalTime.now().getHour();
		if (hour < 5) {
			return "night";
		} else if (hour < 12) {
			return "morning";
		} else if (hour < 17) {
			return "afternoon";
		} else if (hour < 21) {
			return "evening";
		}
		return "night";
	}

	private String username() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			return mc.player.getName().getString();
		}
		return mc.getGameProfile() != null ? mc.getGameProfile().name() : "player";
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		String text = "Good " + partOfDay() + ", " + username() + "!" + SMILEY;
		int textWidth = Render2D.width(text);
		int width = textWidth + PAD + 5;
		setSize(width, 13);
		Render2D.roundedRect(g, getX(), getY(), width, 13, 4, Theme.hudBg(hud.greeterBg.get()));
		int barX = anchorRight() ? getX() + width - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, 9,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
		Render2D.text(g, text, alignedX(textWidth, PAD), getY() + 3, hud.greeterColor.get());
	}
}
