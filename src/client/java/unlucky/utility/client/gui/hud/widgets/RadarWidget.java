package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
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

	private static final int HOSTILE = 0xFFE04545;
	private static final int PASSIVE = 0xFF3FD46A;

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
		Render2D.roundedRect(g, getX(), getY(), size, size, 4, Theme.hudBg(bg.get()));
		g.outline(getX(), getY(), size, size, ColorUtil.withAlpha(Theme.hudAccent(0.5f), 120));
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
			Render2D.rect(g, dotX - 1, dotY - 1, 3, 3, color);
		}
		g.disableScissor();

		// player marker at center
		Render2D.rect(g, cx - 1, cy - 1, 3, 3, Theme.hudAccent(1.0f));
	}

	private int colorFor(Entity entity) {
		if (entity instanceof Player) {
			return players.get() ? Theme.hudAccent(0.3f) : 0;
		}
		if (entity instanceof Enemy) {
			return hostiles.get() ? HOSTILE : 0;
		}
		return passives.get() ? PASSIVE : 0;
	}
}
