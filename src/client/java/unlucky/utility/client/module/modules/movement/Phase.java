package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/** Client no-clip with either live packets or one deferred teleport on disable. */
public class Phase extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"TP suppresses movement packets until disable; NoClip sends normally", "TP", "TP", "NoClip"));
	public final NumberSetting horizontalSpeed = add(new NumberSetting("Horizontal speed",
			"Horizontal no-clip speed in blocks per tick", 0.1, 0.01, 1, 0.01));
	public final NumberSetting verticalSpeed = add(new NumberSetting("Vertical speed",
			"Vertical no-clip speed in blocks per tick", 0.1, 0.01, 1, 0.01));
	public final NumberSetting sprintMultiplier = add(new NumberSetting("Sprint multiplier",
			"Speed multiplier while holding sprint", 3, 1, 10, 0.5));

	private boolean finishing;
	private Vec3 startPos;

	public Phase() {
		super("Phase", "Move through blocks, optionally deferring the server teleport", Category.MOVEMENT);
	}

	public boolean suppressesMovementPackets() {
		return isEnabled() && mode.is("TP") && !finishing;
	}

	@Override
	protected void onEnable() {
		startPos = mc().player == null ? null : mc().player.position();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return;
		}
		player.noPhysics = true;
		double multiplier = mc().options.keySprint.isDown() ? sprintMultiplier.get() : 1.0;
		double yaw = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 right = new Vec3(forward.z, 0, -forward.x);
		Vec3 movement = Vec3.ZERO;
		if (mc().options.keyUp.isDown()) movement = movement.add(forward.scale(horizontalSpeed.get()));
		if (mc().options.keyDown.isDown()) movement = movement.subtract(forward.scale(horizontalSpeed.get()));
		if (mc().options.keyRight.isDown()) movement = movement.add(right.scale(horizontalSpeed.get()));
		if (mc().options.keyLeft.isDown()) movement = movement.subtract(right.scale(horizontalSpeed.get()));
		if (mc().options.keyJump.isDown()) movement = movement.add(0, verticalSpeed.get(), 0);
		if (mc().options.keyShift.isDown()) movement = movement.add(0, -verticalSpeed.get(), 0);
		player.setDeltaMovement(movement.scale(multiplier));
		player.fallDistance = 0.0;
	}

	@Override
	protected void onDisable() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return;
		}
		player.noPhysics = false;
		player.setDeltaMovement(Vec3.ZERO);
		if (mode.is("TP")) {
			finishing = true;
			try {
				double distance = startPos == null ? 0.0 : startPos.distanceTo(player.position());
				int fillers = Math.min(19, Math.max(0, (int) Math.ceil(distance / 10.0) - 1));
				for (int i = 0; i < fillers; i++) {
					player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
				}
				player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), player.getY(), player.getZ(),
						true, player.horizontalCollision));
			} finally {
				finishing = false;
			}
		}
		startPos = null;
	}
}
