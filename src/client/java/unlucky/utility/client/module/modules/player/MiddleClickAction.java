package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;

/**
 * Middle-click to throw a pearl (or a rocket, wind charge or XP bottle) from the hotbar without
 * switching to it.
 *
 * <p>Middle-clicking a player stays with {@link Friends}: when its "Middle click" is on, a
 * player under the crosshair is a friend toggle and nothing gets thrown. Only a click on
 * anything else lands here.
 *
 * <p>Hooked at {@code pickBlockOrEntity} ({@code MinecraftMixin}), which is where vanilla spends
 * the middle button. In survival pick-block would otherwise jump the hotbar to the block you are
 * looking at; in creative it is left alone, since there it is a real tool.
 *
 * <p>The switch, the use and the switch back happen in one tick under the inventory lease at
 * manual priority — this is the player's own click, so it outranks any module's automation.
 */
public class MiddleClickAction extends Module {
	public final ModeSetting action = add(new ModeSetting("Action", "What a middle-click throws",
			"Pearl", "Pearl", "Rocket", "Wind charge", "XP bottle"));
	public final BooleanSetting notifyMissing = add(new BooleanSetting("Notify missing",
			"Toast when the hotbar has none of the item", true));

	public MiddleClickAction() {
		super("MiddleClickAction", "Middle-click throws a pearl, rocket, wind charge or XP bottle",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * MinecraftMixin, at pick-block.
	 *
	 * @return whether the click was spent here, so vanilla's pick-block should not run
	 */
	public boolean onPick() {
		LocalPlayer player = mc().player;
		if (!isEnabled() || player == null || mc().gameMode == null || player.isCreative()
				|| player.isSpectator()) {
			return false;
		}
		Friends friends = UnluckyClient.INSTANCE.modules.get(Friends.class);
		if (mc().crosshairPickEntity instanceof AbstractClientPlayer other && other != player
				&& friends.isEnabled() && friends.middleClick.get()) {
			return false; // the friend toggle already had this click
		}
		if (player.isUsingItem() || AutoEat.mealInProgress()) {
			return true;
		}
		Item item = item();
		if (player.getOffhandItem().is(item)) {
			if (!onCooldown(player, player.getOffhandItem())) {
				mc().gameMode.useItem(player, InteractionHand.OFF_HAND);
			}
			return true;
		}
		int slot = hotbarSlot(player.getInventory(), item);
		if (slot < 0) {
			if (notifyMissing.get()) {
				UnluckyClient.INSTANCE.notifications.add("MiddleClick",
						"No " + action.get().toLowerCase() + " in hotbar", new ItemStack(item));
			}
			return true;
		}
		if (onCooldown(player, player.getInventory().getItem(slot))
				|| !InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_MANUAL)) {
			return true;
		}
		if (InventoryActionCoordinator.selectHotbar(this, slot)) {
			mc().gameMode.useItem(player, InteractionHand.MAIN_HAND);
		}
		InventoryActionCoordinator.release(this); // puts the old slot back
		return true;
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
	}

	private Item item() {
		return switch (action.get()) {
			case "Rocket" -> Items.FIREWORK_ROCKET;
			case "Wind charge" -> Items.WIND_CHARGE;
			case "XP bottle" -> Items.EXPERIENCE_BOTTLE;
			default -> Items.ENDER_PEARL;
		};
	}

	private static boolean onCooldown(LocalPlayer player, ItemStack stack) {
		return player.getCooldowns().isOnCooldown(stack);
	}

	private static int hotbarSlot(Inventory inventory, Item item) {
		if (inventory.getItem(inventory.getSelectedSlot()).is(item)) {
			return inventory.getSelectedSlot();
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (inventory.getItem(slot).is(item)) {
				return slot;
			}
		}
		return -1;
	}
}
