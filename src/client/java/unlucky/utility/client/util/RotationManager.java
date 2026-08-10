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
	/** Structural placement (Surround/Scaffold), below emergency and direct combat aim. */
	public static final int PRIORITY_PLACEMENT = 70;
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

	/**
	 * Ticks the spoofed pose survives after the last request. Modules that act on a
	 * cadence — the Printer places every other tick at Delay 1 — used to lose the
	 * visible turn on every quiet tick: nothing rewrote the pose, vanilla re-derived it
	 * from travel, and the model flickered between aim and flight ten times a second,
	 * which reads as no rotation at all. A few ticks of hold bridges the gaps; the
	 * handback to the camera happens when the hold runs out, not the instant one tick
	 * goes quiet.
	 */
	private static final int POSE_HOLD_TICKS = 4;
	private static int holdTicks;

	// The pose as last requested, kept for the renderer. Rendering happens between ticks
	// and reads these, so they must outlive the per-tick request flags.
	private static float poseYaw;
	private static float poseBodyYaw;
	/**
	 * When a rotation was last asked for, in wall-clock milliseconds, and how long the
	 * model keeps showing it afterwards.
	 *
	 * <p>The renderer deliberately does not consult {@link #spoofing} or {@link #holdTicks}:
	 * those are tick-loop bookkeeping, written at end of tick, and a frame that lands in
	 * the wrong part of that cycle sees a pose nobody set. A timestamp written at the
	 * moment of the request is true whenever the renderer asks.
	 */
	private static long lastRequestMs;
	private static final long VISUAL_HOLD_MS = 250L;

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
		return face(target, speed, PRIORITY_FUNCTIONAL);
	}

	/**
	 * Priority-aware form of {@link #face(Vec3, float)}.
	 *
	 * <p>The turn does not advance while a stronger request owns the tick. Advancing a hidden
	 * local accumulator anyway would let a low-priority placement appear to finish turning
	 * while the server was actually looking wherever the higher-priority combat action asked.
	 */
	public static boolean face(Vec3 target, float speed, int newPriority) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !canRequest(newPriority)) {
			return false;
		}
		if (!faceActive) {
			// resume from the pose currently on show rather than the camera: a module
			// that aims in bursts (the Printer between placements) is still visibly
			// holding its last angle, and restarting from the camera would snap the head
			// back before turning out again
			boolean holding = hasVisualPose();
			faceYaw = holding ? poseYaw : mc.player.getYRot();
			facePitch = holding ? getPitch() : mc.player.getXRot();
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
		if (!rotateIfAllowed(faceYaw, facePitch, newPriority)) {
			return false;
		}
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
		poseBodyYaw = newBodyYaw;
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
		rotateIfAllowed(newYaw, newPitch, newPriority);
	}

	/**
	 * Requests a rotation and reports whether it won this tick.
	 *
	 * <p>Most callers can fire-and-forget through {@link #rotate}; placement callers cannot:
	 * sending the click after losing the rotation lease makes the server derive the block from
	 * somebody else's yaw. Returning the decision keeps the action gated on the shared owner
	 * instead of duplicating priority state in every module.
	 */
	public static boolean rotateIfAllowed(float newYaw, float newPitch, int newPriority) {
		if (!canRequest(newPriority)) {
			return false;
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
		// the renderer's copy is updated here, at the moment of the request, so a frame
		// drawn before this tick ends still shows the pose that was just asked for
		poseYaw = newYaw;
		if (!bodyOverride) {
			poseBodyYaw = newYaw;
		}
		lastRequestMs = System.currentTimeMillis();
		// send right away so interactions that follow (buckets, projectiles)
		// are raytraced server-side from the spoofed rotation, not the camera
		Minecraft mc = Minecraft.getInstance();
		if (changed && mc.player != null && mc.getConnection() != null) {
			spoofing = true;
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					getYaw(), getPitch(), mc.player.onGround(), mc.player.horizontalCollision));
		}
		return true;
	}

	/** Equal priority keeps the existing last-caller-wins behavior; only a stronger claim blocks. */
	private static boolean canRequest(int newPriority) {
		return !requested || newPriority >= priority;
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

	/**
	 * Adds a small visible camera correction without claiming the silent server rotation.
	 *
	 * <p>AimAssist is deliberately camera assistance, not another spoof owner. If Aura or a
	 * placement module owns the server angle this tick, its packet remains authoritative while
	 * the player's camera is still allowed to move naturally underneath it. Calling this through
	 * the manager keeps the visible/silent distinction explicit instead of letting modules write
	 * rotation fields with subtly different clamping rules.
	 */
	public static void assistVisible(float yawDelta, float pitchDelta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !Float.isFinite(yawDelta) || !Float.isFinite(pitchDelta)) {
			return;
		}
		mc.player.setYRot(mc.player.getYRot() + yawDelta);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitchDelta, -90.0f, 90.0f));
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
			holdTicks = POSE_HOLD_TICKS;
			applyPose(mc);
		} else if (spoofing && holdTicks > 0) {
			// a quiet tick inside the hold: keep showing the last spoof, send nothing
			holdTicks--;
			applyPose(mc);
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

	/**
	 * Writes the spoofed pose onto the entity for third person / freecam.
	 *
	 * <p>Both ends of the render lerp are set: while the player is being flown, vanilla
	 * re-derives the pose from travel every tick, and interpolating from its value to
	 * ours smeared the turn into nothing. Snapping is also the honest picture — the
	 * server-side rotation this represents really did change in one tick.
	 */
	private static void applyPose(Minecraft mc) {
		poseYaw = getYaw();
		poseBodyYaw = bodyOverride ? bodyYaw : getYaw();
		mc.player.yHeadRot = poseYaw;
		mc.player.yHeadRotO = poseYaw;
		mc.player.yBodyRot = poseBodyYaw;
		mc.player.yBodyRotO = poseBodyYaw;
	}

	/**
	 * Drops the spoof this instant and hands the server back the camera's real rotation.
	 *
	 * <p>For Panic, and it has to be immediate rather than "stop asking and let the hold run
	 * out": {@link #onTickEnd} only returns the rotation after {@link #POSE_HOLD_TICKS} quiet
	 * ticks, and a fifth of a second of still-spoofed aim after you hit the panic key is a
	 * fifth of a second nobody asked for. The pose is dropped too, so the model is not left
	 * visibly staring at whatever the last module aimed it at.
	 */
	public static void cancel() {
		Minecraft mc = Minecraft.getInstance();
		boolean wasSpoofing = spoofing;
		requested = false;
		priority = 0;
		holdTicks = 0;
		facing = false;
		faceActive = false;
		bodyOverride = false;
		spoofing = false;
		lastRequestMs = 0L; // hasVisualPose() reads false immediately
		if (wasSpoofing && mc.player != null && mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.horizontalCollision));
		}
	}

	/** Head yaw of the last requested pose, for the render-state override. */
	public static float getPoseYaw() {
		return poseYaw;
	}

	/** Body yaw of the last requested pose — differs from the head only for Spinbot. */
	public static float getPoseBodyYaw() {
		return poseBodyYaw;
	}

	/**
	 * Whether the model should be drawn at the spoofed pose right now.
	 *
	 * <p>Asked by the renderer instead of {@link #isSpoofing()}: a module that aims on a
	 * cadence (the Printer places every few ticks) leaves quiet ticks in between, and
	 * whether a given frame sees a pose should depend on how long ago the aim was, not on
	 * where the frame fell in the tick loop's bookkeeping.
	 */
	public static boolean hasVisualPose() {
		return System.currentTimeMillis() - lastRequestMs < VISUAL_HOLD_MS;
	}

	/** Milliseconds since the last rotation request — for the {@code .rot} read-out. */
	public static long sinceRequestMs() {
		return System.currentTimeMillis() - lastRequestMs;
	}

	/** One line of internal state, for the {@code .rot} read-out. */
	public static String debug() {
		return String.format("spoofing=%b hold=%d yaw=%.1f pitch=%.1f pose=%.1f/%.1f age=%dms",
				spoofing, holdTicks, yaw, getPitch(), poseYaw, poseBodyYaw, sinceRequestMs());
	}
}
