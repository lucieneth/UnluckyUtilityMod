package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.HudEntity;
import unlucky.utility.client.util.Render2D;

/** Your own live model in a corner — head and pitch follow where you look. */
public class PlayerModelWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("PlayerModel", "Your own live model in a corner", false));
	public final BooleanSetting bg = add(new BooleanSetting("PlayerModel bg", "Backing behind the player model", true));
	public final BooleanSetting follow = add(new BooleanSetting("Model follows look", "Head and pitch track where you look", true));
	public final BooleanSetting armor = add(new BooleanSetting("Model armor", "Show your armor on the model", true));
	public final BooleanSetting held = add(new BooleanSetting("Model held items", "Show held items on the model", true));

	private static final int WIDTH = 46;
	private static final int HEIGHT = 72;

	public PlayerModelWidget() {
		super("PlayerModel");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.5);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		setSize(WIDTH, HEIGHT);
		Render2D.roundedRect(g, getX(), getY(), WIDTH, HEIGHT, 4, Theme.hudBg(bg.get()));
		if (mc().player == null) {
			return;
		}
		float headOffset = 0.0f;
		float pitch = 0.0f;
		if (follow.get()) {
			headOffset = Mth.wrapDegrees(mc().player.getYHeadRot() - mc().player.yBodyRot);
			pitch = mc().player.getXRot();
		}
		HudEntity.draw(g, mc().player, getX() + 2, getY() + 2, getX() + WIDTH - 2, getY() + HEIGHT - 2,
				15.0f, headOffset, pitch, armor.get(), held.get());
	}
}
