package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.world.item.Item;

/**
 * What the printer is about to spend, in the order it will spend it.
 *
 * <p>A restock is only as good as its shopping list, and "what does this band need in
 * total" is the wrong list. A band on a map art is tens of thousands of blocks; no bag
 * holds it, so the total says nothing about <em>which</em> materials to bring back. The
 * question that matters is narrower and answerable: <b>flying the route from here, which
 * block runs out first, and how far do we get before it does?</b> Everything here exists
 * to answer that, and to answer the follow-up — given N slots to fill, what mix carries
 * us furthest?
 *
 * <p>The route order is what makes it a forecast rather than a tally. Work is bucketed by
 * the lane waypoint that will reach it, so consumption is a sequence, not a heap: two
 * stacks of light-blue that are all spent in the first lane and two stacks spread over the
 * whole band are the same number and completely different facts.
 *
 * <p><b>Why the old allocator could not get this right.</b> It gave the commonest block
 * half the bag and every other colour a flat two stacks. That is a guess about a
 * distribution it never looked at: a colour that happens to carpet the next three lanes
 * got its two stacks and ran out immediately, and the printer stopped again with the bag
 * still four-fifths full. Filling in proportion to what the route actually eats is the
 * whole difference between a stop every minute and a stop every twenty.
 */
public final class MaterialForecast {
	/**
	 * A stretch of route spending one material.
	 *
	 * <p>Runs rather than one entry per block: a lane over a map art lays the same colour
	 * for dozens of positions at a time, so this compresses a 16k-block band to a few
	 * hundred entries and keeps the prefix walks below cheap enough to do every tick.
	 */
	public record Run(Item item, int count, int waypoint) {
	}

	/** An empty forecast — no route, so nothing predicted. */
	public static final MaterialForecast NONE = new MaterialForecast(List.of());

	private final List<Run> runs;
	private final int total;

	private MaterialForecast(List<Run> runs) {
		this.runs = runs;
		int sum = 0;
		for (Run run : runs) {
			sum += run.count();
		}
		this.total = sum;
	}

	public static MaterialForecast of(List<Run> runs) {
		return runs.isEmpty() ? NONE : new MaterialForecast(List.copyOf(runs));
	}

	/** Blocks the forecast covers in all. */
	public int size() {
		return total;
	}

	public boolean isEmpty() {
		return total == 0;
	}

	public List<Run> runs() {
		return runs;
	}

	/**
	 * The same forecast with everything before {@code waypoint} dropped — what is still
	 * ahead once part of the route has been flown.
	 *
	 * <p>Cheaper and steadier than rescanning: the plan already decided what each waypoint
	 * lays, so advancing along it is a matter of forgetting the front of the list. A
	 * mid-route restock asks "what do I need <em>from here</em>", which is a different and
	 * much smaller question than what the band needed when the pass began.
	 */
	public MaterialForecast from(int waypoint) {
		if (waypoint <= 0 || runs.isEmpty()) {
			return this;
		}
		List<Run> ahead = new ArrayList<>();
		for (Run run : runs) {
			if (run.waypoint() >= waypoint) {
				ahead.add(run);
			}
		}
		return ahead.size() == runs.size() ? this : of(ahead);
	}

	/** Everything the forecast spends, item to count — the tail end of a plan, not a bag size. */
	public Map<Item, Integer> totals() {
		Map<Item, Integer> sum = new HashMap<>();
		for (Run run : runs) {
			sum.merge(run.item(), run.count(), Integer::sum);
		}
		return sum;
	}

	/**
	 * How many blocks of the route {@code carried} sees through before something runs dry.
	 *
	 * <p>This is the number the whole design turns on. It is <em>not</em> the total carried:
	 * a bag with 2000 blocks in it covers nothing if the very next run wants a colour it
	 * holds none of. Reported to the HUD so the prediction is visible rather than implied —
	 * a forecast you cannot see is a forecast you cannot tell is wrong.
	 */
	public int coverage(Map<Item, Integer> carried) {
		return coverage(carried, item -> true);
	}

