package unlucky.utility.client.gui.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the area preview shows: a box of work and, cell by cell, what the work wants there.
 *
 * <p>Excavator wants air everywhere in its box, HighwayBuilder wants air in the tunnel and a
 * pave block under it, Printer wants whatever the schematic says. The preview compares that with
 * the world to tell finished cells from ones still to do.
 */
public interface AreaSource {
	String title();

	/** Inclusive {min, max} corners of the work, or null when there is none to show. */
	BlockPos[] bounds();

	/** What the work wants at {@code pos}: air to dig it out, a block to place, null for nothing. */
	BlockState wanted(BlockPos pos);

	/** Called before each rescan, for a source that builds its plan ahead of time. */
	default void refresh() {
	}

	/** The block being worked on right now, if any. */
	default BlockPos target() {
		return null;
	}

	/** The two corners the box was made from, when the user may drag them; null when read-only. */
	default BlockPos[] corners() {
		return null;
	}

	default void setCorners(BlockPos a, BlockPos b) {
	}

	/** The {min, max} box spanned by two corners. */
	static BlockPos[] box(BlockPos a, BlockPos b) {
		return new BlockPos[] {
				new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
				new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))};
	}
}
