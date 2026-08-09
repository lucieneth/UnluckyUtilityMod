package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.RotationManager;

/** Chains a falling mace hit into another wind-charge launch on landing. */
public class MaceCombo extends Module {
	public final NumberSetting attackRange = add(new NumberSetting("Attack range",
			"Maximum distance for an automatic follow-up hit", 4.5, 2, 8, 0.5));
	public final NumberSetting trackingRange = add(new NumberSetting("Tracking range",
			"How far the original target can move before the combo ends", 12, 5, 20, 1));
	public final NumberSetting timeout = add(new NumberSetting("Timeout",
			"Maximum combo duration in seconds", 30, 3, 60, 1));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Silently face the target before an automatic hit", true));
	public final BooleanSetting lockMace = add(new BooleanSetting("Lock first mace",
			"Return to the mace slot that started the combo", false));
	public final BooleanSetting waitForCharge = add(new BooleanSetting("Wait for attack charge",
			"Wait for the attack indicator unless landing is very close", true));
	public final NumberSetting closeRange = add(new NumberSetting("Close range",
			"Ignore the attack indicator inside this distance", 2.5, 1, 5, 0.25), waitForCharge::get);
	public final NumberSetting windPitch = add(new NumberSetting("Wind charge pitch",
			"Downward angle used for the next launch", 85, 70, 90, 1));
	public final NumberSetting jumpDelay = add(new NumberSetting("Jump delay",
			"Ticks after the charge throw before adding jump velocity", 2, 0, 10, 1));
	public final BooleanSetting feedback = add(new BooleanSetting("Chat feedback",
			"Report combo starts, endings, and hit count", true));

	private LivingEntity target;
	private int comboTicks;
	private int attackCooldown;
	private int windJumpTicks = -1;
	private int switchBackTicks = -1;
	private int returnSlot = -1;
	private int firstMaceSlot = -1;
	private int hits;
	private boolean launched;
	private boolean awaitingLanding;
	private boolean lastGrounded;
	private static boolean usingWindCharge;

	public MaceCombo() {
		super("MaceCombo", "Chains mace smashes by relaunching with wind charges", Category.COMBAT);
	}

	public static boolean isUsingWindCharge() {
		return usingWindCharge;
	}

	/** Observes attacks at the shared game-mode hook; recursive combo hits are ignored. */
	public void onAttack(Entity attacked) {
		LocalPlayer player = mc().player;
		if (target != null || player == null || !player.getMainHandItem().is(Items.MACE)
				|| player.fallDistance < 1.5f || !(attacked instanceof LivingEntity living)) {
			return;
		}
		target = living;
		firstMaceSlot = player.getInventory().getSelectedSlot();
		comboTicks = 0;
		hits = 1;
		launched = true;
		awaitingLanding = true;
		lastGrounded = player.onGround();
		if (feedback.get()) {
			ChatUtil.info("§aMace combo started on §f" + living.getName().getString());
		}
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			reset();
			return;
		}
		tickWindTimers(player);
		if (target == null) {
			return;
		}
		if (++comboTicks > timeout.getInt() * 20) {
			end("timed out");
			return;
		}
		if (!target.isAlive() || target.isRemoved()) {
			end("target lost");
			return;
		}
		double horizontal = Math.hypot(player.getX() - target.getX(), player.getZ() - target.getZ());
		if (horizontal > trackingRange.get()) {
			end("target too far away");
			return;
		}
		if (attackCooldown > 0) {
			attackCooldown--;
		}
		if (player.getDeltaMovement().y > 0.45 || player.fallDistance > 3.0f) {
			launched = true;
		}

