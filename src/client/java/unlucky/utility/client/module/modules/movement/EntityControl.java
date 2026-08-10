package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;

/** Lets the mounted player become a mob's controller without changing its item data. */
public class EntityControl extends Module {
	public final BooleanSetting maxJump = add(new BooleanSetting("Max jump",
			"Always charge a rideable mount's jump to maximum", true));
	public final BooleanSetting lockYaw = add(new BooleanSetting("Lock yaw",
			"Point the controlled entity where your camera is facing", true));

	public EntityControl() {
		super("EntityControl", "Steer ridden mobs without a saddle or steering item", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * Supplies the player only when vanilla found no controller. The UUID branch also
	 * keeps an integrated server in agreement without affecting other LAN players.
	 */
	public LivingEntity controller(Mob mob, LivingEntity vanilla) {
		if (vanilla != null || !isEnabled()) {
			return vanilla;
		}
		Entity firstPassenger = mob.getFirstPassenger();
		Minecraft minecraft = mc();
		LocalPlayer local = minecraft.player;
		if (local == null) {
			return null;
		}
		if (firstPassenger == local) {
			return local;
		}
		if (minecraft.hasSingleplayerServer() && firstPassenger instanceof ServerPlayer serverPlayer
				&& serverPlayer.getUUID().equals(local.getUUID())) {
			return serverPlayer;
		}
		return null;
	}

	/** True only after our own player is already riding this mob. */
	public boolean controls(Mob mob) {
		return controller(mob, null) != null;
	}

	/**
	 * Pigs and striders normally ignore WASD and only move forward for a steering
	 * item. Once controlled, give those two the same directional input as other mounts.
	 */
	public Vec3 riddenInput(LivingEntity entity, Player controller, Vec3 vanilla) {
		LocalPlayer local = mc().player;
		if (!isEnabled() || controller != local) {
			return vanilla;
		}
		if (lockYaw.get()) {
			entity.setYRot(local.getYRot());
			entity.setYHeadRot(local.getYRot());
			entity.yBodyRot = local.getYRot();
		}
		if (!(entity instanceof Pig || entity instanceof Strider)) {
			return vanilla;
		}
		Vec2 input = local.input.getMoveVector();
		return new Vec3(input.x, 0.0, input.y);
	}

	public boolean maximizesJump() {
		LocalPlayer local = mc().player;
		return isEnabled() && maxJump.get() && local != null && local.getVehicle() instanceof Mob mob && controls(mob);
	}
}
