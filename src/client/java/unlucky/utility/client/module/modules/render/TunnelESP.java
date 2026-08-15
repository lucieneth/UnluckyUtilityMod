package unlucky.utility.client.module.modules.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
 * Finds dug corridors: a walkable space, two blocks tall, walled on one axis and
 * open on the other. Nothing else in a cave system looks like that, which is why
 * it picks out player tunnels from natural terrain without a block list.
 *
 * <p>The test is Meteor's, block for block — a position qualifies when you could
 * stand in it, the two blocks on one axis are solid to head height, and the two
 * on the other axis are themselves stand-able. A side that is only half blocked
 * disqualifies the position outright rather than counting as either, which is
 * what stops cave mouths and ravine edges from lighting up.
 *
 * <p><b>Both filters are Meteor's too.</b> A cell survives if it is connected to
 * another cell in any of the four directions, and the scan stops at the surface
 * heightmap. Neither is decoration: the first is what keeps single doorways out
 * while keeping corners in, and the second is what keeps ledges and roofed gaps
 * out — they pass the stand-able-with-a-ceiling test perfectly well.
 *
 * <p>What is ours is where and when the work happens, never what counts. The
 * sweep is a budgeted flat-index walk on the client thread around the player,
 * the same shape as LightOverlay, instead of per-chunk jobs on a worker — our
 * renderer emits gizmos from the tick, and a background thread reading chunk
 * sections is the race this codebase already learned to avoid. And a group of
 * surviving cells is drawn as one long box per shared-axis run rather than as a
 * line of cubes with seams, which changes the picture and not the contents.
 *
 * <p>That line moved once already, and it cost the module its corners: the run
 * merge was doing the filtering, and a cell around a bend carries the other
 * axis, so it stopped matching and was dropped. Merging draws; it does not judge.
 *
 * <p>Reference: Meteor's TunnelESP.
 */
public class TunnelESP extends Module {
	private static final double MOVE_INVALIDATE = 4.0;
	/** The four horizontal steps, as {dx, dz}. Connectivity is per layer, like Meteor's. */
	private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	public final NumberSetting horizontalRange = add(new NumberSetting("Horizontal range",
			"Blocks scanned either side of you", 32, 8, 64, 4));
	public final NumberSetting verticalRange = add(new NumberSetting("Vertical range",
			"Blocks scanned above and below you", 16, 4, 64, 4));
	public final NumberSetting minimumLength = add(new NumberSetting("Minimum length",
			"Connected cells a corridor needs before it is drawn (2 is Meteor's rule)", 2, 1, 16, 1));
	public final BooleanSetting connected = add(new BooleanSetting("Connected",
			"Draw a corridor as one box instead of one per block", true));
	public final BooleanSetting undergroundOnly = add(new BooleanSetting("Underground only",
			"Ignore anything at or above the surface", true));

