package unlucky.utility.client.module.modules.combat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatUtil;

/**
 * Left-clicks for you: attacks whatever is under the crosshair (or swings at
 * air) at a flat CPS or in time with the weapon's attack-speed attribute.
 */
public class AutoClicker extends Module {
	public final ModeSetting speed = add(new ModeSetting("Speed", "Attributes = full weapon charge, CPS = flat rate", "CPS", "Attributes", "CPS"));
	public final NumberSetting cps = add(new NumberSetting("CPS", "Clicks per second in CPS mode", 10, 1, 20, 1));
	public final BooleanSetting holdToClick = add(new BooleanSetting("Hold to click", "Only click while the attack button is held", true));

	private int ticksSinceClick;

	public AutoClicker() {
		super("AutoClicker", "Clicks so you don't have to", Category.COMBAT);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().gui.screen() != null) {
			return;
		}
		if (holdToClick.get() && !mc().options.keyAttack.isDown()) {
			return;
		}
		ticksSinceClick++;
		if (!CombatUtil.ready(speed.is("Attributes"), cps.get(), ticksSinceClick)) {
			return;
		}
		ticksSinceClick = 0;
		if (mc().hitResult instanceof EntityHitResult hit && hit.getEntity().isAlive()) {
			CombatUtil.attack(hit.getEntity());
		} else {
			mc().player.swing(InteractionHand.MAIN_HAND);
		}
	}
}
