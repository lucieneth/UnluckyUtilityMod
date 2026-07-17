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
 *
 * <p><b>One rotation per tick, highest priority wins.</b> Several modules can want
 * the head at once, and without a rule the winner is just whoever ticks last —
 * i.e. alphabetical order, which is how Spinbot used to quietly steer Aura's hits
 * into the floor. Aim that has to be correct outranks aim that's for show.
 */
public final class RotationManager {
	/** Aim an action depends on: a wrong angle means the hit or place is rejected. */
	public static final int PRIORITY_FUNCTIONAL = 100;
	/** Aim that's only for looks, and yields to anything real. */
	public static final int PRIORITY_COSMETIC = 10;

	private static float yaw, pitch;
	private static boolean requested; // a module asked this tick
	private static int priority;      // of whoever currently holds this tick
	private static boolean spoofing;  // currently overriding outgoing rotations
	private static boolean bodyOverride; // render the body at its own yaw this tick
	private static float bodyYaw;

	private static float faceYaw, facePitch; // where the in-progress turn has got to
	private static boolean facing;      // face() was called this tick
	private static boolean faceActive;  // a turn is underway

	private RotationManager() {
	}

	/**
	 * Turns toward a world position over several ticks instead of snapping, and
	 * reports true once we're actually looking at it. Call it every tick and gate the
	 * action on the return: <b>turn first, act when aimed.</b>
	 *
	 * <p>{@link #lookAt} snaps, which is right for anything that must land this tick
	 * (Aura mid-swing) and wrong for everything else. A snap costs you the whole
	 * point of a visible rotation — one tick is ~3 frames, so nobody, including you in
	 * F5, ever sees it — and an instant 180° is the least human thing on the wire.
	 * This is the same server-side spoof, just walked there: the camera never moves,
	 * but you and everyone else watch the model turn.
	 *
	 * <p>The turn starts from the <em>camera's</em> real rotation, so it begins where
	 * you're actually looking rather than from wherever the last spoof left off.
	 *
	 * @param speed degrees per tick; 180 is effectively a snap
	 * @return true once within a degree of the target, on both axes
	 */
	public static boolean face(Vec3 target, float speed) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return false;
		}
		if (!faceActive) {
			faceYaw = mc.player.getYRot();
			facePitch = mc.player.getXRot();
			faceActive = true;
		}
		Vec3 eye = mc.player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
		float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		faceYaw = approach(faceYaw, wantYaw, speed);
		facePitch = approach(facePitch, wantPitch, speed);
		facing = true;
		rotate(faceYaw, facePitch);
		return Math.abs(Mth.wrapDegrees(wantYaw - faceYaw)) < 1.0f
				&& Math.abs(wantPitch - facePitch) < 1.0f;
	}

	/** Steps {@code from} toward {@code to} by at most {@code max}, the short way round. */
	private static float approach(float from, float to, float max) {
		float delta = Mth.wrapDegrees(to - from);
		return from + Mth.clamp(delta, -max, max);
	}

	/**
	 * Renders the body at a yaw distinct from the head this tick (the head still
	 * drives the outgoing packet). Call after rotate(); used by Spinbot for
	 * head/body desync. Only visible in third person / freecam.
	 */
	public static void setBodyYaw(float newBodyYaw) {
		setBodyYaw(newBodyYaw, PRIORITY_FUNCTIONAL);
	}

	/**
	 * As {@link #setBodyYaw(float)}, but yields the body to whoever holds the tick
	 * at a higher priority — losing the head but keeping the torso would leave the
	 * model spinning around a locked head, which is worse than either on its own.
	 * Refused here, the body just follows the winner's head.
	 */
	public static void setBodyYaw(float newBodyYaw, int newPriority) {
		if (requested && newPriority < priority) {
			return;
		}
		bodyOverride = true;
		bodyYaw = newBodyYaw;
	}

	/** Sends the given server-side rotation for this tick, immediately. */
	public static void rotate(float newYaw, float newPitch) {
		rotate(newYaw, newPitch, PRIORITY_FUNCTIONAL);
	}

	/**
	 * As {@link #rotate(float, float)}, but yields to anything that already claimed
	 * this tick at a higher priority — so a cosmetic spin can't steal the head from
	 * a module that's mid-swing.
	 */
	public static void rotate(float newYaw, float newPitch, int newPriority) {
		if (requested && newPriority < priority) {
			return;
		}
		// outranking whoever held the tick also takes the body from them: their yaw
		// was set for their head, and pairing it with ours is the desync we're
		// avoiding. Only matters if the loser ticked first — don't rely on that
		if (requested && newPriority > priority) {
			bodyOverride = false;
		}
		boolean changed = !requested || newYaw != yaw || newPitch != pitch;
		yaw = newYaw;
		pitch = newPitch;
		requested = true;
		priority = newPriority;
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
		// a turn nobody renewed this tick is over: the next one starts from the camera
		// again rather than resuming from a stale half-turn
		if (!facing) {
			faceActive = false;
		}
		facing = false;
		if (mc.player == null || mc.getConnection() == null) {
			requested = false;
			priority = 0;
			spoofing = false;
			faceActive = false;
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
		priority = 0;
		bodyOverride = false;
	}
}
