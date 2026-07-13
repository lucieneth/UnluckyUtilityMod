package unlucky.utility.client.gui.skins;

import java.util.function.Supplier;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * The title-screen player preview: your live skin, model + head following the
 * mouse ({@link SkinRender}). Inert to input — the Edit/NameMC buttons below
 * it do the work.
 */
public class SkinPreviewWidget extends AbstractWidget {
	private final Supplier<PlayerSkin> skin;

	public SkinPreviewWidget(int x, int y, int width, int height, Supplier<PlayerSkin> skin) {
		super(x, y, width, height, CommonComponents.EMPTY);
		this.skin = skin;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		SkinRender.draw(g, skin.get(), getX(), getY(), getWidth(), getHeight(), mouseX, mouseY);
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	@Override
	public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent navigationEvent) {
		return null;
	}
}
