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

/** Creates a mace smash while the local player remains standing on the ground. */
public class BlatantMaceKill extends Module {
	public final NumberSetting spoofHeight = add(new NumberSetting("Spoof height",
			"Fall height banked immediately before the hit", 170, 8, 170, 1));
	public final BooleanSetting onlyGrounded = add(new BooleanSetting("Only grounded",
			"Only manufacture a smash while standing on the ground", true));
	public final BooleanSetting skipBlocking = add(new BooleanSetting("Skip blocked targets",
			"Do not spoof when the target is blocking or invulnerable", true));

	private Vec3 restorePos;

	public BlatantMaceKill() {
		super("BlatantMaceKill", "Spoofs a lethal mace fall while you remain on the ground", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	public void beforeAttack(Entity entity) {
		LocalPlayer player = mc().player;
		if (player == null || !player.getMainHandItem().is(Items.MACE)
				|| !(entity instanceof LivingEntity target) || (onlyGrounded.get() && !player.onGround())) {
			return;
		}
		if (skipBlocking.get() && (target.isBlocking() || target.isInvulnerable())) {
			return;
		}
		double height = spoofHeight.get();
		if (!MaceKillPackets.hasRoom(player, height)) {
			return;
		}
		restorePos = MaceKillPackets.prime(player, height);
	}

	public void afterAttack() {
		if (restorePos != null && mc().player != null) {
			MaceKillPackets.restore(mc().player, restorePos, true);
		}
		restorePos = null;
	}

	@Override
	protected void onDisable() {
		afterAttack();
	}
}
