package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.EquipmentScorer;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.MoveUtil;

/**
 * Puts the best armour you are carrying on, and keeps it there.
 *
 * <p><b>Which piece is best is {@link EquipmentScorer}'s answer, not this module's.</b> That is
 * what makes it right for trimmed, enchanted and modded armour instead of only for the five
 * vanilla materials — and it is what lets InventoryCleaner agree about which chestplate is the
 * spare when it decides what to throw.
 *
 * <p><b>Nothing here writes an inventory slot directly.</b> Every swap goes through
 * {@link InventoryActionCoordinator} at {@code EQUIPMENT} priority, which is deliberately below
 * ElytraSwap's {@code ELYTRA_SAFETY} and AutoTotem's {@code TOTEM}: a chestplate going on is
 * never more urgent than wings that are about to break or a totem reaching your hand, and a
 * module that outranked either would kill you politely.
 *
 * <p><b>The minimum score gain is the anti-churn rule and it matters.</b> Two chestplates that
 * score within a rounding error of each other will otherwise be swapped back and forth for
 * ever, one click a tick, because each is "better than" the other by the direction you happen
 * to compare them. Requiring a real improvement is what stops that.
 */
public class AutoArmor extends Module {
	/**
	 * Armour slots as the player's own inventory menu lays them out: 0 is the crafting result,
	 * 1-4 the grid, 5-8 the armour head-to-feet, 9-44 the bag, 45 the offhand.
	 */
	private static final int ARMOUR_MENU_BASE = 5;

	private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	public final ModeSetting profile = add(new ModeSetting("Profile",
			"What to armour against; Balanced weights plain Protection highest and still counts "
					+ "the specialists", "Balanced",
			"Balanced", "Protection", "Blast", "Fire", "Projectile"));

	public final BooleanSetting helmetOverride = add(new BooleanSetting("Helmet override",
			"Use a different profile for the helmet", false));
	public final ModeSetting helmetProfile = add(new ModeSetting("Helmet profile",
			"Profile used for the helmet", "Balanced",
			"Balanced", "Protection", "Blast", "Fire", "Projectile"), helmetOverride::get);
	public final BooleanSetting chestOverride = add(new BooleanSetting("Chestplate override",
			"Use a different profile for the chestplate", false));
	public final ModeSetting chestProfile = add(new ModeSetting("Chestplate profile",
			"Profile used for the chestplate", "Balanced",
			"Balanced", "Protection", "Blast", "Fire", "Projectile"), chestOverride::get);
	public final BooleanSetting legsOverride = add(new BooleanSetting("Leggings override",
			"Use a different profile for the leggings", false));
	public final ModeSetting legsProfile = add(new ModeSetting("Leggings profile",
			"Profile used for the leggings", "Blast",
			"Balanced", "Protection", "Blast", "Fire", "Projectile"), legsOverride::get);
	public final BooleanSetting bootsOverride = add(new BooleanSetting("Boots override",
			"Use a different profile for the boots", false));
	public final ModeSetting bootsProfile = add(new ModeSetting("Boots profile",
			"Profile used for the boots", "Balanced",
			"Balanced", "Protection", "Blast", "Fire", "Projectile"), bootsOverride::get);

