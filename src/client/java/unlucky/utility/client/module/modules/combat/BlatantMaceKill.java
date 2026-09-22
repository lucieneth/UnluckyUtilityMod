package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
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

	public BlatantMaceKill() {
		super("BlatantMaceKill", "Spoofs a lethal mace fall while you remain on the ground", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * MultiPlayerGameModeMixin, at attack HEAD. True means the attack is held: the climb
	 * is out, and {@link MaceKillPackets} lets the hit go once the fall is banked.
	 */
	public boolean beforeAttack(Entity entity) {
		LocalPlayer player = mc().player;
		if (player == null || !player.getMainHandItem().is(Items.MACE)
				|| !(entity instanceof LivingEntity target) || (onlyGrounded.get() && !player.onGround())) {
			return false;
		}
		if (skipBlocking.get() && (target.isBlocking() || target.isInvulnerable())) {
			return false;
		}
		double height = spoofHeight.get();
		if (!MaceKillPackets.hasRoom(player, height)) {
			return false;
		}
		return MaceKillPackets.begin(this, player, target, height, true);
	}

	@Override
	protected void onDisable() {
		MaceKillPackets.cancel(this);
	}
}
