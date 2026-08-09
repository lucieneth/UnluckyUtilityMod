package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.combat.MaceCombo;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/** Jumps shortly after a downward wind-charge throw. */
public class WindChargeJump extends Module {
	public final NumberSetting pitchThreshold = add(new NumberSetting("Pitch threshold",
			"Minimum downward look angle (90 is straight down)", 50, 0, 90, 1));
	public final BooleanSetting requireLookingDown = add(new BooleanSetting("Require looking down",
			"Only jump for charges thrown under you", true));
	public final BooleanSetting onlyOnGround = add(new BooleanSetting("Only on ground",
			"Do not trigger while already airborne", true));
	public final NumberSetting delay = add(new NumberSetting("Jump delay",
			"Ticks between throwing and jumping", 1, 0, 10, 1));

	private int ticks = -1;

	public WindChargeJump() {
		super("WindChargeJump", "Jumps when you throw a wind charge underneath yourself", Category.MOVEMENT);
	}

	/** Called immediately before vanilla sends a use-item packet. */
	public void onUseItem(InteractionHand hand) {
		LocalPlayer player = mc().player;
		if (player == null || MaceCombo.isUsingWindCharge()) {
			return;
		}
		if (!player.getItemInHand(hand).is(Items.WIND_CHARGE)
				|| player.getCooldowns().isOnCooldown(player.getItemInHand(hand))) {
			return;
		}
		if (onlyOnGround.get() && !player.onGround()) {
			return;
		}
		if (requireLookingDown.get() && player.getXRot() < pitchThreshold.getFloat()) {
			return;
		}
		ticks = delay.getInt();
		if (ticks == 0) {
			jump();
		}
	}

	@Override
	public void onTick() {
		if (ticks > 0 && --ticks == 0) {
			jump();
		}
	}

	private void jump() {
		ticks = -1;
		LocalPlayer player = mc().player;
		if (player != null && (!onlyOnGround.get() || player.onGround())) {
			player.jumpFromGround();
		}
	}

	@Override
	protected void onDisable() {
		ticks = -1;
	}
}
