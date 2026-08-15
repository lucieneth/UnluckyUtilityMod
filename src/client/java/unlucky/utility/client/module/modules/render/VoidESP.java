package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.PerfDebug;
import unlucky.utility.client.util.Render3D;

/**
 * The gaps in the bedrock floor — and, in the nether, the gaps in its roof.
 *
 * <p>A column counts when every one of the bottom {@code Hole height} blocks is
 * something other than bedrock, so a hole one block deep in a two-deep floor is
 * not a way down and is not drawn. "Air only" narrows it further to columns that
 * are actually open, which is the useful setting when you are looking for a way
 * through rather than for thin floor.
 *
 * <p>Adjacent columns are merged into rectangles before drawing, for the reason
 * HoleESP merges its cells: a two-wide gap is one hole, and drawing it as two
 * boxes puts a line down the middle of a space that has no line in it.
 *
 * <p>The scan is cached and refreshed on movement or a timer rather than run
 * every tick — at a 48-block radius that is 9,409 columns, and the bedrock layer
 * only changes when somebody mines it.
 *
 * <p>Reference: Meteor's VoidESP.
 */
public class VoidESP extends Module {
	/** How far the player must move before the cached sweep is worth redoing. */
	private static final double MOVE_INVALIDATE = 4.0;
	/** The nether roof's top layer, which is bedrock in every generated world. */
	private static final int NETHER_ROOF_Y = 127;

	public final NumberSetting horizontalRadius = add(new NumberSetting("Horizontal radius",
			"Blocks scanned either side of you", 48, 8, 128, 8));
	public final BooleanSetting airOnly = add(new BooleanSetting("Air only",
			"Only count columns that are open, not merely thin", false));
	public final NumberSetting holeHeight = add(new NumberSetting("Hole height",
			"Layers that must all be clear for a column to count", 1, 1, 5, 1));
	public final BooleanSetting netherRoof = add(new BooleanSetting("Nether roof",
			"Also mark holes in the nether roof", true));
	public final NumberSetting refreshInterval = add(new NumberSetting("Refresh interval",
			"Ticks between rescans when you stand still", 20, 5, 100, 5));

	public final ModeSetting shape = add(new ModeSetting("Shape",
			"Outline, filled faces, or both", "Both", "Outline", "Fill", "Both"));
	public final NumberSetting height = add(new NumberSetting("Height",
			"How tall the marker box is drawn", 1.0, 0.05, 2.0, 0.05));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Colour of the marked holes", ColorUtil.argb(255, 225, 25, 255)));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw holes that are behind terrain", true));

	private final List<AABB> boxes = new ArrayList<>();
	private BlockPos cacheOrigin;
	private int sinceRefresh;

	public VoidESP() {
		super("VoidESP", "Marks holes in the bedrock that lead to the void", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		boxes.clear();
		cacheOrigin = null;
		sinceRefresh = Integer.MAX_VALUE;
	}

	@Override
	protected void onDisable() {
		boxes.clear();
		cacheOrigin = null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			onDisable();
			return;
		}
		// The end's floor is islands over open sky: every column would qualify.
		if (mc().level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
			boxes.clear();
			return;
		}
		BlockPos here = mc().player.blockPosition();
		sinceRefresh++;
		boolean moved = cacheOrigin == null
				|| here.distSqr(cacheOrigin) > MOVE_INVALIDATE * MOVE_INVALIDATE;
		if (moved || sinceRefresh >= refreshInterval.getInt()) {
			rescan(here);
		}
		render();
	}

	private void rescan(BlockPos origin) {
		long start = PerfDebug.begin();
		boxes.clear();
		cacheOrigin = origin;
		sinceRefresh = 0;

		int radius = horizontalRadius.getInt();
		int size = radius * 2 + 1;
		int minX = origin.getX() - radius;
		int minZ = origin.getZ() - radius;

		collect(minX, minZ, size, mc().level.getMinY(), false);
		if (netherRoof.get() && mc().level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
			collect(minX, minZ, size, NETHER_ROOF_Y, true);
		}
		PerfDebug.end("tick.VoidESP.scan", start);
	}

	/** Sweeps one layer into a grid, then hands the grid to the rectangle merge. */
	private void collect(int minX, int minZ, int size, int layerY, boolean downwards) {
		boolean[] hits = new boolean[size * size];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		boolean any = false;
		for (int dx = 0; dx < size; dx++) {
			for (int dz = 0; dz < size; dz++) {
				if (isHole(cursor, minX + dx, minZ + dz, layerY, downwards)) {
					hits[dx * size + dz] = true;
					any = true;
				}
			}
		}
		if (any) {
			merge(hits, size, minX, minZ, layerY);
		}
	}

	/**
	 * Every layer of the column has to be clear. Reading through the chunk rather
	 * than the level skips the per-block chunk lookup, and an unloaded chunk is
	 * never a hole — we simply have not seen it yet.
	 */
	private boolean isHole(BlockPos.MutableBlockPos cursor, int x, int z, int layerY, boolean downwards) {
		ChunkAccess chunk = mc().level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
		if (chunk == null) {
			return false;
		}
		for (int i = 0; i < holeHeight.getInt(); i++) {
			cursor.set(x, downwards ? layerY - i : layerY + i, z);
			if (mc().level.isOutsideBuildHeight(cursor.getY())) {
				return false;
			}
			var block = chunk.getBlockState(cursor).getBlock();
			if (airOnly.get() ? block != Blocks.AIR : block == Blocks.BEDROCK) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Greedy rectangle merge: runs along Z first, then any run directly beside an
	 * identical one on X absorbs it. Cheap, and enough to turn the usual
	 * long thin gaps into one box each.
	 */
	private void merge(boolean[] hits, int size, int minX, int minZ, int layerY) {
		for (int dx = 0; dx < size; dx++) {
			int runStart = -1;
			for (int dz = 0; dz <= size; dz++) {
				boolean hit = dz < size && hits[dx * size + dz];
				if (hit && runStart < 0) {
					runStart = dz;
				} else if (!hit && runStart >= 0) {
					int endX = dx;
					// Absorb identical runs on the next X columns and clear them as we go.
					while (endX + 1 < size && sameRun(hits, size, endX + 1, runStart, dz)) {
						endX++;
						clearRun(hits, size, endX, runStart, dz);
					}
					boxes.add(new AABB(minX + dx, layerY, minZ + runStart,
							minX + endX + 1.0, layerY + height.get(), minZ + dz));
					runStart = -1;
				}
			}
		}
	}

	private boolean sameRun(boolean[] hits, int size, int dx, int from, int to) {
		for (int dz = from; dz < to; dz++) {
			if (!hits[dx * size + dz]) {
				return false;
			}
		}
		// The run must end exactly here too, or the merge would swallow a longer neighbour.
		return (from == 0 || !hits[dx * size + from - 1]) && (to >= size || !hits[dx * size + to]);
	}

	private void clearRun(boolean[] hits, int size, int dx, int from, int to) {
		for (int dz = from; dz < to; dz++) {
			hits[dx * size + dz] = false;
		}
	}

	private void render() {
		if (boxes.isEmpty()) {
			return;
		}
		int base = color.get();
		int line = shape.is("Fill") ? 0 : base;
		int fill = shape.is("Outline") ? 0 : ColorUtil.withAlpha(base, 50);
		for (AABB box : boxes) {
			Render3D.box(box, line, 1.5f, fill, throughWalls.get());
		}
	}
}
