package unlucky.utility.client.module.modules.combat;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;

/**
 * Attacks the best target in range using silent rotations: the server sees
 * you face the target (body turns in third person) while your camera stays
 * free in first person.
 */
public class Aura extends Module {
	// per-mob whitelists, opened by right-clicking the group toggles in the GUI
	public final unlucky.utility.client.settings.EntityListSetting hostileMobs =
			new unlucky.utility.client.settings.EntityListSetting("Hostile mobs", "Which hostile mobs to target");
	public final unlucky.utility.client.settings.EntityListSetting passiveMobs =
			new unlucky.utility.client.settings.EntityListSetting("Passive mobs", "Which passive mobs to target");

	public final NumberSetting range = add(new NumberSetting("Range", "Attack reach in blocks", 4.2, 2.0, 6.0, 0.1));
	public final BooleanSetting players = add(new BooleanSetting("Players", "Target players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles", "Target hostile mobs — right-click to pick which", true)
			.withMobList(hostileMobs, true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives", "Target passive mobs — right-click to pick which", false)
			.withMobList(passiveMobs, false));
	public final ModeSetting speed = add(new ModeSetting("Speed", "Attributes = full weapon charge, CPS = flat rate", "Attributes", "Attributes", "CPS"));
	public final NumberSetting cps = add(new NumberSetting("CPS", "Clicks per second", 8, 1, 20, 1),
			() -> speed.is("CPS"));
	public final ModeSetting priority = add(new ModeSetting("Priority", "Which target to hit first", "Closest", "Closest", "Health"));
	public final ModeSetting targetPoint = add(new ModeSetting("Target point", "Body part to aim at", "Body", "Head", "Body", "Feet"));
	public final BooleanSetting showHitbox = add(new BooleanSetting("Show hitbox", "Outline the body part being targeted", false));
	public final BooleanSetting hitboxWalls = add(new BooleanSetting("Through walls", "Show the hitbox through blocks", true));
	public final BooleanSetting silent = add(new BooleanSetting("Silent look", "Face the target server-side only", true));
	public final ModeSetting autoSwitch = add(new ModeSetting("Auto switch",
			"Hold a weapon of this kind before hitting", "Off", "Off", "Sword", "Axe"));
	public final BooleanSetting switchBack = add(new BooleanSetting("Switch back",
			"Go back to the slot you were holding once there's nothing left to hit", true),
			() -> !autoSwitch.is("Off"));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUIs", "Don't attack with a screen open", true));

	/** The entity Aura is currently locked on, for TargetHUD. Null when idle. */
	public static Entity currentTarget;

	private int ticksSinceAttack;
	/** Slot the player was holding before Auto switch moved them off it, -1 = we haven't. */
	private int returnSlot = -1;
	/** The slot we selected, so we can tell our own choice from one the player made since. */
	private int switchedTo = -1;

	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public Aura() {
		super("Aura", "Attacks nearby targets", Category.COMBAT);
		// registered for config persistence; hidden from the GUI (no component),
		// reachable through the right-click picker instead
		add(hostileMobs);
		add(passiveMobs);
	}

	@Override
	protected void onDisable() {
		currentTarget = null;
		restoreSlot();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			currentTarget = null;
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return;
		}
		if (AutoEat.pauses(pauseOnEat)) {
			currentTarget = null; // drop the lock too, or the pose keeps facing a target we are not hitting
			restoreSlot(); // and give the hotbar back — AutoEat is about to pick a slot of its own
			return;
		}
		ticksSinceAttack++;

		Entity target = pickTarget();
		currentTarget = target;
		if (target == null) {
			restoreSlot();
			return;
		}
		Vec3 aim = aimPoint(target);
		if (showHitbox.get()) {
			Render3D.box(partBox(target.getBoundingBox(), aim), Theme.accent1, 1.5f,
					ColorUtil.withAlpha(Theme.accent1, 45), hitboxWalls.get());
		}
		if (silent.get()) {
			RotationManager.lookAt(aim);
		}
		// A slot change only reaches the server on the next tick's sync, so a hit
		// sent in the same tick would still land with the old item. Skip this
		// tick's attack whenever we just moved — costs one tick, once per fight.
		if (equipWeapon()) {
			return;
		}
		if (CombatUtil.ready(speed.is("Attributes"), cps.get(), ticksSinceAttack)) {
			CombatUtil.attack(target);
			ticksSinceAttack = 0;
		}
	}

