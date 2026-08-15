package unlucky.utility.client.module.modules.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.EntityListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.RotationManager;

/**
 * Spends name tags on the entity types you picked.
 *
 * <p>The entity list is empty by default and that is deliberate: a name tag is
 * consumed whether or not you meant it, and a module that starts out willing to
 * tag anything within reach will empty a stack into a cow field before you have
 * finished reading its settings.
 *
 * <p>"Rename tagged" compares the existing name to the tag's own — an entity
 * already carrying the name you are about to apply is skipped either way, so a
 * dropped tag never loops.
 *
 * <p>Reference: Meteor's AutoNametag.
 */
public class AutoNametag extends Module {
	/** Ticks before a tagged entity is eligible again, matching Meteor's cooldown. */
	private static final int COOLDOWN = 20;

	public final EntityListSetting entities = add(new EntityListSetting("Entities",
			"Entity types to name — nothing is tagged until you pick some"));
	public final NumberSetting range = add(new NumberSetting("Range",
			"Interaction range", 4.5, 1, 6, 0.1));
	public final ModeSetting priority = add(new ModeSetting("Priority",
			"Which candidate to tag first", "Closest", "Closest", "Furthest"));
	public final BooleanSetting renameTagged = add(new BooleanSetting("Rename tagged",
			"Also rename entities that already have a name", true));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select the name tag from the hotbar", true));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Face the entity before interacting", true));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between interactions", 2, 0, 20, 1));

	private final Map<UUID, Integer> interacted = new HashMap<>();
	private int tick;
	private int cooldown;

	public AutoNametag() {
		super("AutoNametag", "Uses name tags on chosen entities", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		interacted.clear();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().gameMode == null || cooldown-- > 0) {
			return;
		}
		tick++;
		InteractionHand hand = heldTagHand();
		int slot = hand == null && autoSwitch.get() ? tagSlot() : -1;
		if (hand == null && slot < 0) {
			return; // no name tag to spend; nothing to complain about every tick
		}

		Entity target = select();
		if (target == null) {
			return;
		}
		if (rotate.get() && !RotationManager.face(target.getBoundingBox().getCenter(), 45,
				RotationManager.PRIORITY_FUNCTIONAL)) {
			return;
		}
		if (slot >= 0) {
			if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_FARMING)
					|| !InventoryActionCoordinator.owns(this)
					|| !InventoryActionCoordinator.selectHotbar(this, slot)) {
				return;
			}
			hand = InteractionHand.MAIN_HAND;
		}
		InteractionResult result = mc().gameMode.interact(mc().player, target,
				new EntityHitResult(target), hand);
		if (result.consumesAction()) {
			mc().player.swing(hand);
		}
		interacted.put(target.getUUID(), tick);
		cooldown = delay.getInt();
	}

	private Entity select() {
		Entity best = null;
		double bestDistance = priority.is("Furthest") ? -1.0 : Double.POSITIVE_INFINITY;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!valid(entity)) {
				continue;
			}
			double distance = mc().player.distanceToSqr(entity);
			if (priority.is("Furthest") ? distance > bestDistance : distance < bestDistance) {
				best = entity;
				bestDistance = distance;
			}
		}
		return best;
	}

	private boolean valid(Entity entity) {
		if (entity == mc().player || !entity.isAlive() || !entities.allows(entity.getType())
				|| mc().player.distanceToSqr(entity) > range.get() * range.get()) {
			return false;
		}
		if (entity.hasCustomName() && (!renameTagged.get() || sameName(entity))) {
			return false;
		}
		Integer last = interacted.get(entity.getUUID());
		return last == null || tick - last >= COOLDOWN;
	}

	/** An entity already wearing this tag's name would consume a tag for nothing. */
	private boolean sameName(Entity entity) {
		ItemStack tag = tagStack();
		return tag != null && entity.getCustomName() != null
				&& entity.getCustomName().getString().equals(tag.getHoverName().getString());
	}

	private ItemStack tagStack() {
		InteractionHand hand = heldTagHand();
		if (hand != null) {
			return hand == InteractionHand.MAIN_HAND
					? mc().player.getMainHandItem() : mc().player.getOffhandItem();
		}
		int slot = tagSlot();
		return slot < 0 ? null : mc().player.getInventory().getItem(slot);
	}

	private InteractionHand heldTagHand() {
		if (mc().player.getMainHandItem().is(Items.NAME_TAG)) {
			return InteractionHand.MAIN_HAND;
		}
		return mc().player.getOffhandItem().is(Items.NAME_TAG) ? InteractionHand.OFF_HAND : null;
	}

	private int tagSlot() {
		Inventory inventory = mc().player.getInventory();
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
			if (inventory.getItem(i).is(Items.NAME_TAG)) {
				return i;
			}
		}
		return -1;
	}
}
