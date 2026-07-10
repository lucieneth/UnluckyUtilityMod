package unlucky.utility.client.module.modules.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.util.InteractUtil;

/**
 * One key: jump, deploy the elytra, fire a rocket.
 * Inspired by Stardust's RocketJump.
 */
public class RocketJump extends Module {
	private enum Phase {
		IDLE, JUMPED, DEPLOYED
	}

	public final KeybindSetting jumpKey = add(new KeybindSetting("Jump key", "Press to rocket jump", GLFW.GLFW_KEY_G));

	private Phase phase = Phase.IDLE;
	private int ticksInPhase;
	private boolean wasDown;

	public RocketJump() {
		super("RocketJump", "Rocket-boosted jumps", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		phase = Phase.IDLE;
		wasDown = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null) {
			phase = Phase.IDLE;
			return;
		}

		boolean down = jumpKey.isBound() && mc().gui.screen() == null
				&& InputConstants.isKeyDown(mc().getWindow(), jumpKey.get());
		boolean pressed = down && !wasDown;
		wasDown = down;

		switch (phase) {
			case IDLE -> {
				if (pressed && mc().player.onGround() && hasElytra()
						&& InteractUtil.findHotbarItem(Items.FIREWORK_ROCKET) >= 0) {
					mc().player.jumpFromGround();
					phase = Phase.JUMPED;
					ticksInPhase = 0;
				}
			}
			case JUMPED -> {
				ticksInPhase++;
				if (!mc().player.onGround() && ticksInPhase >= 3 && mc().player.tryToStartFallFlying()) {
					phase = Phase.DEPLOYED;
					ticksInPhase = 0;
				} else if (ticksInPhase > 15 || mc().player.isFallFlying()) {
					phase = mc().player.isFallFlying() ? Phase.DEPLOYED : Phase.IDLE;
					ticksInPhase = 0;
				}
			}
			case DEPLOYED -> {
				int slot = InteractUtil.findHotbarItem(Items.FIREWORK_ROCKET);
				if (slot >= 0 && mc().player.isFallFlying()) {
					InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
				}
				phase = Phase.IDLE;
			}
		}
	}

	private boolean hasElytra() {
		return mc().player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
	}
}
