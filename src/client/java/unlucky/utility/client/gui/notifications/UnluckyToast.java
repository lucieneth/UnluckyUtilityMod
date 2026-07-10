package unlucky.utility.client.gui.notifications;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * A notification rendered with the vanilla advancement toast frame — same sprite,
 * icon slot and text layout as "Advancement Made!", so it feels native.
 */
public class UnluckyToast implements Toast {
	private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/advancement");
	private static final long DISPLAY_TIME = 3000L;

	private final String header;
	private final String title;
	private final ItemStack icon;
	private final int nameColor;
	private Toast.Visibility visibility = Toast.Visibility.SHOW;

	public UnluckyToast(String header, String title, ItemStack icon, int nameColor) {
		this.header = header;
		this.title = title;
		this.icon = icon;
		this.nameColor = nameColor;
	}

	@Override
	public Toast.Visibility getWantedVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long fullyVisibleForMs) {
		this.visibility = fullyVisibleForMs >= DISPLAY_TIME * manager.getNotificationDisplayTimeMultiplier()
				? Toast.Visibility.HIDE
				: Toast.Visibility.SHOW;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
		graphics.text(font, header, 30, 7, nameColor, false);
		graphics.text(font, title, 30, 18, 0xFFFFFFFF, false);
		if (!icon.isEmpty()) {
			graphics.fakeItem(icon, 8, 8);
		}
	}
}
