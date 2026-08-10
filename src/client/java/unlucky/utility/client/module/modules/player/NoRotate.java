package unlucky.utility.client.module.modules.player;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.RotationManager;

/** Keeps server corrections positional without letting them seize the local camera. */
public class NoRotate extends Module {
	public final BooleanSetting blockYaw = add(new BooleanSetting("Block yaw",
			"Keep the current camera yaw through server corrections", true));
	public final BooleanSetting blockPitch = add(new BooleanSetting("Block pitch",
			"Keep the current camera pitch through server corrections", true));
	public final BooleanSetting acknowledgeCurrent = add(new BooleanSetting(
			"Acknowledge with current rotation",
			"Reply with the camera rotation kept locally; Off acknowledges the server-requested angle",
			true));
	public final BooleanSetting onlyAlive = add(new BooleanSetting("Only while alive",
			"Let death/respawn correction rotation apply normally", true));

	private float serverYaw;
	private float serverPitch;
	private boolean correctionPrepared;

	public NoRotate() {
		super("NoRotate", "Honors server position corrections without moving the camera",
				Category.PLAYER, ServerVisibility.CONDITIONAL);
	}

	@Override
	public boolean isServerObservableNow() {
		return active() && (blockYaw.get() || blockPitch.get());
	}

	/** Rewrites only the change record passed into vanilla's correction application. */
	public PositionMoveRotation filter(ClientboundPlayerPositionPacket packet,
			PositionMoveRotation change) {
		if (!active()) {
			return change;
		}
		float currentYaw = mc().player.getYRot();
		float currentPitch = mc().player.getXRot();
		serverYaw = packet.relatives().contains(Relative.Y_ROT)
				? currentYaw + change.yRot() : change.yRot();
		serverPitch = packet.relatives().contains(Relative.X_ROT)
				? currentPitch + change.xRot() : change.xRot();
		correctionPrepared = true;
		float filteredYaw = blockYaw.get()
				? (packet.relatives().contains(Relative.Y_ROT) ? 0.0f : currentYaw)
				: change.yRot();
		float filteredPitch = blockPitch.get()
				? (packet.relatives().contains(Relative.X_ROT) ? 0.0f : currentPitch)
				: change.xRot();
		return new PositionMoveRotation(change.position(), change.deltaMovement(),
				filteredYaw, filteredPitch);
	}

	public float filterYaw(ClientboundPlayerRotationPacket packet, float yaw) {
		if (!active()) {
			return yaw;
		}
		float currentYaw = mc().player.getYRot();
		float currentPitch = mc().player.getXRot();
		serverYaw = packet.relativeY() ? currentYaw + packet.yRot() : packet.yRot();
		serverPitch = packet.relativeX() ? currentPitch + packet.xRot() : packet.xRot();
		correctionPrepared = true;
		return blockYaw.get() ? (packet.relativeY() ? 0.0f : currentYaw) : yaw;
	}

	public float filterPitch(ClientboundPlayerRotationPacket packet, float pitch) {
		if (!active() || !blockPitch.get()) {
			return pitch;
		}
		return packet.relativeX() ? 0.0f : mc().player.getXRot();
	}

	/** Replaces only the acknowledgment's rotation when the user requested server angles. */
	public Packet<?> acknowledgement(Packet<?> packet) {
		if (!active() || !(packet instanceof ServerboundMovePlayerPacket move)) {
			return packet;
		}
		if (!correctionPrepared || acknowledgeCurrent.get()) {
			correctionPrepared = false;
			return packet;
		}
		correctionPrepared = false;
		if (move.hasPosition()) {
			return new ServerboundMovePlayerPacket.PosRot(
					move.getX(mc().player.getX()), move.getY(mc().player.getY()),
					move.getZ(mc().player.getZ()),
					blockYaw.get() ? serverYaw : move.getYRot(mc().player.getYRot()),
					blockPitch.get() ? serverPitch : move.getXRot(mc().player.getXRot()),
					move.isOnGround(), move.horizontalCollision());
		}
		return new ServerboundMovePlayerPacket.Rot(
				blockYaw.get() ? serverYaw : move.getYRot(mc().player.getYRot()),
				blockPitch.get() ? serverPitch : move.getXRot(mc().player.getXRot()),
				move.isOnGround(), move.horizontalCollision());
	}

	/** A teleport invalidates any silent angle that was staged before it. */
	public void onCorrection() {
		correctionPrepared = false;
		RotationManager.cancel();
	}

	@Override
	protected void onDisable() {
		correctionPrepared = false;
	}

	private boolean active() {
		return isEnabled() && mc().player != null
				&& (!onlyAlive.get() || !mc().player.isDeadOrDying());
	}
}
