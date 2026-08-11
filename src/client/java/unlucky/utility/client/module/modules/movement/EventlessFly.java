package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.RotationManager;

/** Packet flight that sends its movement directly instead of modifying a move event. */
public class EventlessFly extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed",
			"Flight speed in blocks per tick", 0.062, 0.01, 1, 0.001));
	public final NumberSetting verticalMultiplier = add(new NumberSetting("Vertical multiplier",
			"Multiply ascent and descent speed without changing horizontal travel", 1.0, 0.1, 5.0, 0.1));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUI",
			"Do not send flight movement while a screen is open", true));
	public final BooleanSetting lockServerRotation = add(new BooleanSetting("Lock server rotation",
			"Keep the server-facing angle fixed while your camera remains free", true));
	public final BooleanSetting antiKick = add(new BooleanSetting("Anti kick",
			"Periodically dip down to reset floating ticks", true));
	public final NumberSetting antiKickDelay = add(new NumberSetting("Anti-kick delay",
			"Ticks between anti-kick dips", 20, 1, 200, 1), antiKick::get);
	public final NumberSetting dipTicks = add(new NumberSetting("Dip ticks",
			"How long the anti-kick dip lasts", 3, 1, 20, 1), antiKick::get);
	public final BooleanSetting bounceBack = add(new BooleanSetting("Bounce back",
			"Move upward briefly after an anti-kick dip", true), antiKick::get);

	private float lockedYaw;
	private float lockedPitch;
	private int antiKickTicks;

	public EventlessFly() {
		super("EventlessFly", "Direct-packet flight that bypasses ordinary movement events", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	public boolean suppressesMovementPackets() {
		return isEnabled();
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		if (player != null) {
			lockedYaw = player.getYRot();
			lockedPitch = player.getXRot();
		}
		antiKickTicks = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			player.setDeltaMovement(Vec3.ZERO);
			return;
		}
		if (lockServerRotation.get()) {
			RotationManager.rotate(lockedYaw, lockedPitch);
		}

		double verticalOverride = antiKickVelocity(player);
		Vec3 input = verticalOverride == 0 ? inputVelocity(player) : new Vec3(0, verticalOverride, 0);
		if (input.lengthSqr() == 0) {
			player.setDeltaMovement(Vec3.ZERO);
			return;
		}
		Vec3 velocity = input.normalize().scale(speed.get());
		if (input.y != 0.0) velocity = new Vec3(velocity.x, velocity.y * verticalMultiplier.get(), velocity.z);
		player.setDeltaMovement(velocity);
		Vec3 end = player.position().add(velocity);
		player.connection.send(new ServerboundMovePlayerPacket.Pos(end.x, end.y, end.z,
				player.onGround(), player.horizontalCollision));
		player.connection.send(new ServerboundMovePlayerPacket.Pos(end.x, -1_000_000.0, end.z,
				false, player.horizontalCollision));
	}

	private double antiKickVelocity(LocalPlayer player) {
		if (!antiKick.get() || !mc().level.noCollision(player, player.getBoundingBox().inflate(0.4))) {
			antiKickTicks = 0;
			return 0;
		}
		int cycle = antiKickDelay.getInt() + dipTicks.getInt() + (bounceBack.get() ? 1 : 0);
		int phase = antiKickTicks++ % Math.max(1, cycle);
		if (phase >= antiKickDelay.getInt() && phase < antiKickDelay.getInt() + dipTicks.getInt()) {
			return -1;
		}
		if (bounceBack.get() && phase == antiKickDelay.getInt() + dipTicks.getInt()) {
			return 1;
		}
		return 0;
	}

	private Vec3 inputVelocity(LocalPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 right = new Vec3(forward.z, 0, -forward.x);
		Vec3 result = Vec3.ZERO;
		if (mc().options.keyUp.isDown()) result = result.add(forward);
		if (mc().options.keyDown.isDown()) result = result.subtract(forward);
		if (mc().options.keyRight.isDown()) result = result.add(right);
		if (mc().options.keyLeft.isDown()) result = result.subtract(right);
		if (mc().options.keyJump.isDown()) result = result.add(0, 1, 0);
		if (mc().options.keyShift.isDown()) result = result.add(0, -1, 0);
		return result;
	}

	@Override
	protected void onDisable() {
		antiKickTicks = 0;
		if (mc().player != null) {
			mc().player.setDeltaMovement(Vec3.ZERO);
			mc().player.connection.send(new ServerboundMovePlayerPacket.Pos(
					mc().player.getX(), mc().player.getY(), mc().player.getZ(),
					mc().player.onGround(), mc().player.horizontalCollision));
		}
	}
}
