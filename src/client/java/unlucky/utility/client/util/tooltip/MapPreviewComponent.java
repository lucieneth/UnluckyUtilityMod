package unlucky.utility.client.util.tooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix3x2fStack;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Renders a filled map's image on the {@code map.png} parchment frame. The map colours
 * already live on the client ({@code ClientLevel.getMapData}); {@code prepareMapTexture}
 * uploads them and hands back a blittable {@link Identifier}. The frame is 160×160 with
 * a 16px border around the 128×128 content; we draw both scaled by the same factor so
 * the map lands inside the border. Decorations (markers) are skipped.
 */
public class MapPreviewComponent implements ClientTooltipComponent {
	private static final int MAP_NATIVE = 128;
	private static final int FRAME_NATIVE = 160;
	private static final int FRAME_BORDER = 16;
	private static final int MAP_SIZE = 96; // displayed map content size
	private static final float SCALE = (float) MAP_SIZE / MAP_NATIVE; // 0.75
	private static final int FRAME_SIZE = Math.round(FRAME_NATIVE * SCALE); // 120
	private static final int INSET = Math.round(FRAME_BORDER * SCALE); // 12
	private static final int WHITE = 0xFFFFFFFF;

	private static final Identifier FRAME_TEX = UnluckyClientMod.id("textures/gui/map.png");

	private final MapId mapId;

	public MapPreviewComponent(MapId mapId) {
		this.mapId = mapId;
	}

	@Override
	public int getWidth(Font font) {
		return FRAME_SIZE;
	}

	@Override
	public int getHeight(Font font) {
		return FRAME_SIZE + 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		MapItemSavedData data = mc.level.getMapData(mapId);
		if (data == null) {
			return;
		}
		Matrix3x2fStack pose = g.pose();
		// parchment frame
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(SCALE, SCALE);
		g.blit(RenderPipelines.GUI_TEXTURED, FRAME_TEX, 0, 0, 0.0f, 0.0f, FRAME_NATIVE, FRAME_NATIVE, FRAME_NATIVE, FRAME_NATIVE, WHITE);
		pose.popMatrix();
		// map content inside the border
		Identifier tex = mc.getMapTextureManager().prepareMapTexture(mapId, data);
		pose.pushMatrix();
		pose.translate(x + INSET, y + INSET);
		pose.scale(SCALE, SCALE);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0.0f, 0.0f, MAP_NATIVE, MAP_NATIVE, MAP_NATIVE, MAP_NATIVE, WHITE);
		pose.popMatrix();
	}
}
