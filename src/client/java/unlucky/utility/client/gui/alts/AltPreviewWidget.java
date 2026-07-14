package unlucky.utility.client.gui.alts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;
import unlucky.utility.client.gui.skins.SkinRender;
import unlucky.utility.client.util.alts.AltAccount;
import unlucky.utility.client.util.alts.AltManager;

/**
 * The alt-switcher title-screen preview, mirroring the skin changer's on the
 * other side: the first saved alt's skin (resolved by uuid through the vanilla
 * render cache), or a <b>zombie</b> when the list is empty — the zombie texture
 * uses the humanoid layout, so the player model wearing it reads as a zombie
 * with no separate model. Head follows the mouse like the skin preview; inert
 * to input (the button below opens the manager).
 */
public class AltPreviewWidget extends AbstractWidget {
	private static final Identifier ZOMBIE = Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png");

	public AltPreviewWidget(int x, int y, int width, int height) {
		super(x, y, width, height, CommonComponents.EMPTY);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		AltAccount first = AltManager.first();
		if (first == null) {
			SkinRender.draw(g, ZOMBIE, false, getX(), getY(), getWidth(), getHeight(), mouseX, mouseY);
			return;
		}
		Identifier skin = Minecraft.getInstance().playerSkinRenderCache()
				.getOrDefault(ResolvableProfile.createUnresolved(first.uuid()))
				.playerSkin().body().texturePath();
		SkinRender.draw(g, skin, false, getX(), getY(), getWidth(), getHeight(), mouseX, mouseY);
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
