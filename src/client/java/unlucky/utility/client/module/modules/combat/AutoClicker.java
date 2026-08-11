package unlucky.utility.client.module.modules.combat;

import net.minecraft.world.InteractionHand;
import unlucky.utility.client.util.CombatItemUtil;
import net.minecraft.world.phys.EntityHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
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
	public final NumberSetting cps = add(new NumberSetting("CPS", "Clicks per second", 10, 1, 20, 1),
			() -> speed.is("CPS"));
	public final NumberSetting minCps = add(new NumberSetting("Min CPS", "Lowest randomized CPS", 8, 1, 20, 1),
			() -> speed.is("CPS"));
	public final NumberSetting maxCps = add(new NumberSetting("Max CPS", "Highest randomized CPS", 12, 1, 20, 1),
			() -> speed.is("CPS"));
	public final BooleanSetting holdToClick = add(new BooleanSetting("Hold to click", "Only click while the attack button is held", true));
	public final ModeSetting onlyHolding = add(new ModeSetting("Only while holding", "Required held item",
			"Any", "Any", "Weapon", "Sword", "Axe", "Mace"));
	public final BooleanSetting onlyEntities = add(new BooleanSetting("Only entities", "Never swing at air", false));
	public final BooleanSetting allowBreaking = add(new BooleanSetting("Allow block breaking", "Allow normal left clicks on blocks", false));
	public final BooleanSetting respectCooldown = add(new BooleanSetting("Respect item cooldown", "Wait for the held item's cooldown", true));

	private int ticksSinceClick;
	private int nextClickTicks;

	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public AutoClicker() {
		super("AutoClicker", "Clicks so you don't have to", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().gui.screen() != null) {
			return;
		}
		if (holdToClick.get() && !mc().options.keyAttack.isDown()) {
			return;
		}
		if (AutoEat.pauses(pauseOnEat)) {
			return;
		}
		if (!holdingAllowed() || (respectCooldown.get() && mc().player.getCooldowns().isOnCooldown(mc().player.getMainHandItem()))) return;
		ticksSinceClick++;
		if (nextClickTicks == 0) nextClickTicks = randomClickTicks();
		if ((speed.is("Attributes") && !CombatUtil.ready(true, cps.get(), ticksSinceClick))
				|| (speed.is("CPS") && ticksSinceClick < nextClickTicks)) {
			return;
		}
		ticksSinceClick = 0;
		nextClickTicks = 0;
		if (mc().hitResult instanceof EntityHitResult hit && hit.getEntity().isAlive()) {
			CombatUtil.attack(hit.getEntity());
		} else {
			if (!onlyEntities.get() && (allowBreaking.get() || !(mc().hitResult instanceof net.minecraft.world.phys.BlockHitResult))) {
				mc().player.swing(InteractionHand.MAIN_HAND);
			}
		}
	}

	private boolean holdingAllowed() {
		var held = mc().player.getMainHandItem();
		return switch (onlyHolding.get()) {
			case "Weapon" -> CombatItemUtil.isMeleeWeapon(held);
			case "Sword" -> CombatItemUtil.isSword(held);
			case "Axe" -> CombatItemUtil.isAxe(held);
			case "Mace" -> CombatItemUtil.isMace(held);
			default -> true;
		};
	}

	private int randomClickTicks() {
		int min = Math.min(minCps.getInt(), maxCps.getInt());
		int max = Math.max(minCps.getInt(), maxCps.getInt());
		int cps = min + mc().player.getRandom().nextInt(max - min + 1);
		return Math.max(1, Math.round(20.0f / cps));
	}
}
