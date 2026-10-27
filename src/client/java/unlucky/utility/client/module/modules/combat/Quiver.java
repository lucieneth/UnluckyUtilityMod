package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InputActionCoordinator;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.RotationManager;

/**
 * Shoots tipped arrows straight up so they come down on you — Strength and Speed in a fight
 * without drinking anything.
 *
 * <p>The arrow a bow fires is whichever one {@code Player.getProjectile} finds first: a hand,
 * then inventory index 0 upwards. So instead of tracking which arrow will go, the wanted one is
 * moved into that first position, and vanilla picks it by itself.
 *
 * <p>The draw is a held use key through {@link InputActionCoordinator}, not a forged packet, so
 * vanilla starts and releases the bow exactly as a right-click would. A short draw is the point:
 * a full-power arrow goes up for three seconds, a five-tick one is back in about one. The
 * server fires on release from the rotation it last received, which is why the pitch is held
 * up for every tick of the draw rather than only the last.
 *
 * <p>One shot per effect per enable, then it switches off. A missed arrow is not retried — it
 * usually means a ceiling or a lag spike, and looping would empty the quiver into either.
 */
public class Quiver extends Module {
	public final BooleanSetting strength = add(new BooleanSetting("Strength",
			"Shoot a Strength arrow when you do not have Strength", true));
	public final BooleanSetting speed = add(new BooleanSetting("Speed",
			"Shoot a Speed arrow when you do not have Speed", true));
	public final NumberSetting charge = add(new NumberSetting("Charge",
			"Ticks to draw the bow — shorter comes down sooner", 5, 3, 20, 1));
	public final NumberSetting landDelay = add(new NumberSetting("Land delay",
			"Ticks to wait for an arrow before the next one", 25, 5, 80, 1));
	public final BooleanSetting checkCeiling = add(new BooleanSetting("Check ceiling",
			"Do not shoot with a block above your head", true));

	private static final int CEILING_HEIGHT = 4;
	private static final int START_TIMEOUT = 5;

	private final Set<Holder<MobEffect>> shot = new HashSet<>();
	private Holder<MobEffect> shooting;
	private int heldTicks;
	private boolean drawing;
	private int waitTicks;

	public Quiver() {
		super("Quiver", "Shoots tipped arrows up at yourself", Category.COMBAT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		shot.clear();
		shooting = null;
		heldTicks = 0;
		waitTicks = 0;
	}

	@Override
	protected void onDisable() {
		InputActionCoordinator.release(this);
		InventoryActionCoordinator.release(this);
		shooting = null;
	}

	@Override
	protected void onPanic() {
		shooting = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			return;
		}
		if (shooting != null) {
			draw(player);
			return;
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		if (mc().gui.screen() != null || player.isUsingItem()) {
			return;
		}

		Inventory inventory = player.getInventory();
		int bow = bowSlot(inventory);
		Holder<MobEffect> effect = nextEffect(player, inventory);
		if (bow < 0 || effect == null) {
			setEnabled(false);
			return;
		}
		if (checkCeiling.get() && ceiling(player)) {
			return;
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)) {
			return;
		}
		if (!loadFirst(player, inventory.getItem(bow), effect)
				|| !InventoryActionCoordinator.selectHotbar(this, bow)) {
			// the reorder takes effect locally at once, but give it a tick before trusting it
			return;
		}
		shooting = effect;
		heldTicks = 0;
		drawing = false;
		draw(player);
	}

	/** Whether a shot is under way, from the arrow reorder to the release. BowAimbot leaves it alone. */
	public boolean isDrawing() {
		return isEnabled() && shooting != null;
	}

	/** One tick of the draw: aim up, keep the key down, let go once it is charged. */
	private void draw(LocalPlayer player) {
		RotationManager.rotate(player.getYRot(), -90.0f, RotationManager.PRIORITY_FUNCTIONAL);
		if (player.isUsingItem()) {
			drawing = true;
		} else if (drawing) {
			finishShot(false); // released: the arrow is away
			return;
		} else if (heldTicks > START_TIMEOUT) {
			finishShot(true); // the bow never came up
			return;
		}
		heldTicks++;
		if (player.isUsingItem() && player.getTicksUsingItem() >= charge.getInt()) {
			// vanilla sees the key up on its next keybind pass and releases the bow there
			InputActionCoordinator.release(this);
			return;
		}
		InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_SURVIVAL,
				InputActionCoordinator.Key.USE);
	}

	private void finishShot(boolean failed) {
		InputActionCoordinator.release(this);
		InventoryActionCoordinator.release(this);
		shot.add(shooting);
		shooting = null;
		waitTicks = failed ? 0 : landDelay.getInt();
	}

	/** The first wanted effect the player lacks, has an arrow for, and has not shot yet. */
	private Holder<MobEffect> nextEffect(LocalPlayer player, Inventory inventory) {
		List<Holder<MobEffect>> wanted = new ArrayList<>(2);
		if (strength.get()) {
			wanted.add(MobEffects.STRENGTH);
		}
		if (speed.get()) {
			wanted.add(MobEffects.SPEED);
		}
		for (Holder<MobEffect> effect : wanted) {
			if (!shot.contains(effect) && !player.hasEffect(effect) && arrowIndex(inventory, effect) >= 0) {
				return effect;
			}
		}
		return null;
	}

	/**
	 * Puts an arrow carrying {@code effect} where the bow will look first.
	 *
	 * @return whether it is already there
	 */
	private boolean loadFirst(LocalPlayer player, ItemStack bow, Holder<MobEffect> effect) {
		ItemStack next = player.getProjectile(bow);
		if (carries(next, effect)) {
			return true;
		}
		Inventory inventory = player.getInventory();
		int wanted = arrowIndex(inventory, effect);
		int current = indexOf(inventory, next);
		if (wanted < 0 || current < 0) {
			return false;
		}
		InventoryActionCoordinator.pickupMove(this, InventoryActionCoordinator.playerMenu(),
				menuSlot(wanted), menuSlot(current));
		return false;
	}

	private static int arrowIndex(Inventory inventory, Holder<MobEffect> effect) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (carries(inventory.getItem(i), effect)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean carries(ItemStack stack, Holder<MobEffect> effect) {
		if (!stack.is(Items.TIPPED_ARROW)) {
			return false;
		}
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) {
			return false;
		}
		for (MobEffectInstance instance : contents.getAllEffects()) {
			if (instance.getEffect().equals(effect)) {
				return true;
			}
		}
		return false;
	}

	private static int indexOf(Inventory inventory, ItemStack stack) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i) == stack) {
				return i;
			}
		}
		return -1;
	}

	/** Inventory index to inventory-menu slot: hotbar 36-44, main 9-35, offhand 45. */
	private static int menuSlot(int index) {
		if (index < Inventory.SELECTION_SIZE) {
			return InventoryActionCoordinator.hotbarMenuSlot(index);
		}
		return index == Inventory.SLOT_OFFHAND ? 45 : index;
	}

	private static int bowSlot(Inventory inventory) {
		if (inventory.getItem(inventory.getSelectedSlot()).getItem() instanceof BowItem) {
			return inventory.getSelectedSlot();
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (inventory.getItem(slot).getItem() instanceof BowItem) {
				return slot;
			}
		}
		return -1;
	}

	private boolean ceiling(LocalPlayer player) {
		BlockPos head = BlockPos.containing(player.getEyePosition());
		for (int dy = 1; dy <= CEILING_HEIGHT; dy++) {
			BlockPos pos = head.above(dy);
			if (!mc().level.getBlockState(pos).getCollisionShape(mc().level, pos).isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
