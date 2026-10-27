package unlucky.utility.client.module.modules.misc;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.FriendManager;

/**
 * Alerts for the things worth looking up from the fight for: a player coming into (or leaving)
 * visual range, a totem popping, a piece of your armour wearing thin.
 *
 * <p>Toasts go through the client's own {@code NotificationManager}, the same achievement-style
 * popups module toggles and Friends use, so colour and sound follow the HUD settings.
 *
 * <p>Totem pops are counted by {@code SessionTracker} either way. With "Totem pops" on, this
 * module announces them and the PopCounter widget's own "Announce pops" toast stands down, so
 * one pop never shows up twice.
 *
 * <p>"Visual range" is the server's entity tracking range: a player is in it from the moment
 * the server starts sending them to us. The players already there when you join or change
 * dimension are taken as known, so a world join does not read out the whole server.
 */
public class Notifier extends Module {
	public final ModeSetting output = add(new ModeSetting("Output", "Where alerts go", "Toast",
			"Toast", "Chat", "Both"));
	public final BooleanSetting visualRange = add(new BooleanSetting("Visual range",
			"Players entering or leaving render distance", true));
	public final BooleanSetting leaving = add(new BooleanSetting("Leaving",
			"Also announce players leaving range", true), visualRange::get);
	public final BooleanSetting friends = add(new BooleanSetting("Friends",
			"Include friends in visual range alerts", true), visualRange::get);
	public final BooleanSetting pops = add(new BooleanSetting("Totem pops",
			"Announce every totem pop (the PopCounter toast stands down)", true));
	public final BooleanSetting lowArmor = add(new BooleanSetting("Low armor",
			"Warn when a worn piece drops below the threshold", true));
	public final NumberSetting armorThreshold = add(new NumberSetting("Armor threshold",
			"Durability percent that counts as low", 20, 1, 50, 1), lowArmor::get);

	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
	/** Percent above the threshold a piece must be mended back to before it can warn again. */
	private static final int REARM_MARGIN = 5;

	/** Players in range last tick, by UUID, with the name to announce them by once they leave. */
	private final Map<UUID, String> inRange = new HashMap<>();
	private final Set<EquipmentSlot> warned = EnumSet.noneOf(EquipmentSlot.class);
	private ClientLevel lastLevel;

	public Notifier() {
		super("Notifier", "Alerts for visual range, totem pops and low armor", Category.MISC,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		inRange.clear();
		warned.clear();
		lastLevel = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null) {
			inRange.clear();
			lastLevel = null;
			return;
		}
		boolean fresh = level != lastLevel;
		lastLevel = level;
		if (visualRange.get()) {
			trackRange(player, level, fresh);
		} else {
			inRange.clear();
		}
		if (lowArmor.get()) {
			checkArmor(player);
		}
	}

	private void trackRange(LocalPlayer player, ClientLevel level, boolean fresh) {
		if (fresh) {
			inRange.clear();
		}
		Map<UUID, String> now = new HashMap<>();
		for (Player other : level.players()) {
			if (other != player) {
				now.put(other.getUUID(), other.getName().getString());
			}
		}
		if (!fresh) {
			for (Map.Entry<UUID, String> entry : now.entrySet()) {
				if (!inRange.containsKey(entry.getKey()) && announces(entry.getKey())) {
					notify("Visual range", label(entry.getKey(), entry.getValue()) + " entered",
							new ItemStack(Items.ENDER_EYE));
				}
			}
			if (leaving.get()) {
				for (Map.Entry<UUID, String> entry : inRange.entrySet()) {
					if (!now.containsKey(entry.getKey()) && announces(entry.getKey())) {
						notify("Visual range", label(entry.getKey(), entry.getValue()) + " left",
								new ItemStack(Items.ENDER_PEARL));
					}
				}
			}
		}
		inRange.clear();
		inRange.putAll(now);
	}

	private boolean announces(UUID uuid) {
		return friends.get() || !FriendManager.isFriend(uuid);
	}

	private static String label(UUID uuid, String name) {
		return FriendManager.isFriend(uuid) ? name + " (friend)" : name;
	}

	private void checkArmor(LocalPlayer player) {
		int threshold = armorThreshold.getInt();
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isDamageableItem()) {
				warned.remove(slot);
				continue;
			}
			int percent = (stack.getMaxDamage() - stack.getDamageValue()) * 100 / stack.getMaxDamage();
			if (percent <= threshold) {
				if (warned.add(slot)) {
					notify("Low armor", stack.getHoverName().getString() + " at " + percent + "%",
							stack.copyWithCount(1));
				}
			} else if (percent > threshold + REARM_MARGIN) {
				warned.remove(slot);
			}
		}
	}

	/**
	 * SessionTracker, on every totem pop.
	 *
	 * @return whether the pop was announced here, so the PopCounter toast can skip it
	 */
	public boolean onPop(LivingEntity entity, int count) {
		if (!isEnabled() || !pops.get()) {
			return false;
		}
		String who = entity == mc().player ? "You" : entity.getDisplayName().getString();
		notify("Totem", who + " popped (" + count + ")", new ItemStack(Items.TOTEM_OF_UNDYING));
		return true;
	}

	private void notify(String header, String text, ItemStack icon) {
		if (!output.is("Chat")) {
			UnluckyClient.INSTANCE.notifications.add(header, text, icon);
		}
		if (!output.is("Toast")) {
			ChatUtil.info("§7" + header + ": §f" + text);
		}
	}
}
