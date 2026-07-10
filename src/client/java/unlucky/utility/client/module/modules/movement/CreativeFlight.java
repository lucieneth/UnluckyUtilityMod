package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.entity.player.Abilities;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

/** Classic creative-style flight: grants fly ability with a speed multiplier. */
public class CreativeFlight extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Flight speed multiplier", 1.0, 0.2, 5.0, 0.1));

	private boolean previousMayFly;

	public CreativeFlight() {
		super("CreativeFlight", "Fly like in creative mode", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		if (mc().player != null) {
			previousMayFly = mc().player.getAbilities().mayfly;
			mc().player.getAbilities().mayfly = true;
			mc().player.onUpdateAbilities();
		}
	}

	@Override
	public void onTick() {
		if (mc().player == null) {
			return;
		}
		Abilities abilities = mc().player.getAbilities();
		abilities.mayfly = true;
		abilities.setFlyingSpeed(0.05f * speed.getFloat());
	}

	@Override
	protected void onDisable() {
		if (mc().player == null) {
			return;
		}
		Abilities abilities = mc().player.getAbilities();
		abilities.setFlyingSpeed(0.05f);
		if (!mc().player.isCreative()) {
			abilities.mayfly = previousMayFly;
			abilities.flying = false;
			mc().player.onUpdateAbilities();
		}
	}
}
