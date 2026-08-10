package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;

/** Sets the horizontal speed of the living entity ridden by the local player. */
public class EntitySpeed extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed",
			"Mounted speed in blocks per tick", 0.5, 0.05, 3.0, 0.05));
	public final BooleanSetting onlyGround = add(new BooleanSetting("Only on ground",
			"Leave airborne movement to vanilla", false));
	public final BooleanSetting inFluids = add(new BooleanSetting("In fluids",
			"Also control speed in water and lava", true));

	public EntitySpeed() {
		super("EntitySpeed", "Control the speed of ridden living entities", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Called at Entity.move's boundary, leaving vanilla collision resolution intact. */
	public Vec3 movement(LivingEntity entity, Vec3 vanilla) {
		LocalPlayer player = mc().player;
		if (!isEnabled() || player == null || player.getVehicle() != entity) {
			return vanilla;
		}
		if (onlyGround.get() && !entity.onGround()) {
			return vanilla;
		}
		if (!inFluids.get() && (entity.isInWater() || entity.isInLava())) {
			return vanilla;
		}

		Vec3 direction = MoveUtil.inputDirection(player);
		Vec3 movement = new Vec3(direction.x * speed.get(), vanilla.y, direction.z * speed.get());
		entity.setDeltaMovement(movement);
		return movement;
	}
}
