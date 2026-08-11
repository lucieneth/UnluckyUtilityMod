package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.misc.AutoReconnect;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.EntityListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.DamageForecast;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.FakePlayerEntity;

/**
 * Leaves the server before the thing that was going to kill you does.
 *
 * <p>Every trigger here is a different answer to "how much warning do I get". Health is the
 * one with none — by the time it reads six you have already taken the hit — so the useful
 * ones are the predictions: the crystal that is placed but not yet broken, the fall that is
 * already decided, the void with nothing under it. Those come from {@link DamageForecast}
 * rather than being recalculated, so this module and AutoTotem can never disagree about
 * whether you were going to survive.
 *
 * <p><b>It tells AutoReconnect what it did.</b> A safety logout that is politely undone four
 * seconds later by a reconnect is worse than no logout at all — it puts you back in the fight
 * with fewer totems and no warning. {@link AutoReconnect#markDeliberate} is called before the
 * disconnect, not after, because afterwards there is nothing left to ask.
 */
public class AutoLog extends Module {
	public final NumberSetting health = add(new NumberSetting("Health",
			"Log out at or below this much health, counting absorption. 0 never triggers on health.",
			6, 0, 20, 1));
	public final BooleanSetting predictDamage = add(new BooleanSetting("Predict incoming damage",
			"Log out when a crystal or TNT in range would be lethal, whether or not it has gone "
					+ "off yet", true));
	public final BooleanSetting voidLethal = add(new BooleanSetting("Void and fall",
			"Log out on a fall that would kill you, or a drop with nothing under it", true));
	public final BooleanSetting onPops = add(new BooleanSetting("Totem pops",
			"Log out after popping this many totems this session", false));
	public final NumberSetting popLimit = add(new NumberSetting("Pop limit",
			"Pops before logging out", 2, 1, 20, 1), onPops::get);
	public final BooleanSetting onTotemsLeft = add(new BooleanSetting("Totems remaining",
			"Log out when you are nearly out of totems", false));
	public final NumberSetting totemLimit = add(new NumberSetting("Totems left",
			"Log out at or below this many", 1, 0, 20, 1), onTotemsLeft::get);
	public final ModeSetting untrusted = add(new ModeSetting("Untrusted player",
			"Log out when somebody who is not a friend shows up", "Off",
			"Off", "Any non-friend", "Within distance"));
	public final NumberSetting playerDistance = add(new NumberSetting("Player distance",
			"How close a non-friend has to get", 16, 4, 128, 4),
			() -> untrusted.is("Within distance"));
	public final BooleanSetting onEntityCount = add(new BooleanSetting("Entity count",
			"Log out when too many of the selected entities are nearby", false));
	public final EntityListSetting entities = add(new EntityListSetting("Entity types",
			"Which entities to count. Nothing deselected means everything counts."),
			onEntityCount::get);
	public final NumberSetting entityCount = add(new NumberSetting("Count",
			"How many it takes", 5, 1, 64, 1), onEntityCount::get);
	public final NumberSetting entityRange = add(new NumberSetting("Range",
			"How far to count them", 24, 4, 128, 4), onEntityCount::get);

	public final BooleanSetting announce = add(new BooleanSetting("Disconnect message",
			"Say exactly what triggered it, in chat and on the disconnect screen", true));
	public final BooleanSetting disableAfter = add(new BooleanSetting("Disable after trigger",
			"Switch the module off once it fires, so reconnecting does not immediately log you "
					+ "out again", true));
	public final ModeSetting reconnectPolicy = add(new ModeSetting("Reconnect policy",
			"Never and Manual keep a danger logout disconnected. After AutoReconnect delay uses "
					+ "AutoReconnect's normal delay and retry controls.", "Never", "Never",
			"After AutoReconnect delay", "Manual"));
	public final BooleanSetting suppressReconnect = add(new BooleanSetting("Suppress AutoReconnect",
			"For Never, prevent AutoReconnect's generic manual-disconnect rule from overriding "
					+ "this safety logout", true), () -> reconnectPolicy.is("Never"));

