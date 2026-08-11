package unlucky.utility.client.module.modules.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;

/**
 * Firework elytra flight made easy: boost on demand, or automatically
 * whenever you slow down. Inspired by Stardust's RocketMan.
 */
public class RocketMan extends Module {
	public final KeybindSetting boostKey = add(new KeybindSetting("Boost key", "Hold to fire rockets", GLFW.GLFW_KEY_SPACE));
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between rockets", 20, 5, 100, 1));
	public final BooleanSetting autoFire = add(new BooleanSetting("Auto fire", "Fire when you slow down", false));
	public final NumberSetting minSpeed = add(new NumberSetting("Min speed", "Auto fire below this speed", 0.8, 0.2, 1.6, 0.1));

	private int cooldown;
	private boolean warnedNoRockets;
	public final NumberSetting reserve = add(new NumberSetting("Rocket reserve", "Never use the last this many hotbar rockets", 0, 0, 64, 1));
	public final BooleanSetting descendingOnly = add(new BooleanSetting("Only while descending", "Restrict automatic rockets to a descent", true), autoFire::get);

	public RocketMan() {
		super("RocketMan", "Easy firework elytra flight", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		cooldown = 0;
		warnedNoRockets = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null || !mc().player.isFallFlying()) {
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		boolean keyHeld = boostKey.isBound() && mc().gui.screen() == null
				&& InputConstants.isKeyDown(mc().getWindow(), boostKey.get());
		boolean tooSlow = autoFire.get() && mc().player.getDeltaMovement().horizontalDistance() < minSpeed.get()
				&& (!descendingOnly.get() || mc().player.getDeltaMovement().y < 0.0);
		if (!keyHeld && !tooSlow) {
			return;
		}

		int slot = InteractUtil.findHotbarItem(Items.FIREWORK_ROCKET);
		if (slot < 0 || rocketCount() <= reserve.get()) {
			if (!warnedNoRockets) {
				warnedNoRockets = true;
				ChatUtil.info("§cRocketMan is out of rockets!");
			}
			return;
		}
		warnedNoRockets = false;
		InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
		cooldown = (int) Math.round(delay.get());
	}

	private int rocketCount() {
		int count = 0;
		for (int i = 0; i < 9; i++) {
			var stack = mc().player.getInventory().getItem(i);
			if (stack.is(Items.FIREWORK_ROCKET)) count += stack.getCount();
		}
		return count;
	}
}
