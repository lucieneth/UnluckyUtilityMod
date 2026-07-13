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

	public Friends() {
		super("Friends", "Mark friends: middle-click players, blue dot in tablist and nametags", Category.MISC);
		setEnabledSilently(true); // on unless the config says otherwise
	}

	/** True when the tablist should mark {@code uuid} — checked from the tab overlay mixin. */
	public boolean marksTablist(java.util.UUID uuid) {
		return isEnabled() && tablistDot.get() && FriendManager.isFriend(uuid);
	}

	/** True when NameTags should mark {@code uuid}. */
	public boolean marksNametag(java.util.UUID uuid) {
		return isEnabled() && nametagDot.get() && FriendManager.isFriend(uuid);
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