	public AutoLog() {
		super("AutoLog", "Leaves the server before something kills you", Category.COMBAT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().getConnection() == null
				|| player.isSpectator() || player.isDeadOrDying()) {
			return;
		}
		String reason = trigger(player);
		if (reason != null) {
			logOut(reason);
		}
	}

	/** The first trigger that fires, as text fit to show a human, or null. */
	private String trigger(LocalPlayer player) {
		float effective = ExplosionDamageUtil.effectiveHealth(player);
		if (health.getInt() > 0 && effective <= health.getFloat()) {
			return String.format("health %.1f", effective);
		}
		if (predictDamage.get()) {
			float incoming = DamageForecast.worstNearbyExplosion(player);
			if (incoming > 0.0f && effective - incoming <= 0.0f) {
				return String.format("a nearby explosion would do %.1f to %.1f health",
						incoming, effective);
			}
		}
		if (voidLethal.get()) {
			if (DamageForecast.fallingIntoVoid(player)) {
				return "falling into the void";
			}
			float fall = DamageForecast.predictedFallDamage(player);
			if (fall > 0.0f && effective - fall <= 0.0f) {
				return String.format("this fall would do %.1f to %.1f health", fall, effective);
			}
		}
		if (onPops.get()) {
			int pops = UnluckyClient.INSTANCE.session.selfPops();
			if (pops >= popLimit.getInt()) {
				return pops + " totem pops";
			}
		}
		if (onTotemsLeft.get()) {
			int totems = totemCount(player);
			if (totems <= totemLimit.getInt()) {
				return totems == 0 ? "out of totems" : totems + " totems left";
			}
		}
		if (!untrusted.is("Off")) {
			String who = nearestUntrusted(player);
			if (who != null) {
				return "untrusted player " + who;
			}
		}
		if (onEntityCount.get()) {
			int count = countEntities(player);
			if (count >= entityCount.getInt()) {
				return count + " entities within " + entityRange.getInt();
			}
		}
		return null;
	}

	private void logOut(String reason) {
		if (announce.get()) {
			ChatUtil.info("§cAutoLog: " + reason);
		}
		// Before the disconnect, because afterwards the cause has already been classified.
		// An explicit delayed policy owns the result even when AutoReconnect's own
		// "After AutoLog" preference is off; every other safety policy is deliberate.
		if (reconnectPolicy.is("After AutoReconnect delay")) {
			AutoReconnect.markDeliberate(getName(), true);
		} else if (reconnectPolicy.is("Manual") || suppressReconnect.get()) {
			AutoReconnect.markDeliberate(getName(), false);
		}
		if (disableAfter.get()) {
			setEnabledSilently(false);
		}
		mc().disconnectFromWorld(Component.literal("[Unlucky] AutoLog: " + reason));
	}

	/**
	 * Totems anywhere one swap could reach: the main inventory, the hotbar and the offhand.
	 *
	 * <p>{@code getNonEquipmentItems()} rather than a loop to {@code getContainerSize()},
	 * which counts the equipment slots too — including the offhand, which is then added again
	 * below. That reads as a doubled count exactly when it matters least and misleads most:
	 * one totem in the offhand and none anywhere else looks like two, and the reserve check
	 * lets the module spend it.
	 */
	private static int totemCount(LocalPlayer player) {
		int count = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack.is(Items.TOTEM_OF_UNDYING)) {
				count += stack.getCount();
			}
		}
		if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
			count += player.getOffhandItem().getCount();
		}
		return count;
	}

	/** Name of a non-friend who qualifies, or null. */
	private String nearestUntrusted(LocalPlayer player) {
		double limit = untrusted.is("Within distance") ? playerDistance.get() : Double.MAX_VALUE;
		for (Player other : mc().level.players()) {
			if (other == player || other instanceof FakePlayerEntity
					|| FriendManager.isFriend(other.getUUID())) {
				continue;
			}
			if (player.distanceTo(other) <= limit) {
				return other.getName().getString();
			}
		}
		return null;
	}

	private int countEntities(LocalPlayer player) {
		AABB box = player.getBoundingBox().inflate(entityRange.get());
		int count = 0;
		for (Entity entity : mc().level.getEntities(player, box)) {
			if (entities.allows(entity.getType())) {
				count++;
			}
		}
		return count;
	}
}