	/**
	 * Puts the picked weapon kind in hand, returning whether the selection moved
	 * this tick. Only the hotbar is considered — pulling from the inventory would
	 * mean container clicks mid-fight, which is a different (and far louder)
	 * feature. An equal-or-better weapon already in hand is left alone, so
	 * holding your good axe with a worse one in the bar doesn't cause a swap.
	 */
	private boolean equipWeapon() {
		if (autoSwitch.is("Off")) {
			return false;
		}
		var inventory = mc().player.getInventory();
		TagKey<Item> kind = autoSwitch.is("Axe") ? ItemTags.AXES : ItemTags.SWORDS;
		int selected = inventory.getSelectedSlot();
		ItemStack held = inventory.getItem(selected);

		int best = -1;
		double bestDamage = held.is(kind) ? meleeDamage(held) : -1.0;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(kind)) {
				continue;
			}
			double damage = meleeDamage(stack);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = slot;
			}
		}
		if (best < 0 || best == selected) {
			return false;
		}
		if (returnSlot < 0) {
			returnSlot = selected;
		}
		inventory.setSelectedSlot(best);
		switchedTo = best;
		return true;
	}

	/** Hands the hotbar back, unless the player has since picked a slot themselves. */
	private void restoreSlot() {
		if (returnSlot < 0) {
			return;
		}
		if (switchBack.get() && mc().player != null
				&& mc().player.getInventory().getSelectedSlot() == switchedTo) {
			mc().player.getInventory().setSelectedSlot(returnSlot);
		}
		returnSlot = -1;
		switchedTo = -1;
	}

	/** Base main-hand attack damage of a stack, summed over its own modifiers. */
	private static double meleeDamage(ItemStack stack) {
		double[] total = { 0.0 };
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) {
				total[0] += modifier.amount();
			}
		});
		return total[0];
	}

	/** Where on the target we aim: eye height, box center, or just above the feet. */
	private Vec3 aimPoint(Entity target) {
		AABB box = target.getBoundingBox();
		Vec3 center = box.getCenter();
		return switch (targetPoint.get()) {
			case "Head" -> new Vec3(center.x, Math.min(target.getEyeY(), box.maxY - 0.05), center.z);
			case "Feet" -> new Vec3(center.x, box.minY + box.getYsize() * 0.1, center.z);
			default -> center;
		};
	}

	/** A horizontal slice of the hitbox (1/3 of its height) around the aim point. */
	private static AABB partBox(AABB box, Vec3 aim) {
		double half = box.getYsize() / 6.0;
		double y0 = Math.max(aim.y - half, box.minY);
		double y1 = Math.min(aim.y + half, box.maxY);
		return new AABB(box.minX, y0, box.minZ, box.maxX, y1, box.maxZ).inflate(0.03);
	}

	private Entity pickTarget() {
		Entity best = null;
		double bestScore = Double.MAX_VALUE;
		double rangeSq = range.get() * range.get();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!CombatUtil.validTarget(entity, players.get(), hostiles.get(), passives.get(), hostileMobs, passiveMobs)) {
				continue;
			}
			double distSq = mc().player.distanceToSqr(entity);
			if (distSq > rangeSq) {
				continue;
			}
			double score = priority.is("Health") && entity instanceof LivingEntity living
					? living.getHealth()
					: distSq;
			if (score < bestScore) {
				bestScore = score;
				best = entity;
			}
		}
		return best;
	}
}
