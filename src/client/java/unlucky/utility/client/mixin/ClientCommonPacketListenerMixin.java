package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import unlucky.utility.client.util.RotationManager;

/** Rewrites outgoing movement packets with the spoofed rotation while active. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {
	@ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
	private Packet<?> unlucky$spoofRotation(Packet<?> packet) {
		if (RotationManager.isSpoofing()
				&& packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
			float yaw = RotationManager.getYaw();
			float pitch = RotationManager.getPitch();
			if (move.hasPosition()) {
				return new ServerboundMovePlayerPacket.PosRot(move.getX(0), move.getY(0), move.getZ(0),
						yaw, pitch, move.isOnGround(), move.horizontalCollision());
			}
			return new ServerboundMovePlayerPacket.Rot(yaw, pitch, move.isOnGround(), move.horizontalCollision());
		}
		return packet;
	}
}
