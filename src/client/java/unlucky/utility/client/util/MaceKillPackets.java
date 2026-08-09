package unlucky.utility.client.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/** Shared movement-packet bracket for the two mace amplification modules. */
public final class MaceKillPackets {
	private MaceKillPackets() {
	}

	/**
	 * Banks a server-side fall and returns the real position that must be restored
	 * after the attack packet. The client entity is never teleported.
	 */
	public static Vec3 prime(LocalPlayer player, double height) {
		Vec3 real = player.position();
		double raised = real.y + Math.max(1.6, height);
		int fillers = Math.min(19, Math.max(0, (int) Math.ceil(height / 10.0) - 1));
		for (int i = 0; i < fillers; i++) {
			player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision));
		}
		player.connection.send(new ServerboundMovePlayerPacket.Pos(real.x, raised, real.z,
				false, player.horizontalCollision));
		player.connection.send(new ServerboundMovePlayerPacket.Pos(real.x, real.y + 0.01, real.z,
				false, player.horizontalCollision));
		return real;
	}

	/** Avoid sending a spoof into solid blocks when the raised endpoint is loaded. */
	public static boolean hasRoom(LocalPlayer player, double height) {
		if (player.level() == null) {
			return false;
		}
		BlockPos feet = BlockPos.containing(player.getX(), player.getY() + height, player.getZ());
		return player.level().getBlockState(feet).canBeReplaced()
				&& player.level().getBlockState(feet.above()).canBeReplaced()
				&& player.level().getFluidState(feet).isEmpty()
				&& player.level().getFluidState(feet.above()).isEmpty();
	}

	/** Closes the spoof after the attack, leaving the server at the real position. */
	public static void restore(LocalPlayer player, Vec3 real, boolean resetFall) {
		player.connection.send(new ServerboundMovePlayerPacket.Pos(real.x, real.y, real.z,
				player.onGround(), player.horizontalCollision));
		if (resetFall) {
			player.fallDistance = 0.0;
			player.setDeltaMovement(player.getDeltaMovement().x, Math.max(0.01, player.getDeltaMovement().y),
					player.getDeltaMovement().z);
		}
	}
}