	public final BooleanSetting blastLeggings = add(new BooleanSetting("Blast leggings",
			"Prefer blast protection on leggings when the scores are otherwise close", true));
	public final BooleanSetting preferMending = add(new BooleanSetting("Prefer mending",
			"A small tiebreak, never enough to beat materially stronger armour", true));
	public final ItemListSetting avoid = add(new ItemListSetting("Avoid items",
			"Never auto-equip these — right-click to pick",
			item -> item.components().has(net.minecraft.core.component.DataComponents.EQUIPPABLE)));
	public final NumberSetting minimumDurability = add(new NumberSetting("Minimum durability",
			"Reject candidates at or below this percentage", 10, 1, 90, 1));
	public final ModeSetting antiBreak = add(new ModeSetting("Anti-break action",
			"What to do about a worn piece that is about to break",
			"Replace", "Ignore", "Unequip", "Replace"));
	public final ModeSetting bindingCurse = add(new ModeSetting("Binding curse",
			"Keep equipped never takes a bound piece off and never puts one on",
			"Keep equipped", "Keep equipped", "Ignore candidate", "Allow"));
	public final ModeSetting elytraPolicy = add(new ModeSetting("Elytra policy",
			"When a worn elytra may be swapped out for chest armour",
			"Grounded", "Never replace", "Grounded", "Always armor"));

	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between inventory actions", 2, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks on top of the base delay", 1, 0, 10, 1));
	public final NumberSetting minimumGain = add(new NumberSetting("Minimum score gain",
			"How much better a candidate must be before it is worth a swap", 1, 0, 20, 1));

	public final BooleanSetting pauseWhileMoving = add(new BooleanSetting("Pause while moving",
			"Do not click while movement input is held", false));
	public final BooleanSetting pauseWhileUsing = add(new BooleanSetting("Pause while using",
			"Pause while eating, drinking, drawing or placing", true));
	public final BooleanSetting inventoryOnly = add(new BooleanSetting("Inventory only",
			"Only act while an inventory screen is open", false));

	private int delayTicks;

	public AutoArmor() {
		super("AutoArmor", "Equips the best armour you are carrying", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		delayTicks = 0;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()) {
			onDisable();
			return;
		}
		if (delayTicks > 0) {
			delayTicks--;
			return;
		}
		if (!allowed(player)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		// One action per tick, and not configurable in this release: an armour swap is three
		// clicks under the hood, and letting four of them run in one tick is a burst nobody
		// asked for and a menu state nothing can reason about afterwards.
		for (EquipmentSlot slot : SLOTS) {
			if (act(player, slot)) {
				delayTicks = delay.getInt()
						+ (randomDelay.getInt() > 0 ? (int) (Math.random() * (randomDelay.getInt() + 1)) : 0);
				return;
			}
		}
		InventoryActionCoordinator.release(this);
	}

	/** Every condition that says "not now", in one place so they cannot drift apart. */
	private boolean allowed(LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			// A foreign container means the player's own menu is not the one the server has us
			// in; clicking it anyway is a desync, not a swap.
			return false;
		}
		if (inventoryOnly.get() && mc().gui.screen() == null) {
			return false;
		}
		if (pauseWhileUsing.get() && (player.isUsingItem() || AutoEat.busy())) {
			return false;
		}
		return !pauseWhileMoving.get() || !MoveUtil.hasInput(player);
	}

	/**
	 * Does whatever this slot needs, if anything.
	 *
	 * @return whether a click was sent, which is what spends the tick
	 */
	private boolean act(LocalPlayer player, EquipmentSlot slot) {
		ItemStack worn = player.getItemBySlot(slot);
		EquipmentScorer.Preferences preferences = preferencesFor(slot);

		if (!worn.isEmpty() && !EquipmentScorer.removable(worn, preferences)) {
			return false; // bound to you; nothing to decide
		}
		if (slot == EquipmentSlot.CHEST && worn.is(Items.ELYTRA) && !elytraReplaceable(player)) {
			return false;
		}

		Candidate best = bestCandidate(player, slot, preferences);
		float wornScore = worn.isEmpty() ? -1.0f : EquipmentScorer.score(worn, slot, preferences);

		// Anti-break first: a helmet at two durability is worth replacing with something
		// materially worse, which the ordinary score comparison would never agree to.
		if (!worn.isEmpty() && !antiBreak.is("Ignore")
				&& EquipmentScorer.nearlyBroken(worn, minimumDurability.getInt())) {
			if (best != null) {
				return equip(player, slot, best.menuSlot());
			}
			return antiBreak.is("Unequip") && unequip(player, slot);
		}

		if (best == null) {
			return false;
		}
		if (!worn.isEmpty() && best.score() - wornScore < minimumGain.get()) {
			return false;
		}
		return equip(player, slot, best.menuSlot());
	}

	/** A candidate piece and where it is in the player's own menu. */
	private record Candidate(int menuSlot, float score) {
	}

	/**
	 * The best piece in the bag for this slot, or null.
	 *
	 * <p>Slots 9..44 only: 0-8 are the crafting area and the armour itself, and 45 is the
	 * offhand. Reading a candidate out of the armour slots would find the piece we are already
	 * wearing and cheerfully swap it with itself.
	 */
	private Candidate bestCandidate(LocalPlayer player, EquipmentSlot slot,
			EquipmentScorer.Preferences preferences) {
		AbstractContainerMenu menu = player.inventoryMenu;
		Candidate best = null;
		int last = Math.min(44, menu.slots.size() - 1);
		for (int index = 9; index <= last; index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (stack.isEmpty() || avoid.contains(stack.getItem())) {
				continue;
			}
			if (!fitsSlot(stack, slot) || !EquipmentScorer.eligible(stack, preferences)) {
				continue;
			}
			float score = EquipmentScorer.score(stack, slot, preferences);
			if (best == null || score > best.score()) {
				best = new Candidate(index, score);
			}
		}
		return best;
	}

	/**
	 * Whether this stack belongs in this slot.
	 *
	 * <p>Asked of the item's own equippable component rather than of its class, so anything the
	 * game will let you wear there is a candidate — including armour this client has never heard
	 * of, and excluding the pumpkin the class test would happily put on your head.
	 */
	private static boolean fitsSlot(ItemStack stack, EquipmentSlot slot) {
		var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot() == slot
				&& EquipmentScorer.score(stack, slot, EquipmentScorer.Preferences.DEFAULT) > 0.0f;
	}

	private EquipmentScorer.Preferences preferencesFor(EquipmentSlot slot) {
		return new EquipmentScorer.Preferences(profileFor(slot), preferMending.get(),
				blastLeggings.get(), minimumDurability.getInt(), bindingPolicy());
	}

	private EquipmentScorer.Profile profileFor(EquipmentSlot slot) {
		String name = switch (slot) {
			case HEAD -> helmetOverride.get() ? helmetProfile.get() : profile.get();
			case CHEST -> chestOverride.get() ? chestProfile.get() : profile.get();
			case LEGS -> legsOverride.get() ? legsProfile.get() : profile.get();
			default -> bootsOverride.get() ? bootsProfile.get() : profile.get();
		};
		return switch (name) {
			case "Protection" -> EquipmentScorer.Profile.PROTECTION;
			case "Blast" -> EquipmentScorer.Profile.BLAST;
			case "Fire" -> EquipmentScorer.Profile.FIRE;
			case "Projectile" -> EquipmentScorer.Profile.PROJECTILE;
			default -> EquipmentScorer.Profile.BALANCED;
		};
	}

	private EquipmentScorer.BindingPolicy bindingPolicy() {
		return switch (bindingCurse.get()) {
			case "Allow" -> EquipmentScorer.BindingPolicy.ALLOW;
			case "Ignore candidate" -> EquipmentScorer.BindingPolicy.IGNORE;
			default -> EquipmentScorer.BindingPolicy.KEEP_EQUIPPED;
		};
	}

	/**
	 * Whether a worn elytra may be taken off right now.
	 *
	 * <p>Grounded is the default and the reason this method exists: swapping wings for a
	 * chestplate mid-glide is a fall, and the player who set "minimum durability" was asking
	 * for their elytra to be looked after, not for it to be replaced at altitude.
	 *
	 * <p>ElytraSwap gets the first word regardless of the policy chosen here. It owns the chest
	 * slot while it is mid-swap or the player is gliding, and "Always armor" is a preference about
	 * <em>which</em> chestplate to wear rather than permission to take wings off at four hundred
	 * blocks.
	 */
	private boolean elytraReplaceable(LocalPlayer player) {
		if (UnluckyClient.INSTANCE.modules.get(ElytraSwap.class).guardsChestSlot()) {
			return false;
		}
		return switch (elytraPolicy.get()) {
			case "Always armor" -> true;
			case "Never replace" -> false;
			default -> player.onGround() && !player.isFallFlying();
		};
	}

	/** Swaps a bag slot into an armour slot, leaving whatever came off in the bag slot. */
	private boolean equip(LocalPlayer player, EquipmentSlot slot, int menuSlot) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_EQUIPMENT)) {
			return false;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		boolean done = InventoryActionCoordinator.pickupMove(this, menu, menuSlot, armourMenuSlot(slot));
		InventoryActionCoordinator.release(this);
		return done;
	}

	/** Takes a piece off into the first slot that will hold it. */
	private boolean unequip(LocalPlayer player, EquipmentSlot slot) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_EQUIPMENT)) {
			return false;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		boolean done = InventoryActionCoordinator.click(this, menu, armourMenuSlot(slot), 0,
				ContainerInput.QUICK_MOVE);
		InventoryActionCoordinator.release(this);
		return done;
	}

	private static int armourMenuSlot(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> ARMOUR_MENU_BASE;
			case CHEST -> ARMOUR_MENU_BASE + 1;
			case LEGS -> ARMOUR_MENU_BASE + 2;
			default -> ARMOUR_MENU_BASE + 3;
		};
	}
}
