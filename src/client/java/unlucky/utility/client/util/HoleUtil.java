package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What counts as a hole, and how safe it is — asked once, in one place.
 *
 * <p>Four modules need this answer and they need the <em>same</em> one. A hole ESP that draws a
 * box and a HoleFill that refuses to fill it are not two features, they are one feature and one
 * bug, and the player has no way to tell which. Burrow refusing to start somewhere the ESP just
 * called safe is the same failure wearing a different hat.
 *
 * <p><b>Resistance is asked of the block, not looked up in a list.</b> The reference clients
 * both ship a hardcoded set of obsidian/bedrock/anchor/debris, which is wrong twice: it misses
 * whatever the next version adds, and it misses every modded block no matter what. Vanilla
 * already knows what survives a crystal — {@code getExplosionResistance} — and the two
 * thresholds here are the only judgement being made.
 *
 * <p>The three-way material split exists because it is a real decision at the keyboard. A
 * bedrock hole cannot be opened at all; an obsidian one can be, slowly, by someone willing to
 * spend the time; a mixed one is only as strong as its weakest face. Collapsing them into
 * "safe" throws away the thing you actually wanted to know.
 */
public final class HoleUtil {
	/**
	 * Blast resistance at or above which a block is worth standing behind.
	 *
	 * <p>600 is the ender chest, which is the weakest thing anyone actually surrounds with;
	 * obsidian, crying obsidian, respawn anchors, ancient debris, netherite blocks, anvils and
	 * enchanting tables are all 1200 and clear it comfortably. Below it are the blocks a crystal
	 * simply removes, and a hole walled with those is not a hole.
	 */
	private static final float RESISTANT = 600.0f;

	/** Bedrock's resistance. Anything at this level is not coming out by any means. */
	private static final float UNBREAKABLE = 3_600_000.0f;

	/** The four horizontal directions, in a fixed order so results are reproducible. */
	private static final Direction[] SIDES = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	/** How many floor cells a hole has, and how they sit. */
	public enum Shape {
		/** One block, walled on all four sides. */
		SINGLE,
		/** Two adjacent blocks, walled around the pair. */
		DOUBLE,
		/** A 2×2, walled around all four. */
		QUAD
	}

	/** What the floor and walls are made of, weakest-honest answer first. */
	public enum Material {
		/** Every floor and wall block is unbreakable. */
		BEDROCK,
		/** Every one survives a crystal, and at least one of them is not bedrock. */
		OBSIDIAN,
		/** A blend of bedrock and merely-resistant blocks. */
		MIXED,
		/** At least one face a crystal would simply remove. This is not a hole. */
		UNSAFE
	}

	/**
	 * The caller's idea of what makes a hole usable.
	 *
	 * @param minDepth    levels of wall, upward from the floor, that must be resistant
	 * @param minHeadroom passable blocks above the floor the player has to fit in
	 * @param allowWebs   whether a hole with a cobweb in it still counts
	 */
	public record Options(int minDepth, int minHeadroom, boolean allowWebs) {
		public static final Options DEFAULT = new Options(1, 2, false);
	}

	/**
	 * A classified hole.
	 *
	 * @param positions the floor cells, lowest-north-west first; one, two or four of them
	 * @param depth     how many levels of resistant wall it actually has, which may exceed the
	 *                  minimum asked for
	 * @param headroom  passable blocks above the floor, likewise
	 * @param webbed    whether anything in it is a cobweb
	 */
	public record Hole(Shape shape, Material material, List<BlockPos> positions, int depth,
			int headroom, boolean webbed) {
		/** Whether this is a hole worth being in at all. */
		public boolean safe() {
			return material != Material.UNSAFE;
		}

		/** The cell a player standing in this hole occupies — the first one for a multi-cell hole. */
		public BlockPos primary() {
			return positions.get(0);
		}

		public boolean contains(BlockPos pos) {
			return positions.contains(pos);
		}
	}

