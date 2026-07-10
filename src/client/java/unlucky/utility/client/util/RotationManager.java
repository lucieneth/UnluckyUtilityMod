package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Silent server-side rotations. Modules call rotate()/lookAt() during their
 * tick; the spoofed rotation is sent to the server while the camera stays free
 * (invisible in first person). In third person and freecam the body and head
 * visibly turn toward the spoofed direction. Foundation for aura-type modules.
 */
public final class RotationManager {
	private static float yaw, pitch;
	private static boolean requested; // a module asked this tick
	private static boolean spoofing;  // currently overriding outgoing rotations
	private static boolean bodyOverride; // render the body at its own yaw this tick
	private static float bodyYaw;

	private RotationManager() {
	}

	/**
	 * Renders the body at a yaw distinct from the head this tick (the head still
	 * drives the outgoing packet). Call after rotate(); used by Spinbot for
	 * head/body desync. Only visible in third person / freecam.
	 */
	public static void setBodyYaw(float newBodyYaw) {
		bodyOverride = true;
		bodyYaw = newBodyYaw;
	}

	/** Sends the given server-side rotation for this tick, immediately. */
	public static void rotate(float newYaw, float newPitch) {
		boolean changed = !requested || newYaw != yaw || newPitch != pitch;
		yaw = newYaw;
		pitch = newPitch;
		requested = true;
		// send right away so interactions that follow (buckets, projectiles)
		// are raytraced server-side from the spoofed rotation, not the camera
		Minecraft mc = Minecraft.getInstance();
		if (changed && mc.player != null && mc.getConnection() != null) {
			spoofing = true;
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					getYaw(), getPitch(), mc.player.onGround(), mc.player.horizontalCollision));
		}
	}

	/** Faces a world position server-side for this tick. */
	public static void lookAt(Vec3 target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		rotate((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f,
				(float) -Math.toDegrees(Math.atan2(dy, horizontal)));
	}

	/** True while outgoing movement packets should carry the spoofed rotation. */
	public static boolean isSpoofing() {
		return spoofing;
	}

	public static float getYaw() {
		return yaw;
	}

	public static float getPitch() {
		return Mth.clamp(pitch, -90.0f, 90.0f);
	}

	/** End of client tick: push the spoofed rotation and sync the visible body. */
	public static void onTickEnd() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) {
			requested = false;
			spoofing = false;
			return;
		}
		if (requested) {
			// visible in third person / freecam, invisible in first person
			mc.player.yHeadRot = getYaw();
			mc.player.yBodyRot = bodyOverride ? bodyYaw : getYaw();
		} else if (spoofing) {
			spoofing = false;
			// hand the server back the real camera rotation
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.horizontalCollision));
		}
		requested = false;
		bodyOverride = false;
	}
}
