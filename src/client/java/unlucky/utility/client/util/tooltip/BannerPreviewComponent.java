package unlucky.utility.client.util.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Renders a banner preview by drawing the banner <em>item</em> scaled up — banner
 * items already render their pattern layers in-icon, so a pose scale gives a clean,
 * correct flag without re-compositing the pattern atlas by hand.
 */
public class BannerPreviewComponent implements ClientTooltipComponent {
	private static final int ITEM = 16;
	private static final int SCALE = 4;
	private static final int SIZE = ITEM * SCALE;

	private final ItemStack banner;

	public BannerPreviewComponent(ItemStack banner) {
		this.banner = banner;
	}

	@Override
	public int getWidth(Font font) {
		return SIZE;
	}

	@Override
	public int getHeight(Font font) {
		return SIZE + 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(SCALE, SCALE);
		g.item(banner, 0, 0);
		pose.popMatrix();
	}
}
