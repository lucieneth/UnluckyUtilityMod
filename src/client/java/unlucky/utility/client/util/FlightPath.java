package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a way to fly from one block to another around whatever is in between.
 *
 * <p>Built because straight-line flight plus "give up when you stop moving" is not
 * obstacle avoidance. A single block or an outside corner stopped travel dead, when
 * clearing it needs nothing more than going up one and over — the same thing a player
 * does without thinking. Cruising over the top of the whole build instead is worse: on a
 * seventy-block statue it flew away from a target fifteen blocks off for hundreds of
 * ticks.
 *
 * <p>So: an actual search. A* over the cells the player's body fits in, six-connected,
 * with Manhattan distance as the heuristic — exact for unit steps on this lattice, so the
 * path found is the shortest one, not a lucky one.
 *
 * <p>Two things keep it honest about cost. The search is capped at a fixed number of
 * expansions, and when the cap runs out it returns the route to the closest cell it
 * reached rather than nothing, so a long or awkward trip still makes progress and gets
 * re-planned from further along. And the result is smoothed before use: any run of
 * waypoints that can be replaced by one clear straight line is, which turns the lattice
 * staircase into the diagonal a player would actually fly and cuts the number of
 * waypoints to steer through.
 */
public final class FlightPath {
	private FlightPath() {
	}

	/** Expansions before the search settles for the best cell it has found. */
	public static final int DEFAULT_BUDGET = 4000;

	private record Node(long pos, double priority) {
	}

	/**
	 * Waypoints from {@code start} to {@code goal}, smoothed, excluding the start.
	 *
	 * <p>Empty when already there or when the start itself is not a cell the player fits
	 * in — that second case is not failure, it just means steering has nothing useful to
	 * say and the caller should fall back to flying straight.
	 */
	public static List<BlockPos> find(BlockPos start, BlockPos goal, int budget) {
		if (start.equals(goal) || !fits(start)) {
			return List.of();
		}
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::priority));
		Map<Long, Long> cameFrom = new HashMap<>();
		Map<Long, Integer> cost = new HashMap<>();
		long from = start.asLong();
		long destination = goal.asLong();
		cost.put(from, 0);
		open.add(new Node(from, heuristic(start, goal)));

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos step = new BlockPos.MutableBlockPos();
		long closest = from;
		double closestScore = heuristic(start, goal);
		int expansions = 0;

		while (!open.isEmpty() && expansions++ < budget) {
			long current = open.poll().pos();
			if (current == destination) {
				return smooth(rebuild(cameFrom, from, current));
			}
			cursor.set(current);
			int here = cost.getOrDefault(current, Integer.MAX_VALUE);
			for (Direction direction : Direction.values()) {
				step.setWithOffset(cursor, direction);
				if (!fits(step)) {
					continue;
				}
				long next = step.asLong();
				if (here + 1 >= cost.getOrDefault(next, Integer.MAX_VALUE)) {
					continue;
				}
				cost.put(next, here + 1);
				cameFrom.put(next, current);
				double remaining = heuristic(step, goal);
				open.add(new Node(next, here + 1 + remaining));
				// remember the nearest miss, so running out of budget still buys progress
				if (remaining < closestScore) {
					closestScore = remaining;
					closest = next;
				}
			}
		}
		return closest == from ? List.of() : smooth(rebuild(cameFrom, from, closest));
	}

	/** Manhattan distance: the exact step count between two cells with nothing in the way. */
	private static double heuristic(BlockPos from, BlockPos to) {
		return Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY())
				+ Math.abs(from.getZ() - to.getZ());
	}

	private static List<BlockPos> rebuild(Map<Long, Long> cameFrom, long start, long end) {
		List<BlockPos> route = new ArrayList<>();
		for (long at = end; at != start; ) {
			route.add(BlockPos.of(at));
			Long previous = cameFrom.get(at);
			if (previous == null) {
				break; // shouldn't happen, but never spin on a broken chain
			}
			at = previous;
		}
		Collections.reverse(route);
		return route;
	}

	/**
	 * Drops every waypoint that can be flown past.
	 *
	 * <p>Walks forward from each kept waypoint to the furthest one still reachable in a
	 * clear straight line. Without this the route is a six-connected staircase and
	 * steering wobbles along every tread of it.
	 */
	private static List<BlockPos> smooth(List<BlockPos> route) {
		if (route.size() < 2) {
			return route;
		}
		List<BlockPos> smoothed = new ArrayList<>();
		int index = 0;
		while (index < route.size()) {
			int furthest = index;
			for (int candidate = route.size() - 1; candidate > index; candidate--) {
				if (clearBetween(route.get(index), route.get(candidate))) {
					furthest = candidate;
					break;
				}
			}
			// no shortcut from here: keep the next waypoint and carry on from it
			int keep = furthest == index ? index + 1 : furthest;
			if (keep >= route.size()) {
				break;
			}
			smoothed.add(route.get(keep));
			index = keep;
		}
		if (smoothed.isEmpty() || !smoothed.get(smoothed.size() - 1).equals(route.get(route.size() - 1))) {
			smoothed.add(route.get(route.size() - 1));
		}
		return smoothed;
	}

	/**
	 * Whether the player could fly straight from one cell to the other.
	 *
	 * <p>Sampled rather than raycast: a ray is a line but the player is a box, and a ray
	 * threads gaps a body would catch on.
	 *
	 * <p><b>The samples are tested where the body will really be</b> — at the fractional
	 * point on the segment, not at the block it floors into. Flooring was a bug worth
	 * spelling out: halfway up a diagonal the feet are at, say, y 73.5 and the body spans
	 * 73.5 to 75.3, so it occupies three block layers; testing a body at y 73 instead spans
	 * 73 to 74.8 and misses the top one entirely. The effect is a shortcut that reads as
	 * clear while the flight along it clips the block above — the path offset down into
	 * the blocks.
	 */
	private static boolean clearBetween(BlockPos from, BlockPos to) {
		Vec3 start = Vec3.atBottomCenterOf(from);
		Vec3 end = Vec3.atBottomCenterOf(to);
		// finer than the body is wide, so a single block cannot be stepped over unseen
		int samples = (int) Math.ceil(start.distanceTo(end) / 0.25);
		for (int i = 1; i <= samples; i++) {
			if (!fitsAt(start.lerp(end, (double) i / samples))) {
				return false;
			}
		}
		return true;
	}

	/** Whether the player's body fits standing in the middle of this block. */
	public static boolean fits(BlockPos feet) {
		return fitsAt(Vec3.atBottomCenterOf(feet));
	}

	/**
	 * Whether the player's body fits with its feet at this exact point.
	 *
	 * <p>This is the "which blocks would I occupy, and is any of them solid" test: the
	 * real hitbox, at a real position, against the world's own collision shapes — so
	 * slabs, stairs, fences and open trapdoors all answer correctly without special
	 * cases.
	 */
	public static boolean fitsAt(Vec3 feet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return false;
		}
		AABB box = mc.player.getBoundingBox();
		double half = box.getXsize() / 2.0;
		return mc.level.noCollision(mc.player, new AABB(
				feet.x - half, feet.y, feet.z - half,
				feet.x + half, feet.y + box.getYsize(), feet.z + half));
	}
}
