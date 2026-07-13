package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import unlucky.utility.client.util.RotationManager;

/**
 * Rewrites outgoing rotation-bearing packets with the spoofed rotation while
 * active. Movement packets cover attacks and block-breaking; since ~1.20.2
 * {@code ServerboundUseItemPacket} carries its own yaw/pitch which the server
 * re-applies before using the item — so projectile throws (AutoXPRepair's
 * look-down XP bottles) need that one rewritten too, or the spoof is silently
 * ignored for anything thrown.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {
	@ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
	private Packet<?> unlucky$spoofRotation(Packet<?> packet) {
		if (!RotationManager.isSpoofing()) {
			return packet;
		}
		float yaw = RotationManager.getYaw();
		float pitch = RotationManager.getPitch();
		if (packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
			if (move.hasPosition()) {
				return new ServerboundMovePlayerPacket.PosRot(move.getX(0), move.getY(0), move.getZ(0),
						yaw, pitch, move.isOnGround(), move.horizontalCollision());
			}
			return new ServerboundMovePlayerPacket.Rot(yaw, pitch, move.isOnGround(), move.horizontalCollision());
		}
		if (packet instanceof ServerboundUseItemPacket use) {
			return new ServerboundUseItemPacket(use.getHand(), use.getSequence(), yaw, pitch);
		}
		return packet;
	}
}
