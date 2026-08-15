package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.player.XCarry;
import unlucky.utility.client.util.PacketQueueManager;
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

	/**
	 * Runs at the actual connection write, after the HEAD variable modifier above has put any
	 * silent rotation into the packet. A later flush writes straight to {@link Connection}, so
	 * the captured packet is neither re-queued nor rewritten with a newer angle.
	 *
	 * <p>XCarry is tested before the queue rather than after it: its packet is one the module
	 * wants <em>dropped</em>, and a dropped packet must never reach a buffer that a later flush
	 * would faithfully deliver.
	 */
	@Redirect(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private void unlucky$queueEligible(Connection connection, Packet<?> packet) {
		if (packet instanceof net.minecraft.network.protocol.game.ServerboundSignUpdatePacket sign) {
			// The template is whatever you last wrote by hand, read off the wire rather
			// than out of the screen: the screen's text is not final until it closes.
			UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.world.AutoSign.class).captureTemplate(sign);
		}
		if (packet instanceof ServerboundContainerClosePacket close
				&& UnluckyClient.INSTANCE.modules.get(XCarry.class).suppressesClose(close.getContainerId())) {
			return;
		}
		if (!PacketQueueManager.intercept(packet)) {
			connection.send(packet);
		}
	}
}
