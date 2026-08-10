package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MaceKillPackets;

/** Amplifies a real falling mace smash without creating a smash from the ground. */
public class LegitMaceKill extends Module {
	public final NumberSetting multiplier = add(new NumberSetting("Fall multiplier",
			"Multiplies the real fall distance sent for the mace hit", 1.5, 1, 50, 0.5));
	public final NumberSetting minimumFall = add(new NumberSetting("Minimum fall",
			"Real fall distance required before amplification", 2, 1.5, 20, 0.5));
	public final NumberSetting heightCap = add(new NumberSetting("Height cap",
			"Maximum amplified server-side fall", 170, 8, 170, 1));
	public final BooleanSetting preventFallDamage = add(new BooleanSetting("Prevent fall damage",
			"Restore grounded state and clear the client fall after the hit", true));
	public final BooleanSetting skipBlocking = add(new BooleanSetting("Skip blocked targets",
			"Do not spoof when the target is blocking or invulnerable", true));

	private Vec3 restorePos;

	public LegitMaceKill() {
		super("LegitMaceKill", "Amplifies mace damage only while you are genuinely falling", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	public void beforeAttack(Entity entity) {
		LocalPlayer player = mc().player;
		if (player == null || !player.getMainHandItem().is(Items.MACE)
				|| !(entity instanceof LivingEntity target) || player.fallDistance < minimumFall.getFloat()) {
			return;
		}
		if (skipBlocking.get() && (target.isBlocking() || target.isInvulnerable())) {
			return;
		}
		double height = Math.min(heightCap.get(), player.fallDistance * multiplier.get());
		if (height <= player.fallDistance || !MaceKillPackets.hasRoom(player, height)) {
			return;
		}
		restorePos = MaceKillPackets.prime(player, height);
	}

	public void afterAttack() {
		if (restorePos != null && mc().player != null) {
			MaceKillPackets.restore(mc().player, restorePos, preventFallDamage.get());
		}
		restorePos = null;
	}

	@Override
	protected void onDisable() {
		afterAttack();
	}
}
