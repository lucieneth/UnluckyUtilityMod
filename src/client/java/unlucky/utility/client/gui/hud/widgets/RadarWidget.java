package unlucky.utility.client.gui.hud.widgets;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.HeadRenderer;
import unlucky.utility.client.util.Render2D;

/**
 * Top-down radar of nearby entities. Positions are the horizontal offset from
 * the player, scaled to the canvas and optionally rotated so the camera faces up.
 *
 * <p>Under the markers sits a terrain map, one texel per block in the map colour of the top
 * block, shaded against its northern neighbour the way vanilla maps are. The texture is anchored
 * to the world rather than to the player, with a margin around the view: walking only slides it,
 * and it is rebuilt in a bounded number of columns per frame, so no frame pays for the whole map.
 * Players can be drawn as their heads instead of dots.
 */
public class RadarWidget extends HudWidget {
	private static final Identifier TERRAIN = Identifier.fromNamespaceAndPath("unlucky", "radar_terrain");
	/** Blocks of map kept past the view's edge, so walking slides the map before it re-anchors. */
	private static final int MARGIN = 16;
	/** Columns rescanned per frame; the whole map cycles through in a fraction of a second. */
	private static final int BUDGET = 2048;
	private static final long UPLOAD_MS = 100;

	public final BooleanSetting enabled = add(new BooleanSetting("Radar", "Top-down radar of nearby entities", false));
	public final BooleanSetting bg = add(new BooleanSetting("Radar bg", "Backing behind the radar", true));
	public final NumberSetting reach = add(new NumberSetting("Radar range", "Blocks shown from center to edge", 48, 8, 128, 4));
	public final NumberSetting canvasSize = add(new NumberSetting("Radar size", "Canvas size in pixels", 90, 60, 160, 10));
	public final BooleanSetting rotateWithCamera = add(new BooleanSetting("Radar rotate", "Rotate with the camera (off = north up)", true));
	public final BooleanSetting terrain = add(new BooleanSetting("Radar terrain", "Draw the ground from above, like a map", true));
	public final NumberSetting terrainOpacity = add(new NumberSetting("Radar terrain opacity", "Opacity of the terrain map", 85, 10, 100, 5));
	public final BooleanSetting heads = add(new BooleanSetting("Radar heads", "Draw players as their heads instead of dots", true));
	public final NumberSetting headSize = add(new NumberSetting("Radar head size", "Size of player heads", 8, 4, 16, 1));
	public final BooleanSetting players = add(new BooleanSetting("Radar players", "Show players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Radar hostiles", "Show hostile mobs", true));
	public final BooleanSetting passives = add(new BooleanSetting("Radar passives", "Show passive mobs", false));
	public final ColorSetting playerColor = add(new ColorSetting("Radar player color", "Color used for players", Theme.hudAccent1));
	public final ColorSetting hostileColor = add(new ColorSetting("Radar hostile color", "Color used for hostile mobs", 0xFFE04545));
	public final ColorSetting passiveColor = add(new ColorSetting("Radar passive color", "Color used for passive mobs", 0xFF3FD46A));
	public final NumberSetting dotSize = add(new NumberSetting("Radar dot size", "Size of entity markers", 3, 1, 6, 1));
	public final NumberSetting markerOpacity = add(new NumberSetting("Radar marker opacity", "Opacity of entity markers", 100, 10, 100, 5));
	public final BooleanSetting proximityFade = add(new BooleanSetting("Radar proximity fade", "Fade markers as they approach the edge", true));

	// terrain map: side x side columns, world column (originX + i, originZ + j) at index j * side + i
	private DynamicTexture texture;
	private int side;
	private int originX;
	private int originZ;
	private int[] colors;
	private int[] heights;
	private int cursor;
	private boolean dirty;
	private long uploadedAt;
	private Level mapLevel;
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

	public RadarWidget() {
		super("Radar");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.35);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		int size = canvasSize.getInt();
		setSize(size, size);
		Render2D.hudPanel(g, getX(), getY(), size, size, bg.get());
		if (!Theme.hudPanelBorder && !hasExplicitPanelOverride()) {
			g.outline(getX(), getY(), size, size, ColorUtil.withAlpha(accentAt(1, 2), 120));
		}
		if (mc().player == null || mc().level == null) {
			return;
		}