	public final ModeSetting shape = add(new ModeSetting("Shape",
			"Outline, filled faces, or both", "Both", "Outline", "Fill", "Both"));
	public final NumberSetting height = add(new NumberSetting("Height",
			"How tall the marker box is drawn", 0.1, 0.05, 2.0, 0.05));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Colour of the marked corridors", ColorUtil.argb(255, 255, 175, 25)));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw corridors that are behind terrain", true));

	public final NumberSetting scanBudget = add(new NumberSetting("Scan budget",
			"Block positions examined per tick", 1500, 100, 20000, 100));
	public final NumberSetting refreshInterval = add(new NumberSetting("Refresh interval",
			"Ticks before the cached corridors are rebuilt", 40, 10, 400, 10));

	/** One qualifying position. Deliberately without the axis — see {@link #rebuildBoxes}. */
	private record Cell(int x, int y, int z) {
		Cell offset(int dx, int dz) {
			return new Cell(x + dx, y, z + dz);
		}
	}

	/** Qualifying positions and the axis each one's corridor runs along. */
	private final Map<Cell, Boolean> building = new LinkedHashMap<>();
	private final List<AABB> boxes = new ArrayList<>();
	private BlockPos scanOrigin;
	private BlockPos cacheOrigin;
	private int cursor = -1;
	private int sinceRefresh;

	public TunnelESP() {
		super("TunnelESP", "Highlights dug tunnels", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		invalidate();
	}

	@Override
	protected void onDisable() {
		boxes.clear();
		building.clear();
		cursor = -1;
		cacheOrigin = null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			onDisable();
			return;
		}
		BlockPos here = mc().player.blockPosition();
		sinceRefresh++;
		boolean moved = cacheOrigin == null
				|| here.distSqr(cacheOrigin) > MOVE_INVALIDATE * MOVE_INVALIDATE;
		if (cursor < 0 && (moved || sinceRefresh >= refreshInterval.getInt())) {
			invalidate();
		}
		if (cursor >= 0) {
			advanceScan();
		}
		render();
	}

	/** Restarts the sweep. The boxes from the last finished pass keep drawing meanwhile. */
	private void invalidate() {
		scanOrigin = mc().player == null ? null : mc().player.blockPosition();
		building.clear();
		cursor = scanOrigin == null ? -1 : 0;
		sinceRefresh = 0;
	}

	private void advanceScan() {
		long start = PerfDebug.begin();
		int horizontal = horizontalRange.getInt();
		int vertical = verticalRange.getInt();
		int width = horizontal * 2 + 1;
		int tall = vertical * 2 + 1;
		int total = width * width * tall;
		int budget = Math.min(scanBudget.getInt(), total - cursor);

		BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();
		for (int step = 0; step < budget; step++) {
			int index = cursor + step;
			int x = index % width;
			int z = index / width % width;
			int y = index / (width * width);
			classify(cursorPos, scanOrigin.getX() + x - horizontal,
					scanOrigin.getY() + y - vertical, scanOrigin.getZ() + z - horizontal);
		}
		cursor += budget;

		if (cursor >= total) {
			rebuildBoxes();
			cacheOrigin = scanOrigin;
			cursor = -1;
		}
		PerfDebug.end("tick.TunnelESP.scan", start);
	}

	/** Meteor's tunnel test: stand-able, walled on one axis, open on the other. */
	private void classify(BlockPos.MutableBlockPos cursorPos, int x, int y, int z) {
		// Underground only, the same ceiling Meteor's per-chunk scan runs up to. A
		// ledge, an overhang or a gap under a roof satisfies "headroom with a
		// ceiling" perfectly well, and none of them is a tunnel.
		if (undergroundOnly.get() && y >= mc().level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)) {
			return;
		}
		if (!canStandIn(cursorPos, x, y, z)) {
			return;
		}
		Side east = side(cursorPos, x + 1, y, z);
		if (east == Side.PARTIAL) {
			return;
		}
		Side west = side(cursorPos, x - 1, y, z);
		if (west == Side.PARTIAL) {
			return;
		}
		Side south = side(cursorPos, x, y, z + 1);
		if (south == Side.PARTIAL) {
			return;
		}
		Side north = side(cursorPos, x, y, z - 1);
		if (north == Side.PARTIAL) {
			return;
		}

		boolean alongX = east == Side.OPEN && west == Side.OPEN
				&& south == Side.BLOCKED && north == Side.BLOCKED;
		boolean alongZ = south == Side.OPEN && north == Side.OPEN
				&& east == Side.BLOCKED && west == Side.BLOCKED;
		if (alongX || alongZ) {
			building.put(new Cell(x, y, z), alongX);
		}
	}

	private Side side(BlockPos.MutableBlockPos cursorPos, int x, int y, int z) {
		if (canStandIn(cursorPos, x, y, z)) {
			return Side.OPEN;
		}
		if (!passable(cursorPos, x, y, z) && !passable(cursorPos, x, y + 1, z)) {
			return Side.BLOCKED;
		}
		return Side.PARTIAL;
	}

	/** Floor underfoot, two blocks of headroom, and a ceiling on the third. */
	private boolean canStandIn(BlockPos.MutableBlockPos cursorPos, int x, int y, int z) {
		return solid(cursorPos, x, y - 1, z)
				&& passable(cursorPos, x, y, z)
				&& passable(cursorPos, x, y + 1, z)
				&& !passable(cursorPos, x, y + 2, z);
	}

	private boolean solid(BlockPos.MutableBlockPos cursorPos, int x, int y, int z) {
		BlockState state = state(cursorPos, x, y, z);
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		return !state.getCollisionShape(mc().level, cursorPos).isEmpty();
	}

	private boolean passable(BlockPos.MutableBlockPos cursorPos, int x, int y, int z) {
		BlockState state = state(cursorPos, x, y, z);
		if (state.isAir()) {
			return true;
		}
		// Water and lava are not corridor: a flooded shaft is not somewhere you walked.
		if (!state.getFluidState().isEmpty()) {
			return false;
		}
		return state.getCollisionShape(mc().level, cursorPos).isEmpty();
	}

	private BlockState state(BlockPos.MutableBlockPos cursorPos, int x, int y, int z) {
		cursorPos.set(x, y, z);
		return mc().level.getBlockState(cursorPos);
	}

	/**
	 * Filters the cells, then draws what survives.
	 *
	 * <p><b>Filtering is Meteor's rule and nothing else:</b> a cell counts when it
	 * is connected to another cell, in any of the four directions, regardless of
	 * which way either one's corridor runs. Measuring instead along a cell's
	 * <em>own</em> axis — which is what this did first — silently deletes every
	 * corner and T-junction in the world, because the cell around the bend is
	 * tagged with the other axis and stops matching. A group of two is exactly
	 * Meteor's "has a neighbour"; the setting only raises that bar.
	 *
	 * <p>Merging is ours and stays purely cosmetic: it decides how many boxes a
	 * surviving group is drawn with, never whether it survives.
	 */
	private void rebuildBoxes() {
		boxes.clear();
		Set<Cell> remaining = new HashSet<>(building.keySet());
		int minimum = minimumLength.getInt();

		while (!remaining.isEmpty()) {
			Cell seed = remaining.iterator().next();
			List<Cell> group = new ArrayList<>();
			ArrayDeque<Cell> frontier = new ArrayDeque<>();
			remaining.remove(seed);
			frontier.add(seed);
			while (!frontier.isEmpty()) {
				Cell cell = frontier.poll();
				group.add(cell);
				for (int[] step : NEIGHBOURS) {
					Cell next = cell.offset(step[0], step[1]);
					if (remaining.remove(next)) {
						frontier.add(next);
					}
				}
			}
			if (group.size() >= minimum) {
				draw(group);
			}
		}
		building.clear();
	}

	/**
	 * One group of connected cells as boxes.
	 *
	 * <p>Runs along a shared axis become a single long box, which is what a
	 * corridor looks like from the inside; a cell whose neighbours disagree about
	 * the axis — the corner itself — is simply its own box. Everything in the
	 * group is drawn either way.
	 */
	private void draw(List<Cell> group) {
		Set<Cell> pending = new HashSet<>(group);
		// Ascending order so a run is always entered from its low end.
		group.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));

		for (Cell cell : group) {
			if (!pending.remove(cell)) {
				continue; // already part of a run
			}
			boolean alongX = Boolean.TRUE.equals(building.get(cell));
			int endX = cell.x();
			int endZ = cell.z();
			if (connected.get()) {
				while (true) {
					Cell next = alongX ? new Cell(endX + 1, cell.y(), cell.z())
							: new Cell(cell.x(), cell.y(), endZ + 1);
					// Only absorb a neighbour that agrees about the direction of travel.
					Boolean nextAxis = building.get(next);
					if (nextAxis == null || nextAxis != alongX || !pending.remove(next)) {
						break;
					}
					endX = next.x();
					endZ = next.z();
				}
			}
			boxes.add(new AABB(cell.x(), cell.y(), cell.z(),
					endX + 1.0, cell.y() + height.get(), endZ + 1.0));
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

	/** What the block beside a candidate is: somewhere to walk, a wall, or neither. */
	private enum Side {
		OPEN,
		PARTIAL,
		BLOCKED
	}
}
