package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Helpers for scanning the loaded world around the player. */
public final class WorldScan {
	private WorldScan() {
	}

	/** All block entities within {@code range} blocks of the player. */
	public static List<BlockEntity> blockEntitiesAround(double range) {
		List<BlockEntity> result = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			return result;
		}

		double rangeSq = range * range;
		int chunkRadius = (int) Math.ceil(range / 16.0);
		int playerChunkX = player.blockPosition().getX() >> 4;
		int playerChunkZ = player.blockPosition().getZ() >> 4;

		for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
			for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					BlockPos pos = blockEntity.getBlockPos();
					double dx = pos.getX() + 0.5 - player.getX();
					double dy = pos.getY() + 0.5 - player.getY();
					double dz = pos.getZ() + 0.5 - player.getZ();
					if (dx * dx + dy * dy + dz * dz <= rangeSq) {
						result.add(blockEntity);
					}
				}
			}
		}
		return result;
	}
}
