package unlucky.utility.client.module.modules.misc;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.FriendManager;

/**
 * The friends system (Phase 1: local only — see the networking phase in plan.md).
 * Friends are stored by UUID in {@code config/unlucky/friends.json} via
 * {@link FriendManager}; this module is the user-facing switchboard: middle-click
 * a player to add/remove them, and a blue {@code •} marks friends in the tablist
 * ({@code PlayerTabOverlayMixin}) and in NameTags. Enabled by default — a friends
 * list that silently ignores clicks would read as broken.
 */
public class Friends extends Module {
	public final BooleanSetting middleClick = add(new BooleanSetting("Middle click", "Middle-click a player to add or remove them", true));
	public final BooleanSetting tablistDot = add(new BooleanSetting("Tablist dot", "Blue dot before friend names in the player list", true));
	public final BooleanSetting nametagDot = add(new BooleanSetting("Nametag dot", "Blue dot on friend NameTags", true));
	public final BooleanSetting chatDot = add(new BooleanSetting("Chat dot", "Blue dot on friend chat heads (needs Heads)", true));
	public final BooleanSetting selfDot = add(new BooleanSetting("Self dot", "Mark yourself green wherever friend dots appear", false));

	public Friends() {
		super("Friends", "Mark friends: middle-click players, blue dot in tablist and nametags", Category.MISC);
	}

	/**
	 * The dot for {@code uuid} on surfaces without their own toggle (locator bar,
	 * compass): friend blue, yourself green when "Self dot" is on, 0 = no dot.
	 */
	public int dotColor(java.util.UUID uuid) {
		if (!isEnabled() || uuid == null) {
			return 0;
		}
		if (selfDot.get() && mc().player != null && uuid.equals(mc().player.getUUID())) {
			return FriendManager.SELF_COLOR;
		}
		return FriendManager.isFriend(uuid) ? FriendManager.COLOR : 0;
	}

	/** Tablist dot color for {@code uuid}, 0 = none — checked from the tab overlay mixin. */
	public int tablistDotColor(java.util.UUID uuid) {
		return tablistDot.get() ? dotColor(uuid) : 0;
	}

	/** NameTags dot color for {@code uuid}, 0 = none. */
	public int nametagDotColor(java.util.UUID uuid) {
		return nametagDot.get() ? dotColor(uuid) : 0;
	}

	/** Chat-head dot color for {@code uuid}, 0 = none. */
	public int chatDotColor(java.util.UUID uuid) {
		return chatDot.get() ? dotColor(uuid) : 0;
	}

	/**
	 * Friendships are keyed by UUID, so they survive name changes — but the
	 * stored display name would go stale. Whenever a friend is on the current
	 * server under a new name, refresh it (checked every 10s, saves only on an
	 * actual change).
	 */
	@Override
	public void onTick() {
		if (mc().getConnection() == null || mc().player == null || mc().player.tickCount % 200 != 0) {
			return;
		}
		for (var info : mc().getConnection().getOnlinePlayers()) {
			String stored = unlucky.utility.client.util.FriendManager.all().get(info.getProfile().id());
			if (stored != null && !stored.equals(info.getProfile().name())) {
				unlucky.utility.client.util.FriendManager.add(info.getProfile().id(), info.getProfile().name());
			}
		}
	}

	/** Middle-click on {@code player} (from the mouse mixin): toggle + toast. */
	public void onMiddleClick(AbstractClientPlayer player) {
		if (!isEnabled() || !middleClick.get()) {
			return;
		}
		String name = player.getName().getString();
		boolean added = FriendManager.toggle(player.getUUID(), name);
		UnluckyClient.INSTANCE.notifications.add("Friends",
				name + (added ? " added" : " removed"),
				new ItemStack(added ? Items.PLAYER_HEAD : Items.SKELETON_SKULL));
	}
}