	/**
	 * As {@link #coverage(Map)}, but blocks made of something {@code fixable} rejects are
	 * counted as covered rather than treated as a wall.
	 *
	 * <p>Running out of a material no shulker holds is not a reason to go to the base. The
	 * printer does not stop at such a position, it skips it and flies on, so modelling it as
	 * the end of the route both understates how far the bag stretches and — far worse —
	 * leaves the trigger permanently satisfied, sending the printer off to refill again and
	 * again for a shortage no refill can touch. Measured over the materials a trip could
	 * actually change, the number means what the trigger needs it to mean.
	 */
	public int coverage(Map<Item, Integer> carried, Predicate<Item> fixable) {
		Map<Item, Integer> left = new HashMap<>(carried);
		int done = 0;
		for (Run run : runs) {
			if (!fixable.test(run.item())) {
				done += run.count(); // skipped by the printer, not blocking on
				continue;
			}
			int have = left.getOrDefault(run.item(), 0);
			if (have < run.count()) {
				return done + have;
			}
			left.put(run.item(), have - run.count());
			done += run.count();
		}
		return done;
	}

	/** The material that ends the run in {@link #coverage}, or null when the route is covered. */
	public Item firstShortfall(Map<Item, Integer> carried) {
		return firstShortfall(carried, item -> true);
	}

	/** As {@link #firstShortfall(Map)}, over the materials {@code fixable} admits. */
	public Item firstShortfall(Map<Item, Integer> carried, Predicate<Item> fixable) {
		Map<Item, Integer> left = new HashMap<>(carried);
		for (Run run : runs) {
			if (!fixable.test(run.item())) {
				continue;
			}
			int have = left.getOrDefault(run.item(), 0);
			if (have < run.count()) {
				return run.item();
			}
			left.put(run.item(), have - run.count());
		}
		return null;
	}

