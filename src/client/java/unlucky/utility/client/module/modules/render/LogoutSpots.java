package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.HeadRenderer;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * A ghost of the last place you saw someone before they disconnected — their
 * box, their head, their name and how long ago they dropped.
 *
 * <p>Detection is Meteor's: watch the tab list. A uuid that leaves
 * {@code getOnlinePlayerIds()} has genuinely disconnected, which is what
 * separates a logout from someone merely walking out of render distance (their
 * entity unloads either way). We only raise a ghost for players we had actually
 * loaded, since that's the only way we know where they were standing.
 */
public class LogoutSpots extends Module {
	public final BooleanSetting box = add(new BooleanSetting("Box", "Draw a box where they logged", true));
	public final BooleanSetting head = add(new BooleanSetting("Head", "Their face above the box", true));
	public final BooleanSetting health = add(new BooleanSetting("Health", "The health they had when they left", true));
	public final NumberSetting expire = add(new NumberSetting("Expire", "Forget a spot after this many minutes (0 = never)", 10, 0, 60, 1));
	public final ColorSetting color = add(new ColorSetting("Color", "Ghost color", 0xFFB478FF));

	/** Where a player was standing, kept fresh while they're loaded. */
	private record Snapshot(String name, AABB box, float health, float maxHealth) {
	}

	/** A player who has actually left, frozen at their last known spot. */
	private record Ghost(UUID uuid, String name, AABB box, float health, float maxHealth, long at) {
	}

	private final Map<UUID, Snapshot> lastSeen = new HashMap<>();
	private final Set<UUID> online = new HashSet<>();
	private final List<Ghost> ghosts = new ArrayList<>();
	private final double[] proj = new double[3];
	private String dimension;

	public LogoutSpots() {
		super("LogoutSpots", "Mark where players were when they disconnected", Category.RENDER);
	}

	@Override
	protected void onDisable() {
		clear();
	}

	private void clear() {
		lastSeen.clear();
		online.clear();
		ghosts.clear();
	}

	@Override
	public void onTick() {
		if (!isEnabled() || mc().player == null || mc().level == null || mc().getConnection() == null) {
			return;
		}
		// a dimension change teleports everyone: the old spots are meaningless
		String current = mc().level.dimension().identifier().getPath();
		if (!current.equals(dimension)) {
			dimension = current;
			clear();
		}

		for (Player player : mc().level.players()) {
			if (player == mc().player || !(player instanceof AbstractClientPlayer)) {
				continue;
			}
			lastSeen.put(player.getUUID(), new Snapshot(player.getName().getString(),
					player.getBoundingBox(), player.getHealth(), player.getMaxHealth()));
		}

		Set<UUID> nowOnline = new HashSet<>(mc().getConnection().getOnlinePlayerIds());
		for (UUID uuid : online) {
			if (nowOnline.contains(uuid) || uuid.equals(mc().player.getUUID())) {
				continue;
			}
			Snapshot snapshot = lastSeen.remove(uuid);
			if (snapshot != null) {
				ghosts.add(new Ghost(uuid, snapshot.name(), snapshot.box(),
						snapshot.health(), snapshot.maxHealth(), System.currentTimeMillis()));
			}
		}
		online.clear();
		online.addAll(nowOnline);

		long ttl = expire.getInt() * 60_000L;
		long now = System.currentTimeMillis();
		for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext();) {
			Ghost ghost = it.next();
			// they came back — the ghost has served its purpose
			if (nowOnline.contains(ghost.uuid()) || (ttl > 0 && now - ghost.at() > ttl)) {
				it.remove();
			}
		}

		if (box.get()) {
			for (Ghost ghost : ghosts) {
				int argb = tint(ghost);
				Render3D.box(ghost.box(), argb, 1.5f, ColorUtil.withAlpha(argb, 40), true);
			}
		}
	}

	/** Friends keep their friend color, so you can tell who logged at a glance. */
	private int tint(Ghost ghost) {
		int friend = UnluckyClient.INSTANCE.modules.get(Friends.class).dotColor(ghost.uuid());
		return friend != 0 ? friend : color.get();
	}

	/** Head + name + "left 4m ago" + health, projected above the box. Called from the HUD layer. */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!isEnabled() || ghosts.isEmpty() || mc().player == null) {
			return;
		}
		int guiWidth = g.guiWidth();
		int guiHeight = g.guiHeight();
		long now = System.currentTimeMillis();

		for (Ghost ghost : ghosts) {
			AABB aabb = ghost.box();
			if (!Render3D.worldToScreen(aabb.getCenter().x, aabb.maxY + 0.5, aabb.getCenter().z,
					guiWidth, guiHeight, proj)) {
				continue;
			}
			int cx = (int) proj[0];
			int cy = (int) proj[1];
			int argb = tint(ghost);

			String label = ghost.name() + "  " + ago(now - ghost.at());
			int width = Render2D.width(label);
			int headSize = head.get() ? 10 : 0;
			int left = cx - (width + headSize + 2) / 2;

			if (head.get()) {
				HeadRenderer.draw(g, ghost.uuid(), left, cy - 5, headSize, 0xFFFFFFFF);
			}
			Render2D.text(g, label, left + headSize + 2, cy - 4, argb);

			if (health.get() && ghost.maxHealth() > 0) {
				String hp = (int) Math.ceil(ghost.health()) + " HP";
				float frac = ghost.health() / ghost.maxHealth();
				int hpColor = frac <= 0.33f ? 0xFFFF5555 : frac <= 0.66f ? 0xFFFFAA00 : 0xFF55FF55;
				Render2D.text(g, hp, cx - Render2D.width(hp) / 2, cy + 6, hpColor);
			}
		}
	}

	/** "4m ago" / "2h ago" — coarse on purpose, it's a "how stale is this" signal. */
	private static String ago(long millis) {
		long minutes = millis / 60_000L;
		if (minutes < 1) {
			return "just now";
		}
		if (minutes < 60) {
			return minutes + "m ago";
		}
		return (minutes / 60) + "h ago";
	}
}
