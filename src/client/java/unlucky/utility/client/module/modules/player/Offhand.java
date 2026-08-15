package unlucky.utility.client.module.modules.player;

import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.combat.CrystalAura;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.OffhandManager;

/**
 * Decides what lives in your offhand when a totem does not.
 *
 * <p>Everything below AutoTotem used to be AutoTotem's problem — it carried a "preferred
 * fallback" setting that had nothing to do with totems and no way to express "gapple while I
 * hold a sword, crystal while the aura is running". That behaviour moved here, along with a
 * config migration so existing profiles keep the outcome they had.
 *
 * <p><b>AutoTotem always wins and this module never argues.</b> Every request here goes to
 * {@link OffhandManager} at {@code REPLENISH} for the baseline and {@code COMBAT} for a
 * contextual override; the totem sits at {@code TOTEM} above both. There is no case in which
 * something this module wants is worth more than being alive, so there is no case to write.
 *
 * <p><b>Overrides do not stack their undo.</b> OffhandManager remembers only the <em>first</em>
 * displaced item across nested requests, which is what makes "put it back" mean the shield you
 * were carrying before any of this started rather than whatever the previous override left
 * there. That is its rule, not this module's, and it is the reason this one has no restore
 * bookkeeping of its own.
 */
public class Offhand extends Module {
	/** How long between missing-item notices, so a permanent shortage is not a permanent toast. */
	private static final int NOTIFY_INTERVAL = 600;

	public final ModeSetting normalItem = add(new ModeSetting("Normal item",
			"What the offhand holds when nothing more specific applies. Previous means no request "
					+ "at all, so whatever was displaced comes back.",
			"Previous", "Previous", "Golden apple", "Shield", "Crystal", "Pearl", "Custom"));
	public final ItemListSetting customItems = add(new ItemListSetting("Custom items",
			"Preference order for Custom — right-click to pick", item -> true),
			() -> normalItem.is("Custom"));

	public final ModeSetting swordOverride = add(new ModeSetting("Sword override",
			"What to hold while a sword is in the main hand", "Golden apple",
			"Off", "Golden apple", "Shield"));
	public final ModeSetting rightClickOverride = add(new ModeSetting("Right-click override",
			"What to hold while the use key is held", "Off", "Off", "Golden apple", "Shield"));
	public final ModeSetting lowHealthOverride = add(new ModeSetting("Low-health override",
			"What to hold below the health threshold", "Golden apple",
			"Off", "Golden apple", "Shield"));
	public final NumberSetting lowHealthThreshold = add(new NumberSetting("Low-health threshold",
			"Health plus absorption at which the low-health override applies", 14, 1, 36, 1),
			() -> !lowHealthOverride.is("Off"));
	public final ModeSetting crystalAuraOverride = add(new ModeSetting("CrystalAura override",
			"What to hold while CrystalAura is working", "Crystal", "Off", "Crystal"));

	public final BooleanSetting searchHotbar = add(new BooleanSetting("Search hotbar",
			"Allow the offhand to be filled from a hotbar slot", true));
	public final BooleanSetting searchInventory = add(new BooleanSetting("Search inventory",
			"Allow the offhand to be filled from the main inventory", true));
	public final NumberSetting switchDelay = add(new NumberSetting("Switch delay",
			"Base ticks between swaps", 1, 0, 20, 1));
	public final BooleanSetting restorePrevious = add(new BooleanSetting("Restore previous",
			"Put the first displaced item back when the request ends", true));
	public final BooleanSetting pauseInContainers = add(new BooleanSetting("Pause in containers",
			"Do not baseline-swap while a non-player menu is open", true));
	public final BooleanSetting notifyMissing = add(new BooleanSetting("Notify missing",
			"Say so, occasionally, when the wanted item is nowhere to be found", true));

	private int delayTicks;
	private int sinceNotify = NOTIFY_INTERVAL;

