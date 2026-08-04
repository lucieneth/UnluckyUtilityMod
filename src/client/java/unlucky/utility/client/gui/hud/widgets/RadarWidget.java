package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Top-down radar of nearby entities. Positions are the horizontal offset from
 * the player, scaled to the canvas and optionally rotated so the camera faces up.
 */
public class RadarWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Radar", "Top-down radar of nearby entities", false));
	public final BooleanSetting bg = add(new BooleanSetting("Radar bg", "Backing behind the radar", true));
	public final NumberSetting reach = add(new NumberSetting("Radar range", "Blocks shown from center to edge", 48, 8, 128, 4));
	public final NumberSetting canvasSize = add(new NumberSetting("Radar size", "Canvas size in pixels", 90, 60, 160, 10));
	public final BooleanSetting rotateWithCamera = add(new BooleanSetting("Radar rotate", "Rotate with the camera (off = north up)", true));
	public final BooleanSetting players = add(new BooleanSetting("Radar players", "Show players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Radar hostiles", "Show hostile mobs", true));
	public final BooleanSetting passives = add(new BooleanSetting("Radar passives", "Show passive mobs", false));
	public final ColorSetting playerColor = add(new ColorSetting("Radar player color", "Color used for players", Theme.hudAccent1));
	public final ColorSetting hostileColor = add(new ColorSetting("Radar hostile color", "Color used for hostile mobs", 0xFFE04545));
	public final ColorSetting passiveColor = add(new ColorSetting("Radar passive color", "Color used for passive mobs", 0xFF3FD46A));
	public final NumberSetting dotSize = add(new NumberSetting("Radar dot size", "Size of entity markers", 3, 1, 6, 1));
	public final NumberSetting markerOpacity = add(new NumberSetting("Radar marker opacity", "Opacity of entity markers", 100, 10, 100, 5));
	public final BooleanSetting proximityFade = add(new BooleanSetting("Radar proximity fade", "Fade markers as they approach the edge", true));

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
		boolean drewEntity = false;
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
			int marker = dotSize.getInt();
			Render2D.rect(g, dotX - marker / 2, dotY - marker / 2, marker, marker,
					ColorUtil.withAlpha(color, alpha));
			drewEntity = true;
		}
		if (editing && HudManager.isPreviewData() && !drewEntity) {
			previewMarker(g, cx + size / 5, cy - size / 6, playerColor.get());
			previewMarker(g, cx - size / 4, cy + size / 5, hostileColor.get());
			previewMarker(g, cx + size / 8, cy + size / 3, passiveColor.get());
		}
		g.disableScissor();

		// player marker at center
		Render2D.rect(g, cx - 1, cy - 1, 3, 3, accentAt(1, 1));
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
