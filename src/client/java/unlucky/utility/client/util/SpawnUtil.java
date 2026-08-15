package unlucky.utility.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * One answer to "can something spawn here", shared by the module that draws it
 * and the module that acts on it.
 *
 * <p>Same reason {@link HoleUtil} exists: LightOverlay marks the ground and
 * SpawnProofer covers it, and a marker one of them honours and the other ignores
 * is not a disagreement the player can debug — it just looks like one of the two
 * is broken.
 */
public final class SpawnUtil {
	/** What a position is worth covering for. */
	public enum Spawn {
		/** Nothing can spawn here, now or at night. */
		NONE,
		/** Only once the sky darkens — outdoors ground under an open sky. */
		POTENTIAL,
		/** Dark enough right now, whatever the time of day. */
		ALWAYS
	}

	private SpawnUtil() {
	}

	/**
	 * Classifies one position against a block-light threshold.
	 *
	 * <p>The threshold is the light level a mob tolerates: 0 for modern spawning
	 * rules, 7 for the pre-1.18 behaviour some servers still run.
	 */
	public static Spawn spawnAt(Level level, BlockPos pos, int threshold) {
		if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, pos, EntityTypes.ZOMBIE)) {
			return Spawn.NONE;
		}
		if (level.getBrightness(LightLayer.BLOCK, pos) > threshold) {
			return Spawn.NONE; // lit well enough that the time of day cannot make it worse
		}
		return level.getBrightness(LightLayer.SKY, pos) <= threshold ? Spawn.ALWAYS : Spawn.POTENTIAL;
	}
}
