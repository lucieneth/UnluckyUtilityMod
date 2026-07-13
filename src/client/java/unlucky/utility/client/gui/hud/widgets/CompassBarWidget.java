package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.HeadRenderer;
import unlucky.utility.client.util.Render2D;

/**
 * Horizontal compass strip that scrolls with your facing — cardinal letters
 * every 45°, minor ticks every 15°, a center caret for the exact heading.
 * Nearby players are projected onto the bar by bearing as 2D heads (via
 * {@link HeadRenderer}), friends get their blue dot; alpha fades with distance.
 *
 * <p>Everything works in MC yaw space directly (0 = south, 90 = west), so the
 * bearing to a target is just {@code atan2(-dx, dz)} and no basis conversion
 * ever happens: a marker sits under the caret exactly when you face the player.
 */
public class CompassBarWidget extends HudWidget {
	private static final String[] CARDINALS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
	private static final int BAR_HEIGHT = 17;
	private static final int HEAD_ROW = 11;

	public CompassBarWidget() {
		super("CompassBar");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().compass.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.02);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		int width = hud.compassWidth.getInt();
		boolean players = hud.compassPlayers.get();
		int height = players ? BAR_HEIGHT + HEAD_ROW : BAR_HEIGHT;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud.compassBg.get()));
		if (mc().player == null || mc().level == null) {
			return;
		}

		float yaw = Mth.wrapDegrees(mc().player.getYRot());
		float half = hud.compassFov.getFloat() / 2.0f;
		int cx = getX() + width / 2;
		float pxPerDegree = (width / 2.0f - 6) / half;

		g.enableScissor(getX() + 1, getY(), getX() + width - 1, getY() + height);

		// ticks + cardinal letters, every 15° inside the visible window
		int first = Mth.floor((yaw - half) / 15.0f) * 15;
		for (int deg = first; deg <= yaw + half; deg += 15) {
			float delta = Mth.wrapDegrees(deg - yaw);
			if (Math.abs(delta) > half) {
				continue;
			}
			int x = Math.round(cx + delta * pxPerDegree);
			int wrapped = Math.floorMod(deg, 360);
			if (wrapped % 45 == 0) {
				String label = CARDINALS[wrapped / 45];
				Render2D.rect(g, x, getY() + 2, 1, 3, ColorUtil.withAlpha(0xFFFFFFFF, 200));
				Render2D.text(g, label, x - Render2D.width(label) / 2, getY() + 7,
						wrapped == 180 ? Theme.hudAccent(0.0f) : 0xFFE8E8E8);
			} else {
				Render2D.rect(g, x, getY() + 2, 1, 2, ColorUtil.withAlpha(0xFFFFFFFF, 90));
			}
		}

		// player heads projected by bearing
		if (players) {
			float range = hud.compassRange.getFloat();
			boolean friendsOnly = hud.compassFriendsOnly.get();
			int headY = getY() + BAR_HEIGHT + 1;
			for (Player player : mc().level.players()) {
				if (player == mc().player || !(player instanceof AbstractClientPlayer)) {
					continue;
				}
				if (friendsOnly && !FriendManager.isFriend(player.getUUID())) {
					continue;
				}
				double dx = player.getX() - mc().player.getX();
				double dz = player.getZ() - mc().player.getZ();
				double dist = Math.hypot(dx, dz);
				if (dist > range) {
					continue;
				}
				float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
				float delta = Mth.wrapDegrees(bearing - yaw);
				if (Math.abs(delta) > half) {
					continue;
				}
				int hx = Math.round(cx + delta * pxPerDegree) - 4;
				int alpha = 255 - (int) (155 * Math.min(1.0, dist / range));
				HeadRenderer.draw(g, player.getUUID(), hx, headY, 8, (alpha << 24) | 0xFFFFFF);
				int dot = UnluckyClient.INSTANCE.modules
						.get(unlucky.utility.client.module.modules.misc.Friends.class)
						.dotColor(player.getUUID());
				if (dot != 0) {
					Render2D.rect(g, hx + 6, headY + 6, 3, 3, dot);
				}
			}
		}

		g.disableScissor();

		// center caret — the exact heading
		Render2D.rect(g, cx, getY() + 1, 1, BAR_HEIGHT - 2, Theme.hudAccent(0.0f));
	}
}
