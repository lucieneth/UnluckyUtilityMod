package unlucky.utility.client.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class MoveUtil {
	private MoveUtil() {
	}

	public static boolean hasInput(LocalPlayer player) {
		Vec2 move = player.input.getMoveVector();
		return move.x != 0 || move.y != 0;
	}

	/** Horizontal unit direction from the player's movement input and yaw. */
	public static Vec3 inputDirection(LocalPlayer player) {
		Vec2 move = player.input.getMoveVector();
		if (move.x == 0 && move.y == 0) {
			return Vec3.ZERO;
		}
		float yaw = (float) Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		// move.x is left-positive (vanilla leftImpulse); a south-facing player's left is east (+X)
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 dir = forward.scale(move.y).add(left.scale(move.x));
		return dir.lengthSqr() < 1.0e-6 ? Vec3.ZERO : dir.normalize();
	}

	public static double horizontalSpeed(LocalPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
	}
}