		double distance = player.distanceTo(target);
		boolean charged = !waitForCharge.get() || distance <= closeRange.get()
				|| player.getAttackStrengthScale(0.0f) >= 0.99f;
		if (!player.onGround() && player.fallDistance >= 1.5f && launched
				&& attackCooldown == 0 && distance <= attackRange.get() && charged) {
			ensureMace(player);
			if (player.getMainHandItem().is(Items.MACE)) {
				if (rotate.get()) {
					RotationManager.lookAt(target.getBoundingBox().getCenter());
				}
				mc().gameMode.attack(player, target);
				player.swing(InteractionHand.MAIN_HAND);
				hits++;
				attackCooldown = 20;
				launched = false;
				awaitingLanding = true;
			}
		}

		boolean grounded = player.onGround();
		if (awaitingLanding && grounded && !lastGrounded) {
			if (useWindCharge(player)) {
				awaitingLanding = false;
				attackCooldown = 10;
			} else {
				end("no wind charge in the hotbar or offhand");
				return;
			}
		}
		lastGrounded = grounded;
	}

	private void tickWindTimers(LocalPlayer player) {
		if (windJumpTicks > 0 && --windJumpTicks == 0) {
			Vec3 velocity = player.getDeltaMovement();
			player.setDeltaMovement(velocity.x, Math.max(0.6, velocity.y), velocity.z);
			windJumpTicks = -1;
		}
		if (switchBackTicks > 0 && --switchBackTicks == 0) {
			if (returnSlot >= 0) {
				player.getInventory().setSelectedSlot(returnSlot);
			}
			returnSlot = -1;
			switchBackTicks = -1;
			usingWindCharge = false;
		}
	}

	private boolean useWindCharge(LocalPlayer player) {
		InteractionHand hand;
		if (player.getOffhandItem().is(Items.WIND_CHARGE)) {
			hand = InteractionHand.OFF_HAND;
		} else {
			int slot = findHotbar(player, Items.WIND_CHARGE);
			if (slot < 0) {
				return false;
			}
			returnSlot = player.getInventory().getSelectedSlot();
			player.getInventory().setSelectedSlot(slot);
			hand = InteractionHand.MAIN_HAND;
		}
		usingWindCharge = true;
		float oldPitch = player.getXRot();
		player.setXRot(windPitch.getFloat());
		mc().gameMode.useItem(player, hand);
		player.swing(hand);
		player.setXRot(oldPitch);
		windJumpTicks = jumpDelay.getInt();
		if (windJumpTicks == 0) {
			Vec3 velocity = player.getDeltaMovement();
			player.setDeltaMovement(velocity.x, Math.max(0.6, velocity.y), velocity.z);
			windJumpTicks = -1;
		}
		switchBackTicks = 5;
		return true;
	}

	private void ensureMace(LocalPlayer player) {
		if (player.getMainHandItem().is(Items.MACE)) {
			return;
		}
		if (lockMace.get() && firstMaceSlot >= 0 && player.getInventory().getItem(firstMaceSlot).is(Items.MACE)) {
			player.getInventory().setSelectedSlot(firstMaceSlot);
			return;
		}
		int slot = findHotbar(player, Items.MACE);
		if (slot >= 0) {
			player.getInventory().setSelectedSlot(slot);
		}
	}

	private static int findHotbar(LocalPlayer player, net.minecraft.world.item.Item item) {
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getItem(slot).is(item)) {
				return slot;
			}
		}
		return -1;
	}

	private void end(String reason) {
		if (feedback.get()) {
			ChatUtil.info("§7Mace combo ended: " + reason + " (§f" + hits + "§7 hits)");
		}
		reset();
	}

	private void reset() {
		target = null;
		comboTicks = 0;
		attackCooldown = 0;
		windJumpTicks = -1;
		switchBackTicks = -1;
		returnSlot = -1;
		firstMaceSlot = -1;
		hits = 0;
		launched = false;
		awaitingLanding = false;
		lastGrounded = false;
		usingWindCharge = false;
	}

	@Override
	protected void onDisable() {
		LocalPlayer player = mc().player;
		if (player != null && returnSlot >= 0) {
			player.getInventory().setSelectedSlot(returnSlot);
		}
		reset();
	}
}