	public Offhand() {
		super("Offhand", "Chooses your non-totem offhand item", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		delayTicks = 0;
		sinceNotify = NOTIFY_INTERVAL;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()) {
			return;
		}
		if (sinceNotify < NOTIFY_INTERVAL) {
			sinceNotify++;
		}
		if (delayTicks > 0) {
			delayTicks--;
			return;
		}
		if (pauseInContainers.get() && OffhandManager.isBlocked()) {
			return;
		}

		Choice choice = chooseOverride(player);
		if (choice != null) {
			ask(choice, OffhandManager.PRIORITY_COMBAT);
			return;
		}
		Choice baseline = chooseBaseline();
		if (baseline != null) {
			ask(baseline, OffhandManager.PRIORITY_REPLENISH);
		}
		// "Previous" is the absence of a request, not a request for the previous item: with
		// nobody asking, OffhandManager puts back whatever it displaced. Reconstructing that
		// here would be a worse answer than the one it already holds.
	}

	/** A wanted item and how to describe it. */
	private record Choice(Predicate<ItemStack> wanted, String label) {
	}

	/**
	 * The contextual override that applies, if any.
	 *
	 * <p>Ordered most-urgent first and deliberately not summed: low health beats a sword in the
	 * hand, and the crystal aura beats both because holding a crystal is the thing it is doing.
	 */
	private Choice chooseOverride(LocalPlayer player) {
		CrystalAura aura = UnluckyClient.INSTANCE.modules.get(CrystalAura.class);
		if (!crystalAuraOverride.is("Off") && aura.isEnabled() && aura.isActing()) {
			return new Choice(stack -> stack.is(Items.END_CRYSTAL), "Crystal");
		}
		if (!lowHealthOverride.is("Off")
				&& player.getHealth() + player.getAbsorptionAmount() <= lowHealthThreshold.get()) {
			return named(lowHealthOverride.get());
		}
		if (!rightClickOverride.is("Off") && mc().options.keyUse.isDown()) {
			return named(rightClickOverride.get());
		}
		if (!swordOverride.is("Off") && CombatItemUtil.isSword(player.getMainHandItem())) {
			return named(swordOverride.get());
		}
		return null;
	}

	private Choice chooseBaseline() {
		return switch (normalItem.get()) {
			case "Golden apple", "Shield" -> named(normalItem.get());
			case "Crystal" -> new Choice(stack -> stack.is(Items.END_CRYSTAL), "Crystal");
			case "Pearl" -> new Choice(stack -> stack.is(Items.ENDER_PEARL), "Ender pearl");
			case "Custom" -> customChoice();
			default -> null;
		};
	}

	/**
	 * The Custom list as one predicate.
	 *
	 * <p>Any listed item matches rather than the first in list order, because OffhandManager
	 * searches slots and takes the first match it finds. Insisting on the list's own order would
	 * mean rejecting a perfectly good second choice sitting in the hotbar in favour of a first
	 * choice the player does not have.
	 */
	private Choice customChoice() {
		return customItems.get().isEmpty() ? null
				: new Choice(stack -> customItems.contains(stack.getItem()), "Custom item");
	}

	private static Choice named(String mode) {
		if (mode.equals("Shield")) {
			return new Choice(stack -> stack.is(Items.SHIELD), "Shield");
		}
		return new Choice(stack -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE),
				"Golden apple");
	}

	/** Files the request, and notices when nothing in the bag can satisfy it. */
	private void ask(Choice choice, int priority) {
		OffhandManager.request(this, priority, choice.wanted(), choice.label(),
				restorePrevious.get(), this::slotAllowed);
		delayTicks = switchDelay.getInt();
		if (notifyMissing.get() && sinceNotify >= NOTIFY_INTERVAL && !available(choice)) {
			sinceNotify = 0;
			UnluckyClient.INSTANCE.notifications.add("Offhand",
					"No " + choice.label().toLowerCase() + " to hold", ItemUtil.icon(Items.SHIELD));
		}
	}

	private boolean slotAllowed(int menuSlot) {
		return OffhandManager.isHotbarSlot(menuSlot) ? searchHotbar.get() : searchInventory.get();
	}

	/** Whether the bag holds anything matching, within the slots we are allowed to take from. */
	private boolean available(Choice choice) {
		LocalPlayer player = mc().player;
		if (player == null) {
			return false;
		}
		if (choice.wanted().test(player.getOffhandItem())) {
			return true;
		}
		var menu = player.inventoryMenu;
		int last = Math.min(44, menu.slots.size() - 1);
		for (int slot = 9; slot <= last; slot++) {
			if (slotAllowed(slot) && choice.wanted().test(menu.getSlot(slot).getItem())) {
				return true;
			}
		}
		return false;
	}
}