	private HoleUtil() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- the shared block questions ----------------------------------------

	/** Whether a crystal blast leaves this block standing. */
	public static boolean resistant(BlockState state) {
		return !state.isAir() && state.getBlock().getExplosionResistance() >= RESISTANT;
	}

	/** Whether nothing at all removes this block. */
	public static boolean unbreakable(BlockState state) {
		return state.getBlock().getExplosionResistance() >= UNBREAKABLE;
	}

	/**
	 * Whether a player can stand in this block.
	 *
	 * <p>Not the same question as {@code canBeReplaced}: a hole with a torch or a button in it
	 * is still a hole you can stand in, and one full of lava is not, even though both are
	 * "replaceable" by some measure. Collision is what a body actually cares about — plus the
	 * fluid test, because a source block occupies the space as far as survival is concerned.
	 */
	public static boolean passable(BlockPos pos) {
		BlockGetter level = mc().level;
		if (level == null) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	/** Whether anything may be placed here — the replaceability half of the shared checks. */
	public static boolean replaceable(BlockPos pos) {
		return mc().level != null && mc().level.getBlockState(pos).canBeReplaced();
	}

	/** Whether this block is a cobweb, which is passable and still a trap. */
	public static boolean web(BlockPos pos) {
		return mc().level != null && mc().level.getBlockState(pos).is(Blocks.COBWEB);
	}

	// ---- classification ----------------------------------------------------

	/**
	 * Classifies the hole whose floor cell is {@code pos}, or null if there is not one.
	 *
	 * <p>Tried narrowest-first — single, then double, then quad — because a narrower hole is
	 * strictly safer than a wider one containing it, and reporting the widest match would call
	 * a perfectly good single hole a quad the moment its neighbour happened to be open.
	 */
	public static Hole classify(BlockPos pos, Options options) {
		if (mc().level == null || pos == null) {
			return null;
		}
		Hole single = tryShape(List.of(pos.immutable()), Shape.SINGLE, options);
		if (single != null) {
			return single;
		}
		for (Direction side : SIDES) {
			Hole pair = tryShape(List.of(pos.immutable(), pos.relative(side).immutable()),
					Shape.DOUBLE, options);
			if (pair != null) {
				return pair;
			}
		}
		// Quads are named by their north-west cell so the same 2×2 is not reported four times
		// under four different origins.
		List<BlockPos> quad = List.of(pos.immutable(), pos.east().immutable(),
				pos.south().immutable(), pos.east().south().immutable());
		return tryShape(quad, Shape.QUAD, options);
	}

	/** Whether {@code pos} is a hole meeting {@code options} at all. */
	public static boolean isHole(BlockPos pos, Options options) {
		Hole hole = classify(pos, options);
		return hole != null && hole.safe();
	}

	/**
	 * The hole the player is currently standing in, or null.
	 *
	 * <p>Burrow's "only in hole" and HoleESP's "ignore own hole" are the same question and were
	 * going to be two implementations of it that disagreed at block boundaries.
	 */
	public static Hole playerHole(Options options) {
		if (mc().player == null) {
			return null;
		}
		return classify(mc().player.blockPosition(), options);
	}

	/**
	 * Every hole with a floor cell inside the given radii around {@code centre}.
	 *
	 * <p>Bounded by construction — the caller's radii are the budget, and there is no recursion
	 * or flood fill in here. Callers that scan every frame are expected to cache; this does no
	 * caching of its own precisely so that the cache can be invalidated by whoever knows which
	 * blocks changed.
	 */
	public static List<Hole> scan(BlockPos centre, int horizontalRadius, int verticalRadius,
			Options options) {
		List<Hole> found = new ArrayList<>();
		if (mc().level == null || centre == null) {
			return found;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
			for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
				for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
					cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
					// Cheap rejection before the full classification: the overwhelming majority
					// of a scan volume is solid rock or open air, and both fail here.
					if (!passable(cursor)) {
						continue;
					}
					Hole hole = classify(cursor, options);
					if (hole != null && hole.safe() && hole.primary().equals(cursor.immutable())) {
						// primary() keeps a multi-cell hole from being reported once per cell.
						found.add(hole);
					}
				}
			}
		}
		return found;
	}

	// ---- the actual test ---------------------------------------------------

	/**
	 * Whether {@code cells} form a hole of {@code shape}, and what it is made of.
	 *
	 * <p>Three independent requirements, each of which alone is not enough: the cells have to be
	 * standable to the required headroom, the floor under every one of them has to hold, and
	 * every wall position beside the group — at every level up to the required depth — has to
	 * hold too. A cell of the group is never its own wall, which is what makes a double or a
	 * quad different from two or four singles that all fail.
	 */
	private static Hole tryShape(List<BlockPos> cells, Shape shape, Options options) {
		int headroom = Integer.MAX_VALUE;
		boolean webbed = false;
		for (BlockPos cell : cells) {
			int free = freeAbove(cell, options.minHeadroom());
			if (free < options.minHeadroom()) {
				return null;
			}
			headroom = Math.min(headroom, free);
			if (webInColumn(cell, options.minHeadroom())) {
				webbed = true;
			}
		}
		if (webbed && !options.allowWebs()) {
			return null;
		}

		boolean anyBedrock = false;
		boolean anyPlain = false;
		for (BlockPos cell : cells) {
			BlockState floor = mc().level.getBlockState(cell.below());
			if (!resistant(floor)) {
				return null;
			}
			anyBedrock |= unbreakable(floor);
			anyPlain |= !unbreakable(floor);
		}

		int depth = Integer.MAX_VALUE;
		for (BlockPos cell : cells) {
			for (Direction side : SIDES) {
				BlockPos wallBase = cell.relative(side);
				if (cells.contains(wallBase)) {
					continue; // a neighbouring cell of the same hole, not a wall
				}
				int levels = 0;
				while (levels < options.minDepth()) {
					BlockState wall = mc().level.getBlockState(wallBase.above(levels));
					if (!resistant(wall)) {
						break;
					}
					anyBedrock |= unbreakable(wall);
					anyPlain |= !unbreakable(wall);
					levels++;
				}
				if (levels < options.minDepth()) {
					return null;
				}
				depth = Math.min(depth, levels);
			}
		}

		Material material = anyBedrock && anyPlain ? Material.MIXED
				: anyBedrock ? Material.BEDROCK : Material.OBSIDIAN;
		return new Hole(shape, material, canonical(cells), depth, headroom, webbed);
	}

	/**
	 * Sorts the cells into one fixed order, so the same hole has the same {@link Hole#primary()}
	 * whichever of its cells it was found from.
	 *
	 * <p>This is what stops a double hole being reported twice by a scan — once from each end —
	 * and it is why callers may use {@code primary()} as the hole's identity.
	 */
	private static List<BlockPos> canonical(List<BlockPos> cells) {
		if (cells.size() == 1) {
			return cells;
		}
		List<BlockPos> sorted = new ArrayList<>(cells);
		sorted.sort((a, b) -> {
			int y = Integer.compare(a.getY(), b.getY());
			if (y != 0) {
				return y;
			}
			int x = Integer.compare(a.getX(), b.getX());
			return x != 0 ? x : Integer.compare(a.getZ(), b.getZ());
		});
		return List.copyOf(sorted);
	}

	/** Passable blocks starting at {@code cell}, counted no further than {@code wanted}. */
	private static int freeAbove(BlockPos cell, int wanted) {
		int free = 0;
		while (free < wanted && passable(cell.above(free))) {
			free++;
		}
		return free;
	}

	private static boolean webInColumn(BlockPos cell, int height) {
		for (int level = 0; level < height; level++) {
			if (web(cell.above(level))) {
				return true;
			}
		}
		return false;
	}
}