	/**
	 * What to fetch: the mix of at most {@code slots} inventory slots that carries the
	 * printer as far along this route as any mix could.
	 *
	 * <p>Stated as an optimisation because that is what it is. Coverage is monotone in what
	 * you carry and the slots a target costs is monotone in the target, so "the furthest
	 * point reachable within the budget" can be found by bisection on the route itself:
	 * pick a distance, ask what covering it costs, keep the furthest distance that fits.
	 * The mix falls out of the answer — no per-material rules, no base-block-versus-accent
	 * split, nothing to tune. A colour laid twice as fast gets twice as much because the
	 * route says so.
	 *
	 * @param carried     what is already in the bag, loose
	 * @param partialRoom spare space in stacks already held, which costs no new slot
	 * @param slots       new slots the caller is willing to fill
	 * @param obtainable  materials a refill could actually get hold of; the rest are
	 *                    treated as free, since being short of something you cannot fetch
	 *                    is not a reason to stop planning around everything else
	 */
	public Map<Item, Integer> fill(Map<Item, Integer> carried, Map<Item, Integer> partialRoom,
			int slots, Predicate<Item> obtainable) {
		if (runs.isEmpty() || slots <= 0) {
			return Map.of();
		}
		// Reserved before the bisection, not after. The bisection maximises how far up the
		// route it can provision, so it spends every slot it is given and there is never
		// anything left at the end — a colour wanting six was fetched as nothing at all while
		// six hundred slots' worth of the front of the route went in ahead of it.
		Map<Item, Integer> small = finishSmall(carried, partialRoom, slots / 4, obtainable);
		int budget = slots - costOf(small, partialRoom);

		// Bisect on how far up the route to provision for. costOf is non-decreasing in the
		// distance, so the largest affordable distance is exactly what a binary search finds.
		int low = 0;
		int high = total;
		Map<Item, Integer> best = Map.of();
		while (low <= high) {
			int mid = (low + high) >>> 1;
			Map<Item, Integer> need = shortfallOver(mid, carried, obtainable);
			if (costOf(need, partialRoom) <= budget) {
				best = need;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		// No speculative padding. There used to be a pass here that spent every slot left over
		// on more of the bulk material, on the theory that a trip with room to spare is a trip
		// half wasted. It is the opposite: a route wanting its last 109 cobblestone came home
		// with 2,029, because thirty spare slots were filled with a material nothing had asked
		// for. That is what silts up a bag, and a silted bag is what makes the next trip
		// fetch ten of something. topUp already fills the slots a fetch is being charged for,
		// capped by demand that actually exists, which is the whole of the honest answer.
		Map<Item, Integer> filled = new LinkedHashMap<>(topUp(best, carried, partialRoom));
		// The prefix may have reached some of them anyway; take whichever answer is larger so
		// a reserved colour is never fetched twice or downgraded.
		small.forEach((item, count) -> filled.merge(item, count, Math::max));
		return filled;
	}

	/**
	 * The materials this trip can finish outright, cheapest kind first.
	 *
	 * <p>The bisection buys a <em>prefix</em> of the route, which is right for how much to
	 * bring of the bulk and wrong for the tail. A colour wanting six hundred and one wanting
	 * six are not the same problem: the six cost a single slot, and leaving them behind buys
	 * a whole supply run later to fetch them. A band's tail is full of these — six yellow,
	 * three pink, eleven blue — and they are exactly the trips that came home with twenty
	 * blocks in them.
	 *
	 * <p>Not the speculative padding this class removed once before, and the difference is
	 * the whole point: every entry is capped at demand that already exists, and is taken only
	 * when the <b>whole</b> of what is left fits in one slot. It cannot fetch more of
	 * anything than the route asks for. The reservation is capped at a quarter of the bag so
	 * a schematic made of two hundred trace colours cannot crowd out the material the pass is
	 * actually flying to lay.
	 */
	private Map<Item, Integer> finishSmall(Map<Item, Integer> carried,
			Map<Item, Integer> partialRoom, int slotCap, Predicate<Item> obtainable) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		if (slotCap <= 0) {
			return out;
		}
		Map<Item, Integer> totals = totals();
		int spent = 0;
		for (Run run : runs) { // route order, so the earliest unfinished colour goes first
			Item item = run.item();
			if (out.containsKey(item) || !obtainable.test(item)) {
				continue;
			}
			int need = totals.getOrDefault(item, 0) - carried.getOrDefault(item, 0);
			if (need <= 0) {
				continue;
			}
			int cost = costOf(Map.of(item, need), partialRoom);
			if (cost != 1 || spent + cost > slotCap) {
				continue; // not finishable in a slot, or the reservation is full
			}
			out.put(item, need);
			spent += cost;
		}
		return out;
	}

	/**
	 * Fills every slot the fetch has already paid for.
	 *
	 * <p>A slot costs a slot whether it comes home with two carpets in it or sixty-four. The
	 * bisection asks for exactly what the prefix needs, which was right about <em>which</em>
	 * materials to bring and badly wrong about how much: a real run came back with a whole
	 * inventory slot holding two light blue carpet, and six more like it. Rounding each entry
	 * up to the slots it is already being charged for is therefore free — {@link #costOf}
	 * gives the same answer before and after — and it is the difference between a trip that
	 * carries 822 blocks and one that carries the 1152 the same eighteen slots can hold.
	 *
	 * <p>Capped by what the route will ever use, so this tops slots up rather than inventing
	 * demand: there is no point coming home with a stack of a colour the schematic wants five of.
	 */
	private Map<Item, Integer> topUp(Map<Item, Integer> need, Map<Item, Integer> carried,
			Map<Item, Integer> partialRoom) {
		if (need.isEmpty()) {
			return need;
		}
		Map<Item, Integer> totals = totals();
		Map<Item, Integer> filled = new LinkedHashMap<>();
		for (Map.Entry<Item, Integer> entry : need.entrySet()) {
			Item item = entry.getKey();
			int want = entry.getValue();
			int room = partialRoom.getOrDefault(item, 0);
			int stack = stackSize(item);
			int fresh = Math.max(0, want - room);
			// exactly the space the slots already being charged for can hold
			int capacity = room + ((fresh + stack - 1) / stack) * stack;
			int ceiling = Math.max(0, totals.getOrDefault(item, 0) - carried.getOrDefault(item, 0));
			filled.put(item, Math.max(want, Math.min(capacity, ceiling)));
		}
		return filled;
	}

	/**
	 * What the bag is short of to lay the first {@code blocks} of the route.
	 *
	 * <p>Materials no refill can supply are skipped rather than counted: they cap how far
	 * the printer gets whatever we do, and letting them inflate the shortfall would spend
	 * the search budget provisioning for a distance no purchase can buy.
	 */
	private Map<Item, Integer> shortfallOver(int blocks, Map<Item, Integer> carried,
			Predicate<Item> obtainable) {
		Map<Item, Integer> need = new HashMap<>();
		int seen = 0;
		for (Run run : runs) {
			if (seen >= blocks) {
				break;
			}
			int take = Math.min(run.count(), blocks - seen);
			seen += take;
			if (obtainable.test(run.item())) {
				need.merge(run.item(), take, Integer::sum);
			}
		}
		Map<Item, Integer> shortfall = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : need.entrySet()) {
			int missing = entry.getValue() - carried.getOrDefault(entry.getKey(), 0);
			if (missing > 0) {
				shortfall.put(entry.getKey(), missing);
			}
		}
		return shortfall;
	}

