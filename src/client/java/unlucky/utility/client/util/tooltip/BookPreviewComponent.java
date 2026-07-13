package unlucky.utility.client.util.tooltip;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Renders a written book's first page on the {@code book.png} parchment (292×360),
 * scaled to half size, with the page text wrapped inside the cream writing area and
 * drawn at native font size on top (dark ink). Lines past the page are clipped.
 */
public class BookPreviewComponent implements ClientTooltipComponent {
	private static final int NATIVE_W = 292;
	private static final int NATIVE_H = 360;
	private static final float SCALE = 0.5f;
	private static final int DISPLAY_W = Math.round(NATIVE_W * SCALE); // 146
	private static final int DISPLAY_H = Math.round(NATIVE_H * SCALE); // 180
	// writing area within the parchment, in displayed px
	private static final int TEXT_X = 17;
	private static final int TEXT_Y = 14;
	private static final int TEXT_W = 109;
	private static final int TEXT_H = 150;
	private static final int INK = 0xFF201A12;
	private static final int WHITE = 0xFFFFFFFF;

	private static final Identifier BOOK_TEX = UnluckyClientMod.id("textures/gui/book.png");

	private final Component firstPage;

	public BookPreviewComponent(Component firstPage) {
		this.firstPage = firstPage;
	}

	@Override
	public int getWidth(Font font) {
		return DISPLAY_W;
	}

	@Override
	public int getHeight(Font font) {
		return DISPLAY_H + 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(SCALE, SCALE);
		g.blit(RenderPipelines.GUI_TEXTURED, BOOK_TEX, 0, 0, 0.0f, 0.0f, NATIVE_W, NATIVE_H, NATIVE_W, NATIVE_H, WHITE);
		pose.popMatrix();

		Font f = Minecraft.getInstance().font;
		List<FormattedCharSequence> lines = f.split(firstPage, TEXT_W);
		int maxLines = TEXT_H / (f.lineHeight);
		int ty = y + TEXT_Y;
		for (int i = 0; i < lines.size() && i < maxLines; i++) {
			g.text(f, lines.get(i), x + TEXT_X, ty, INK, false);
			ty += f.lineHeight;
		}
	}
}
