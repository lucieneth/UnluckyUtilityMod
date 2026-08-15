package unlucky.utility.client.gui.skins;

import java.util.function.Supplier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The title-screen player preview: your live skin and cape, head following the
 * mouse, and draggable left-and-right ({@link SkinPanelWidget}). The Edit/NameMC
 * buttons below it do everything else.
 */
public class SkinPreviewWidget extends SkinPanelWidget {
	private final Supplier<PlayerSkin> skin;

	public SkinPreviewWidget(int x, int y, int width, int height, Supplier<PlayerSkin> skin) {
		super(x, y, width, height);
		this.skin = skin;
	}

	@Override
	protected PlayerSkin skin() {
		return skin.get();
	}
}
