package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.HudEntity;
import unlucky.utility.client.util.Render2D;

/** Your own live model in a corner — head and pitch follow where you look. */
public class PlayerModelWidget extends HudWidget {
	private static final int WIDTH = 46;
	private static final int HEIGHT = 72;

	public PlayerModelWidget() {
		super("PlayerModel");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().playerModel.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.5);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		setSize(WIDTH, HEIGHT);
		Render2D.roundedRect(g, getX(), getY(), WIDTH, HEIGHT, 4, Theme.hudBg(hud().playerModelBg.get()));
		if (mc().player == null) {
			return;
		}
		float headOffset = 0.0f;
		float pitch = 0.0f;
		if (hud().playerModelFollow.get()) {
			headOffset = Mth.wrapDegrees(mc().player.getYHeadRot() - mc().player.yBodyRot);
			pitch = mc().player.getXRot();
		}
		HudEntity.draw(g, mc().player, getX() + 2, getY() + 2, getX() + WIDTH - 2, getY() + HEIGHT - 2,
				15.0f, headOffset, pitch, hud().playerModelArmor.get(), hud().playerModelHeld.get());
	}
}
