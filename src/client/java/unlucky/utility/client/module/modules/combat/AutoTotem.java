package unlucky.utility.client.module.modules.combat;


import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.DamageForecast;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.OffhandManager;

/**
 * Keeps a totem in your offhand when something is about to kill you.
 *
 * <p>The module itself decides one thing — <em>do I want a totem right now</em> — and asks
 * {@link OffhandManager} for it at the top priority. It never clicks a slot. That is what
 * stops the oldest bug in this genre: AutoTotem and AutoReplenish each noticing the offhand
 * is "wrong" and each putting it right, forty times a second, so the slot is empty exactly
 * when the crystal lands.
 *
 * <p><b>Smart is not "low health".</b> Health is the condition that arrives too late — by the
 * time a crystal has taken you to six hearts the next one is already placed. The other four
 * tests are all forms of "the damage has not happened yet": a fall whose landing is already
 * determined, a crystal sitting in range with your name on it, a glide that ends at a wall,
 * and a drop with nothing under it at all.
 */
public class AutoTotem extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Smart weighs what is about to happen as well as what already has. Always holds one "
					+ "whenever you have one. Health only ignores everything but the number.",
			"Smart", "Smart", "Always", "Health only"));
	public final NumberSetting threshold = add(new NumberSetting("Health threshold",
			"Hold a totem at or below this much health, counting absorption", 10, 1, 36, 1),
			() -> !mode.is("Always"));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks of danger before reaching for one", 0, 0, 20, 1));
	public final BooleanSetting fallProtection = add(new BooleanSetting("Fall protection",
			"Count the fall you are already committed to", true), () -> mode.is("Smart"));
	public final BooleanSetting explosionProtection = add(new BooleanSetting("Explosion protection",
			"Count crystals and primed TNT in range, through the blocks between you", true),
			() -> mode.is("Smart"));
	public final BooleanSetting elytraProtection = add(new BooleanSetting("Elytra protection",
			"Hold one the whole time you are gliding", true), () -> mode.is("Smart"));
	public final BooleanSetting voidProtection = add(new BooleanSetting("Void protection",
			"Hold one when there is nothing at all below you", true), () -> mode.is("Smart"));
	public final BooleanSetting lowArmor = add(new BooleanSetting("Low armor protection",
			"Hold one while a piece of armor is missing or nearly gone", false),
			() -> mode.is("Smart"));
	public final NumberSetting reserve = add(new NumberSetting("Reserve count",
			"Totems to leave alone. 0 spends the last one.", 0, 0, 16, 1));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Put the offhand item back once the danger passes", true));
	public final NumberSetting swapBackDelay = add(new NumberSetting("Swap-back delay",
			"Ticks of calm before letting go of the totem", 2, 0, 40, 1));
	public final ModeSetting fallback = add(new ModeSetting("Preferred fallback",
			"What the offhand holds when you are not in danger", "Previous item",
			"Previous item", "Golden apple", "Shield", "None"));
	public final BooleanSetting warnLast = add(new BooleanSetting("Warn on last totem",
			"Toast when the one going into your hand is the last you have", true));

	/** Ticks danger has been continuously true, against {@link #delay}. */
	private int dangerTicks;
	/** Ticks of calm still being ridden out, against {@link #swapBackDelay}. */
	private int calmTicks;
	/** So the "last totem" toast fires on the way into danger, not once a tick while in it. */
	private boolean warned;

	public AutoTotem() {
		super("AutoTotem", "Keeps a totem in your offhand when something is about to kill you",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		dangerTicks = 0;
		calmTicks = 0;
		warned = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()) {
			dangerTicks = 0;
			calmTicks = 0;
			return;
		}

		boolean want = totemCount(player) > reserve.getInt() && inDanger(player);
		if (want) {
			dangerTicks++;
			calmTicks = 0;
		} else {
			dangerTicks = 0;
			// Ride out a moment of calm rather than handing the slot back the instant a
			// crystal is broken: the next one is usually already on its way.
			if (calmTicks < swapBackDelay.getInt()) {
				calmTicks++;
				want = true;
			}
		}

		if (want && dangerTicks >= delay.getInt()) {
			if (warnLast.get() && !warned && totemCount(player) == 1) {
				UnluckyClient.INSTANCE.notifications.add("AutoTotem", "Last totem",
						ItemUtil.icon(Items.TOTEM_OF_UNDYING));
				warned = true;
			}
			OffhandManager.request(this, OffhandManager.PRIORITY_TOTEM,
					stack -> stack.is(Items.TOTEM_OF_UNDYING), "Totem",
					swapBack.get() && fallback.is("Previous item"));
			return;
		}

		warned = false;
		requestFallback();
	}

	/**
	 * What the offhand should hold while nothing is trying to kill you.
	 *
	 * <p>"Previous item" is the absence of a request — {@link OffhandManager} restores what it
	 * displaced when nobody is asking, which is a better answer than any this module could
	 * reconstruct. The other two are ordinary requests at combat priority, so a totem still
	 * takes the slot off them the moment it is wanted.
	 */
	private void requestFallback() {
		if (fallback.is("Golden apple")) {
			OffhandManager.request(this, OffhandManager.PRIORITY_COMBAT,
					stack -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE),
					"Golden apple", swapBack.get());
		} else if (fallback.is("Shield")) {
			OffhandManager.request(this, OffhandManager.PRIORITY_COMBAT,
					stack -> stack.is(Items.SHIELD), "Shield", swapBack.get());
		}
	}

	private boolean inDanger(LocalPlayer player) {
		if (mode.is("Always")) {
			return true;
		}
		float health = ExplosionDamageUtil.effectiveHealth(player);
		float limit = threshold.getFloat();
		if (health <= limit) {
			return true;
		}
		if (mode.is("Health only")) {
			return false;
		}

		if (elytraProtection.get() && player.isFallFlying()) {
			return true;
		}
		if (lowArmor.get() && armorIsThin(player)) {
			return true;
		}

		// The three predictive tests, cheapest first. Each asks the same question: would the
		// damage that is already on its way put me under the threshold?
		if (voidProtection.get() && DamageForecast.fallingIntoVoid(player)) {
			return true;
		}
		if (fallProtection.get()
				&& health - DamageForecast.predictedFallDamage(player) <= limit) {
			return true;
		}
		return explosionProtection.get()
				&& health - DamageForecast.worstNearbyExplosion(player) <= limit;
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





	/** Any armour slot empty, or worn down past a tenth. */
	private static boolean armorIsThin(LocalPlayer player) {
		for (EquipmentSlot slot : new EquipmentSlot[] {
				EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				return true;
			}
			int max = stack.getMaxDamage();
			if (max > 0 && (max - stack.getDamageValue()) * 10 < max) {
				return true;
			}
		}
		return false;
	}
}