		int cx = getX() + size / 2;
		int cy = getY() + size / 2;
		float range = reach.getFloat();
		float scale = (size / 2.0f - 2) / range;
		double px = mc().player.getX();
		double pz = mc().player.getZ();

		boolean rotate = rotateWithCamera.get();
		double yaw = Math.toRadians(mc().player.getYRot());
		float cos = (float) Math.cos(yaw);
		float sin = (float) Math.sin(yaw);

		g.enableScissor(getX() + 1, getY() + 1, getX() + size - 1, getY() + size - 1);
		if (terrain.get()) {
			updateTerrain(Mth.ceil(range));
			drawTerrain(g, cx, cy, scale, rotate ? (float) (Math.PI - yaw) : 0.0f);
		} else if (texture != null) {
			releaseTerrain();
		}

		boolean drewEntity = false;
		int head = headSize.getInt();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity) || entity == mc().player) {
				continue;
			}
			int color = colorFor(entity);
			if (color == 0) {
				continue;
			}
			double dx = entity.getX() - px;
			double dz = entity.getZ() - pz;
			if (Math.hypot(dx, dz) > range) {
				continue;
			}
			float sx;
			float sy;
			if (rotate) {
				sx = (float) (-dx * cos - dz * sin);
				sy = (float) (dx * sin - dz * cos);
			} else {
				sx = (float) dx;
				sy = (float) dz;
			}
			int dotX = Math.round(cx + sx * scale);
			int dotY = Math.round(cy + sy * scale);
			float distanceFade = proximityFade.get() ? 1.0f - 0.65f * (float) (Math.hypot(dx, dz) / range) : 1.0f;
			int alpha = Math.round(255 * markerOpacity.getFloat() / 100.0f * distanceFade);
			if (heads.get() && entity instanceof Player player) {
				drawHead(g, player, dotX - head / 2, dotY - head / 2, head, alpha, 0xFF000000);
			} else {
				int marker = dotSize.getInt();
				Render2D.rect(g, dotX - marker / 2, dotY - marker / 2, marker, marker,
						ColorUtil.withAlpha(color, alpha));
			}
			drewEntity = true;
		}
		if (editing && HudManager.isPreviewData() && !drewEntity) {
			previewMarker(g, cx + size / 5, cy - size / 6, playerColor.get());
			previewMarker(g, cx - size / 4, cy + size / 5, hostileColor.get());
			previewMarker(g, cx + size / 8, cy + size / 3, passiveColor.get());
		}
		g.disableScissor();

		// player marker at center
		if (heads.get()) {
			drawHead(g, mc().player, cx - head / 2, cy - head / 2, head, 255, accentAt(1, 1));
		} else {
			Render2D.rect(g, cx - 1, cy - 1, 3, 3, accentAt(1, 1));
		}
	}

	private void drawHead(GuiGraphicsExtractor g, Player player, int x, int y, int size, int alpha, int frame) {
		Render2D.rect(g, x - 1, y - 1, size + 2, size + 2, ColorUtil.withAlpha(frame, alpha));
		HeadRenderer.draw(g, player.getUUID(), x, y, size, ColorUtil.withAlpha(0xFFFFFFFF, alpha));
	}

	// ------------------------------------------------------------------ terrain

	/**
	 * Keeps the map covering the view: re-anchors (sliding what it already has) once the player
	 * nears its edge, then rescans the next {@link #BUDGET} columns in a loop over the whole map.
	 */
	private void updateTerrain(int radius) {
		Level level = mc().level;
		int wanted = (radius + MARGIN) * 2 + 1;
		if (texture == null || side != wanted || level != mapLevel) {
			releaseTerrain();
			side = wanted;
			mapLevel = level;
			colors = new int[side * side];
			heights = new int[side * side];
			texture = new DynamicTexture(TERRAIN::toString, new NativeImage(side, side, true));
			mc().getTextureManager().register(TERRAIN, texture);
			originX = mc().player.getBlockX() - side / 2;
			originZ = mc().player.getBlockZ() - side / 2;
			cursor = 0;
		}

		int offX = mc().player.getBlockX() - (originX + side / 2);
		int offZ = mc().player.getBlockZ() - (originZ + side / 2);
		if (Math.abs(offX) > MARGIN / 2 || Math.abs(offZ) > MARGIN / 2) {
			shift(offX, offZ);
		}

		boolean ceiling = level.dimensionType().hasCeiling();
		int playerY = mc().player.getBlockY();
		int count = side * side;
		for (int n = 0; n < Math.min(BUDGET, count); n++) {
			int index = cursor;
			cursor = (cursor + 1) % count;
			int i = index % side;
			int j = index / side;
			int color = scanColumn(level, originX + i, originZ + j, ceiling, playerY, index, j);
			if (color != colors[index]) {
				colors[index] = color;
				dirty = true;
			}
		}

		long now = System.currentTimeMillis();
		if (dirty && now - uploadedAt >= UPLOAD_MS) {
			NativeImage pixels = texture.getPixels();
			for (int j = 0; j < side; j++) {
				for (int i = 0; i < side; i++) {
					pixels.setPixel(i, j, colors[j * side + i]);
				}
			}
			texture.upload();
			dirty = false;
			uploadedAt = now;
		}
	}

	/** Moves the map's origin by (dx, dz), keeping the columns both positions cover. */
	private void shift(int dx, int dz) {
		int[] oldColors = colors;
		int[] oldHeights = heights;
		colors = new int[side * side];
		heights = new int[side * side];
		for (int j = 0; j < side; j++) {
			int oj = j + dz;
			if (oj < 0 || oj >= side) {
				continue;
			}
			for (int i = 0; i < side; i++) {
				int oi = i + dx;
				if (oi >= 0 && oi < side) {
					colors[j * side + i] = oldColors[oj * side + oi];
					heights[j * side + i] = oldHeights[oj * side + oi];
				}
			}
		}
		originX += dx;
		originZ += dz;
		dirty = true;
	}

	/**
	 * The map colour of the column's top block, shaded brighter or darker than the column north of
	 * it like a vanilla map; transparent where the chunk is not loaded. Under a ceiling (the
	 * Nether) the top block is looked for from just above the player down, not from the roof.
	 */
	private int scanColumn(Level level, int x, int z, boolean ceiling, int playerY, int index, int row) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return 0;
		}
		int top = ceiling ? playerY + 2 : level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		int bottom = Math.max(top - (ceiling ? 48 : 16), level.getMinY());
		if (ceiling) {
			// skip the solid block we start inside, so a low ceiling does not hide the floor
			while (top > bottom && !level.getBlockState(probe.set(x, top, z)).isAir()) {
				top--;
			}
		}
		for (int y = top; y >= bottom; y--) {
			BlockState state = level.getBlockState(probe.set(x, y, z));
			MapColor color = state.getMapColor(level, probe);
			if (color == MapColor.NONE) {
				continue;
			}
			heights[index] = y;
			int north = row > 0 ? heights[index - side] : y;
			float light = y > north ? 1.0f : y < north ? 0.71f : 0.86f;
			return ColorUtil.lerp(0xFF000000, 0xFF000000 | color.col, light);
		}
		return 0;
	}

	private void drawTerrain(GuiGraphicsExtractor g, int cx, int cy, float scale, float angle) {
		double localX = mc().player.getX() - originX;
		double localZ = mc().player.getZ() - originZ;
		int alpha = Math.round(255 * terrainOpacity.getFloat() / 100.0f);
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate(angle);
		g.pose().scale(scale, scale);
		g.pose().translate((float) -localX, (float) -localZ);
		g.blit(RenderPipelines.GUI_TEXTURED, TERRAIN, 0, 0, 0.0f, 0.0f, side, side, side, side,
				ColorUtil.withAlpha(0xFFFFFFFF, alpha));
		g.pose().popMatrix();
	}

	private void releaseTerrain() {
		if (texture != null) {
			mc().getTextureManager().release(TERRAIN);
			texture = null;
		}
	}

	private int colorFor(Entity entity) {
		if (entity instanceof Player) {
			return players.get() ? playerColor.get() : 0;
		}
		if (entity instanceof Enemy) {
			return hostiles.get() ? hostileColor.get() : 0;
		}
		return passives.get() ? passiveColor.get() : 0;
	}

	private void previewMarker(GuiGraphicsExtractor g, int x, int y, int color) {
		int marker = dotSize.getInt();
		Render2D.rect(g, x - marker / 2, y - marker / 2, marker, marker,
				ColorUtil.withAlpha(color, Math.round(255 * markerOpacity.getFloat() / 100.0f)));
	}
}