	/** Slots a fetch costs, after the space left in stacks already carried is used up. */
	private static int costOf(Map<Item, Integer> need, Map<Item, Integer> partialRoom) {
		int slots = 0;
		for (Map.Entry<Item, Integer> entry : need.entrySet()) {
			int fresh = entry.getValue() - partialRoom.getOrDefault(entry.getKey(), 0);
			if (fresh > 0) {
				slots += (fresh + stackSize(entry.getKey()) - 1) / stackSize(entry.getKey());
			}
		}
		return slots;
	}

	private static int stackSize(Item item) {
		return Math.max(1, item.getDefaultInstance().getMaxStackSize());
	}

	/**
	 * Collects positions into runs in route order.
	 *
	 * <p>The builder is here rather than in the caller so the ordering rule lives with the
	 * thing that depends on it: the forecast is only meaningful if "earlier in the list"
	 * really does mean "laid sooner", and that is a property of how the list was built.
	 */
	public static final class Builder {
		private final List<Run> runs = new ArrayList<>();

		/**
		 * Adds one position's material, extending the open run when it matches.
		 *
		 * <p>Must be called in the order the route lays them.
		 */
		public void add(Item item, int waypoint) {
			if (item == null) {
				return;
			}
			if (!runs.isEmpty()) {
				Run last = runs.get(runs.size() - 1);
				if (last.item() == item && last.waypoint() == waypoint) {
					runs.set(runs.size() - 1, new Run(item, last.count() + 1, waypoint));
					return;
				}
			}
			runs.add(new Run(item, 1, waypoint));
		}

		/**
		 * Appends bulk demand with no route position of its own — the bands above this one.
		 *
		 * <p>Lookahead is the fix for the refill that tops up for a band about to finish and
		 * is dry again one band later, which on a staircased map art is every other colour
		 * changing at once. It goes on the tail with a waypoint past the end of the lane, so
		 * it is provisioned for only once the current route is fully covered and never
		 * competes with work the printer is about to reach.
		 */
		public void addAhead(Map<Item, Integer> demand, int waypoint) {
			for (Map.Entry<Item, Integer> entry : demand.entrySet()) {
				if (entry.getValue() > 0) {
					runs.add(new Run(entry.getKey(), entry.getValue(), waypoint));
				}
			}
		}

		public MaterialForecast build() {
			return of(runs);
		}
	}
}
