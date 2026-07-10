package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Top-down radar of nearby entities. Positions are the horizontal offset from
 * the player, scaled to the canvas and optionally rotated so the camera faces up.
 */
public class RadarWidget extends HudWidget {
	private static final int HOSTILE = 0xFFE04545;
	private static final int PASSIVE = 0xFF3FD46A;

	public RadarWidget() {
		super("Radar");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().radar.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.35);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		int size = hud.radarSize.getInt();
		setSize(size, size);
		Render2D.roundedRect(g, getX(), getY(), size, size, 4, Theme.hudBg(hud.radarBg.get()));
		g.outline(getX(), getY(), size, size, ColorUtil.withAlpha(Theme.hudAccent(0.5f), 120));
		if (mc().player == null || mc().level == null) {
			return;
		}

		int cx = getX() + size / 2;
		int cy = getY() + size / 2;
		float range = hud.radarRange.getFloat();
		float scale = (size / 2.0f - 2) / range;
		double px = mc().player.getX();
		double pz = mc().player.getZ();

		boolean rotate = hud.radarRotate.get();
		double yaw = Math.toRadians(mc().player.getYRot());
		float cos = (float) Math.cos(yaw);
		float sin = (float) Math.sin(yaw);

		g.enableScissor(getX() + 1, getY() + 1, getX() + size - 1, getY() + size - 1);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity) || entity == mc().player) {
				continue;
			}
			int color = colorFor(entity, hud);
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

	private static int colorFor(Entity entity, HudModule hud) {
		if (entity instanceof Player) {
			return hud.radarPlayers.get() ? Theme.hudAccent(0.3f) : 0;
		}
		if (entity instanceof Enemy) {
			return hud.radarHostiles.get() ? HOSTILE : 0;
		}
		return hud.radarPassives.get() ? PASSIVE : 0;
	}
}
