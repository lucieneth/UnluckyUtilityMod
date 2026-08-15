package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.modules.player.AutoEat;

/**
 * Refilling from shulker boxes you are carrying — the survival map-art workflow, where
 * the stock is in your bags rather than in a chest you flew from.
 *
 * <p>One cycle is <b>place → open → pull → close → break → collect → recover</b>, one step
 * per tick, driven by whoever owns the job (the Printer) handing it a {@link MaterialForecast}.
 *
 * <p>Two invariants make this better than doing it by hand rather than worse, and both are
 * enforced by construction rather than checked at the end:
 * <ul>
 * <li><b>The box comes back.</b> A shulker placed and not recovered is a real loss — not of
 *     a block, but of everything inside it. So the pull stops while one inventory slot is
 *     still empty (the slot the box will land in), the cycle does not leave the spot until
 *     the drop is <em>observed</em> to be gone, a box that could not be broken is remembered
 *     and fetched back before anything else is allowed to happen, and a spot is only chosen
 *     if a drop there cannot fall out of reach.
 * <li><b>Stock for the route, not for the click.</b> The caller's forecast describes the work
 *     ahead in the order it will be done, so one stop refills for a long stretch and the mix
 *     is whatever carries the printer furthest. Pulling a stack at a time would mean stopping
 *     every minute, which is the thing this exists to avoid.
 * </ul>
 */
public final class ShulkerRestock {
	/** Where in the cycle we are. */
	public enum Stage {
		IDLE, PLATFORM, LAND, PLACE, OPEN, PULL, CLOSE, BREAK, COLLECT, RECOVER, CLEANUP
	}

	/** Ticks a stage may take before the cycle is abandoned and the caller told. */
	private static final int STAGE_TIMEOUT = 100;
	/** Mining a box by hand is inherently slow, so BREAK and CLEANUP get their own budget. */
	private static final int MINE_TIMEOUT = 300;
	/**
	 * How long to chase a dropped box before admitting it is gone.
	 *
	 * <p>Generous on purpose. The old code waited ten ticks flat, which is <em>exactly</em>
	 * the pickup delay vanilla stamps on a block drop ({@code setDefaultPickUpDelay}), so the
	 * item became collectable on the very tick we stopped waiting for it — and the platform
	 * was then mined out from under it. Every shulker lost to "it just vanished" came through
	 * that gap.
	 */
	private static final int COLLECT_TIMEOUT = 200;
	/** Ticks between container clicks, so a server sees a human cadence. */
	private static final int PULL_DELAY = 2;
	/** Quiet spell after any cycle, so refills cannot chain one into the next. */
	private static final int CYCLE_COOLDOWN = 200;
	/** How long to wait before re-asking a question that just answered "nothing to fetch". */
	private static final int DECLINE_COOLDOWN = 40;

	private Stage stage = Stage.IDLE;
	private int stageTicks;
	private int pullDelay;
	/** Where our box is standing, once placed. */
	private BlockPos boxPos;
	/** Where the box will stand, and where we stand while working it. */
	private BlockPos boxSpot;
	/** Ground beside the box: we work it from here, never from on top of it. */
	private BlockPos standSpot;
	/**
	 * A box we placed and could not get back, kept until we have.
	 *
	 * <p>The single most expensive bug this class can have. Previously a BREAK that ran out
	 * of time went to IDLE with the box still standing in the world and nothing remembering
	 * it — a full shulker, gone, silently. Now the position survives the failure and no new
	 * cycle may start until it has been collected.
	 */
	private BlockPos orphan;
	/** True while the cycle exists only to fetch {@link #orphan} back. */
	private boolean rescue;
	/** True only during the final, verified short descent onto a refill stand spot. */
	private boolean protectedLanding;
	/** Blocks we placed purely to stand the box on, broken again afterwards. */
	private final List<BlockPos> pad = new ArrayList<>();
	/** Pad positions still to place before the box can go down. */
	private final List<BlockPos> padTodo = new ArrayList<>();
	/** The way to the base, and how far along it we are. Empty means fly straight. */
	private List<BlockPos> path = List.of();
	private int pathIndex;
	/** Closest we have come to the current land target; the deadline resets whenever it improves. */
	private double landClosest = Double.MAX_VALUE;
	/** Positions the build owns — a base may never sit on one. */
	private java.util.function.Predicate<BlockPos> forbidden = pos -> false;
	/** A refill spot the user picked, tried before searching. Null when unset. */
	private BlockPos preferred;
	/** Hotbar slot the box was selected in, restored afterwards. */
	private int previousSlot = -1;
	/** What this cycle is trying to fetch. */
	private Map<Item, Integer> want = Map.of();
	/** How much of each the space allows — the forecast's own answer, not a heuristic. */
	private Map<Item, Integer> budget = Map.of();
	/** Taken so far this cycle, so the shares stay honest across ticks. */
	private final Map<Item, Integer> taken = new HashMap<>();
	/** Shulkers held when the current box went down, so its return can be checked. */
	private int boxesAtPlace;
	private String status = "idle";
	private String lastProblem = "";
	/** Cycles that ended badly in a row; enough of them and we stop trying for a while. */
	private int failures;
	/** Ticks left before another attempt is allowed after repeated failure. */
	private int backoff;
	/** Ticks left of the quiet spell after any finished cycle. */
	private int cooldown;
	/** Set for one tick when something worth telling the user happened. */
	private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
	/** Said once: mining boxes with bare hands is slow enough to time out. */
	private boolean toolWarned;
	/** What a pack cycle is putting away; empty for the ordinary fetch. */
	private Map<Item, Integer> stowing = Map.of();
	/** The stack a pack last pushed, so a move that did nothing can be told from one that worked. */
	private int pushSlot = -1;
	private ItemStack pushStack = ItemStack.EMPTY;
	private final java.util.Set<Integer> pushStuck = new java.util.HashSet<>();

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public Stage stage() {
		return stage;
	}

	public boolean busy() {
		return stage != Stage.IDLE;
	}

	/** True while we are mining something of our own — the box, or the pad. */
	public boolean mining() {
		return stage == Stage.BREAK || stage == Stage.CLEANUP;
	}

	/** Whether a box of ours is stranded in the world. Worth surfacing: it holds your materials. */
	public boolean hasStrandedBox() {
		return orphan != null;
	}

	/**
	 * The stages that work the box in place, as opposed to travelling to it or chasing its
	 * drop — the ones where holding station beside the box is the right thing to do.
	 */
	private boolean working() {
		return stage != Stage.IDLE && stage != Stage.LAND && stage != Stage.PLATFORM
				&& stage != Stage.COLLECT;
	}

	/**
	 * Lands the player beside the box and keeps them standing there to mine it.
	 *
	 * <p>Flight comes off only once the player is truly at the spot — over the block they
	 * land on, feet at its floor. Cutting it a moment early, still out over the open air a
	 * floating build hangs above, is exactly what dropped the player two dozen blocks to the
	 * ground far below and stranded the box up top. And if a landed player ever slips off an
	 * edge, flight snaps back on and eases them onto the spot, so a refill can never fall.
	 */
	/**
	 * Puts the player on the ground beside the box, or holds them hovering until it is safe to.
	 *
	 * <p><b>Landing is not cosmetic.</b> Vanilla multiplies mining time by five for a player
	 * who is not on the ground, so a hovering refill spends five times as long on every box —
	 * which on a print that opens a box every couple of minutes is most of the saving gone.
	 * Standing up is worth real time.
	 *
	 * <p>What made landing dangerous was never the landing. It was a spot chosen twenty-four
	 * blocks below the build, and an {@code abilities.flying} flag the server was never told
	 * about — so it counted the whole descent as free fall and charged it the moment the
	 * player touched down. The dive is gone and the flag is now sent, so the remaining rule is
	 * simply this: <b>only ever cut flight with solid ground within about a block of the
	 * feet</b>, which is a drop that cannot hurt. Anywhere else — over a built pad, over open
	 * air — it hovers and mines slowly rather than falling quickly.
	 */
	private void settleAt(BlockPos spot) {
		Vec3 here = mc().player.position();
		double dx = here.x - (spot.getX() + 0.5);
		double dz = here.z - (spot.getZ() + 0.5);
		// Land only when actually *centred*, not merely somewhere over the block. The old
		// tolerance was 0.6 and the player is 0.6 wide, so touching down two-thirds of the way
		// across the stand spot left a shoulder inside the box's space — and since a landed
		// player is then frozen, it stood there overlapping for ever while the placement was
		// refused every time. Hovering until centred costs a few ticks and cannot deadlock.
		boolean centred = dx * dx + dz * dz < CENTRED * CENTRED;
		if (centred && groundGap(here) <= SAFE_DROP) {
			// The server may still have fall distance left from the flight into this spot.
			// During this one-block, centred descent LocalPlayerMixin reports ground so that
			// distance is retired server-side before the mining stance begins.
			protectedLanding = !mc().player.onGround();
			flyOff(); // ground is right there: stand up and mine at full speed
			Vec3 velocity = mc().player.getDeltaMovement();
			mc().player.setDeltaMovement(0.0, Math.min(velocity.y, 0.0), 0.0);
			return;
		}
		protectedLanding = false;
		flyOn();
		holdAt(spot);
	}

	/** Whether the outgoing movement packet may protect the controlled final landing. */
	public boolean protectsLanding() {
		if (mc().player == null || mc().player.onGround()) {
			protectedLanding = false;
		}
		return protectedLanding;
	}

	/** Blocks of air between the feet and whatever is under them, up to a few. */
	private double groundGap(Vec3 feet) {
		if (mc().player.onGround()) {
			return 0.0;
		}
		BlockPos at = BlockPos.containing(feet.x, feet.y, feet.z);
		for (int drop = 1; drop <= SAFE_DROP + 2; drop++) {
			if (mc().level.getBlockState(at.below(drop)).isSolidRender()) {
				return feet.y - (at.getY() - drop + 1);
			}
		}
		return Double.MAX_VALUE;
	}

	/**
	 * Turns flight on and <b>tells the server</b>, but only when something changed.
	 *
	 * <p>Writing {@code abilities.flying = true} is a client-side note to self. Until an
	 * abilities packet goes out the server still believes the player is falling, and bills the
	 * landing accordingly — which is how a printer that never appeared to fall could still die
	 * of fall damage. Guarded on change so re-asserting it every tick costs no packets.
	 */
	private void flyOn() {
		protectedLanding = false;
		net.minecraft.world.entity.player.Abilities abilities = mc().player.getAbilities();
		if (abilities.mayfly && abilities.flying) {
			return;
		}
		abilities.mayfly = true;
		abilities.flying = true;
		mc().player.onUpdateAbilities();
	}

	/**
	 * Puts the player down, telling the server so the landing is not charged as a fall.
	 *
	 * <p>The counterpart to {@link #flyOn}, and the same rule: guarded on change, so a landed
	 * player standing still sends nothing.
	 */
	private void flyOff() {
		net.minecraft.world.entity.player.Abilities abilities = mc().player.getAbilities();
		if (!abilities.flying) {
			return;
		}
		abilities.flying = false;
		mc().player.onUpdateAbilities();
	}

	/** Flies the player toward {@code spot} at a gentle speed, stopping when on it. */
	private void holdAt(BlockPos spot) {
		Vec3 here = mc().player.position();
		Vec3 step = Vec3.atBottomCenterOf(spot).subtract(here);
		double speed = 0.2;
		mc().player.setDeltaMovement(step.lengthSqr() <= 0.04
				? Vec3.ZERO
				: step.lengthSqr() <= speed * speed ? step : step.normalize().scale(speed));
	}

	/** True while a container we asked for is opening — the silent-screen gate. */
	public boolean expectingOpen() {
		return stage == Stage.OPEN || stage == Stage.PULL || stage == Stage.CLOSE;
	}

	/** One line of the fetch plan: how much of an item we have taken this cycle, of how much we mean to. */
	public record Fetch(Item item, int got, int want) {
	}

	/**
	 * The current cycle's shopping list with progress, largest first; empty when idle.
	 *
	 * <p>Straight off {@link #budget} and {@link #taken}, so a widget can show exactly what
	 * a refill is fetching and how far along it is without keeping its own count.
	 */
	public List<Fetch> fetching() {
		if (!busy() || budget.isEmpty()) {
			return List.of();
		}
		List<Fetch> list = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : budget.entrySet()) {
			list.add(new Fetch(entry.getKey(), taken.getOrDefault(entry.getKey(), 0), entry.getValue()));
		}
		list.sort((a, b) -> Integer.compare(b.want(), a.want()));
		return list;
	}

	public String status() {
		return status;
	}

	/**
	 * A refill spot chosen by the player, tried before any search.
	 *
	 * <p>A known base beats a found one on a long print: it is somewhere you have
	 * already decided is safe and out of the way, so refills stop happening in
	 * whatever hole the printer happened to be passing over. Null goes back to
	 * searching near the work.
	 */
	public void setPreferredBase(BlockPos base) {
		this.preferred = base;
	}

	/** Why the last cycle gave up, or "" — worth surfacing, since it means an idle print. */
	public String lastProblem() {
		return lastProblem;
	}

	/** Next line for the caller's event trail, or "" — call until it returns empty. */
	public String takeEvent() {
		String taken = events.pollFirst();
		return taken == null ? "" : taken;
	}

	/** Queues a line for the trail — see {@code ChestStash.note} for why it is a queue. */
	private void note(String line) {
		if (events.size() < 32) {
			events.addLast(line);
		}
	}

	/** Everything the cycle knows, for the {@code .report} dump. */
	public String debug() {
		// budget and taken as well as want: want is the raw shortage, budget is what the
		// forecast decided to fetch of it, taken is what actually came out. An overfill shows
		// in the gap between them, so the report can answer "did the cap work?" without a guess.
		return String.format(
				"stage=%s ticks=%d box=%s base=%s pad=%d orphan=%s rescue=%b want=%s budget=%s taken=%s backoff=%d last=%s",
				stage, stageTicks, boxSpot == null ? "-" : boxSpot.toShortString(),
				preferred == null ? "-" : preferred.toShortString(), pad.size(),
				orphan == null ? "-" : orphan.toShortString(), rescue, want, budget, taken, backoff,
				lastProblem.isEmpty() ? "-" : lastProblem);
	}

	/**
	 * Whether a cycle could start right now for this forecast: the route needs something the
	 * bag has not got, a carried shulker holds it, and there is a slot for the box to come
	 * home to.
	 */
	public boolean canRestock(MaterialForecast forecast) {
		if (mc().player == null || forecast == null || forecast.isEmpty() || freeSlots() <= 0) {
			return false;
		}
		Map<Item, Integer> plan = plan(forecast);
		return !plan.isEmpty() && findShulker(plan) >= 0;
	}

	/** Abandons the cycle wherever it is — the module was switched off. */
	public void reset() {
		stowing = Map.of();
		pushStuck.clear();
		pushSlot = -1;
		stage = Stage.IDLE;
		stageTicks = 0;
		boxPos = null;
		rescue = false;
		protectedLanding = false;
		restoreSlot();
		MiningActionCoordinator.release(this);
		status = "idle";
		// orphan deliberately survives: a box stranded in the world is still stranded after a
		// toggle, and forgetting it is precisely how it becomes a permanent loss.
	}

	/**
	 * One tick of the cycle. Start it by passing a forecast while {@link #busy()} is false;
	 * keep calling every tick until it returns false.
	 *
	 * @return true while the cycle is driving, so the caller holds off its own work
	 */
	public boolean tick(MaterialForecast forecast, java.util.function.Predicate<BlockPos> buildArea) {
		return tick(forecast, buildArea, false);
	}

	/**
	 * As {@link #tick(MaterialForecast, java.util.function.Predicate)}, but {@code urgent}
	 * skips the quiet spell between cycles.
	 *
	 * <p>For the supply run emptying borrowed boxes at the stash, where one cycle after
	 * another <em>is</em> the job. The cooldown exists to stop a printer that came home short
	 * from working through every box it owns out in the field; standing at the chest with
	 * boxes that are going straight back on the shelf, it is ten seconds of nothing per box.
	 * The failure backoff still applies — that one guards against a cycle that cannot work at
	 * all, which is as true here as anywhere.
	 */
	public boolean tick(MaterialForecast forecast, java.util.function.Predicate<BlockPos> buildArea,
			boolean urgent) {
		if (mc().player == null || mc().level == null) {
			return false;
		}
		if (AutoEat.busy()) {
			yieldToAutoEat();
			return busy();
		}
		this.forbidden = buildArea == null ? pos -> false : buildArea;
		if (stage == Stage.IDLE) {
			if (backoff > 0) {
				backoff--;
				return false;
			}
			if (urgent) {
				cooldown = 0;
			}
			// A stranded box outranks everything. Going off to place a second box while the
			// first is still standing full of concrete is how one loss becomes several.
			if (orphan != null) {
				return startRescue();
			}
			// A finished cycle is not permission to start another one immediately. Without
			// this, a cycle that came back short went straight round again, broke the next
			// box, came back short again — and worked through every shulker you owned in
			// seconds. If a refill did not fix the shortage, more refills are not the
			// answer; the printer should get on with what it can place.
			if (cooldown > 0) {
				cooldown--;
				return false;
			}
			if (!begin(forecast)) {
				// Declining is not free: the caller asks again every tick while the bag is
				// short, and working out that there is nothing to fetch costs a walk of the
				// inventory and a search over the forecast. Once every two seconds is plenty
				// for a condition that only changes when the player's bags do — and "the
				// shulkers are empty" is the *normal* state at the end of a print, so this is
				// the hot path, not an edge case.
				cooldown = DECLINE_COOLDOWN;
				return false;
			}
		}
		return runStages();
	}

	/**
	 * Packs leftovers into a spare box and puts the box back, instead of the usual fetch.
	 *
	 * <p>Exists because a stash chest full of shulkers has <b>no room for loose blocks</b>.
	 * Deposits into it move nothing, the surplus rides along in the bag for ever, and every
	 * later trip is squeezed into whatever slots are left — which is how a bag ends up too
	 * full to borrow anything worth having. Packing solves it without needing a spare chest
	 * slot at all: the box was taken <em>out</em> of the chest, so the slot it came from is
	 * still free for it to go back into, full or empty.
	 *
	 * <p>Runs the same place/open/close/break/collect cycle as a fetch, with only the middle
	 * reversed — items go in rather than out.
	 */
	public boolean stowTick(Map<Item, Integer> surplus,
			java.util.function.Predicate<BlockPos> buildArea) {
		if (mc().player == null || mc().level == null) {
			return false;
		}
		this.forbidden = buildArea == null ? pos -> false : buildArea;
		if (stage == Stage.IDLE) {
			if (surplus == null || surplus.isEmpty() || emptyBox() < 0) {
				return false;
			}
			pad.clear();
			padTodo.clear();
			pathIndex = 0;
			rescue = false;
			stowing = new HashMap<>(surplus);
			want = Map.of();
			budget = Map.of();
			taken.clear();
			boxSpot = findBoxSpot();
			if (boxSpot == null && !planPad()) {
				lastProblem = "nowhere safe to stand a box to pack into";
				status = lastProblem;
				stowing = Map.of();
				return false;
			}
			stage = padTodo.isEmpty() ? Stage.LAND : Stage.PLATFORM;
			stageTicks = 0;
			landClosest = Double.MAX_VALUE;
			note("packing " + stowing.size() + " leftover item(s) into a spare box");
		}
		return runStages();
	}

	/** An empty carried shulker, or -1 — the one a pack needs. */
	private int emptyBox() {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isShulker(stack) && contents(stack).isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	/** The stage machine, shared by the fetch and the pack. */
	private boolean runStages() {
		// The break lease is held by stage, not by the call that mines: mineTick takes it when
		// it needs it, and keeping one through a travel leg would refuse every other miner for
		// no reason. Both entry points funnel through here, so this is the one place to let go.
		if (!mining()) {
			MiningActionCoordinator.release(this);
		}
		int limit = switch (stage) {
			case BREAK, CLEANUP -> MINE_TIMEOUT;
			case COLLECT -> COLLECT_TIMEOUT;
			default -> STAGE_TIMEOUT;
		};
		if (++stageTicks > limit) {
			// never leave a box on the ground because a step hung: fall through to the
			// recovery half, which is what gets it back
			fail("timed out in " + stage);
			return busy();
		}
		// Do NOT reset fall distance here. NoFall decides when to lie about onGround by
		// watching fallDistance climb past its threshold; zeroing it every tick kept that at
		// nought, so NoFall never fired and the server charged the fall in full. Left alone,
		// fallDistance accumulates, NoFall spoofs onGround, and a drop costs no health — which
		// is the whole point of leaning on NoFall rather than a client-only reset that a
		// server never sees anyway.
		// Hold station beside the box for every working stage. The player hovers next to the
		// box (see standBeside), not on it, so it stays in arm's reach without ever standing
		// on the block being mined. Flight is kept on throughout: cutting it here only dropped
		// the player through the open air under a floating build — 24 blocks down in the
		// report, far out of the box's reach — which is exactly why the box got left behind.
		if (working() && standSpot != null) {
			// always stand beside the box, never on its spot: the dropped shulker is one block
			// away and pickup reaches that far, and standing on the spot means the next box in
			// the loop has nowhere to go — it lands where we stand and can never be opened.
			settleAt(standSpot);
		}
		switch (stage) {
			case PLATFORM -> platform();
			case LAND -> land();
			case PLACE -> place();
			case OPEN -> open();
			case PULL -> pull();
			case CLOSE -> close();
			case BREAK -> breakBox();
			case COLLECT -> collect();
			case RECOVER -> recover();
			case CLEANUP -> cleanup();
			default -> {
			}
		}
		return busy();
	}

	/** Closes a shulker menu we own so eating never fights a right-click or screen. */
	public void yieldToAutoEat() {
		if (expectingOpen() && mc().player != null
				&& mc().player.containerMenu != mc().player.inventoryMenu) {
			ContainerUtil.closeMenu();
		}
	}

	/**
	 * Opens a cycle for this forecast, or declines. True when one started.
	 *
	 * <p>The shopping list is settled before the spot, because the spot search depends on it:
	 * {@link #spareBlock} may only spend a block on a pad once it knows which materials are
	 * spoken for, or a refill ends up building its own footing out of the concrete it came to
	 * fetch.
	 */
	private boolean begin(MaterialForecast forecast) {
		if (forecast == null || forecast.isEmpty() || freeSlots() <= 0) {
			return false;
		}
		pad.clear();
		padTodo.clear();
		pathIndex = 0;
		rescue = false;
		want = shortfall(forecast);
		budget = plan(forecast);
		if (budget.isEmpty() || findShulker(budget) < 0) {
			lastProblem = "nothing a carried shulker can supply";
			status = lastProblem;
			return false;
		}
		boxSpot = findBoxSpot();
		if (boxSpot == null && !planPad()) {
			lastProblem = "nowhere safe to stand a shulker, and nothing to build one on";
			status = lastProblem;
			return false;
		}
		taken.clear();
		stage = padTodo.isEmpty() ? Stage.LAND : Stage.PLATFORM;
		stageTicks = 0;
		landClosest = Double.MAX_VALUE;
		note("restock starting at " + boxSpot.toShortString()
				+ (padTodo.isEmpty() ? "" : " (building a " + padTodo.size() + "-block pad)")
				+ " for " + budget.size() + " item(s): " + budget);
		return true;
	}

	/**
	 * Starts a cycle whose only job is to get {@link #orphan} back.
	 *
	 * <p>No container work, no shopping list: fly to it, mine it, collect it. If the block
	 * is not there any more — someone else took it, or the chunk reloaded and it was already
	 * gone — the debt is written off rather than chased forever.
	 */
	private boolean startRescue() {
		if (!(mc().level.getBlockState(orphan).getBlock() instanceof ShulkerBoxBlock)) {
			note("stranded shulker at " + orphan.toShortString() + " is no longer there");
			orphan = null;
			return false;
		}
		if (freeSlots() <= 0) {
			lastProblem = "no free slot to recover the stranded shulker into";
			status = lastProblem;
			// Rated, like every other declined start: a full bag stays full until the printer
			// places something, and re-deciding that twenty times a second buys nothing.
			backoff = DECLINE_COOLDOWN;
			return false;
		}
		boxSpot = orphan;
		boxPos = orphan;
		rescue = true;
		boxesAtPlace = countShulkers(); // one more than this on the way out means we got it back
		pad.clear();
		padTodo.clear();
		pathIndex = 0;
		budget = Map.of();
		want = Map.of();
		taken.clear();
		if (!standBeside(orphan)) {
			// Nowhere to stand: hover over it and mine from there. Worse than standing beside
			// it, and still far better than leaving a full shulker in the world.
			standSpot = orphan.above();
			path = List.of();
		}
		stage = Stage.LAND;
		stageTicks = 0;
		landClosest = Double.MAX_VALUE;
		note("going back for the stranded shulker at " + orphan.toShortString());
		return true;
	}

	// ---- stages -----------------------------------------------------------------

	/**
	 * Builds the pad that stands the box on nothing.
	 *
	 * <p>Only the <em>box</em> needs support — the printer is flying, so the player does
	 * not. But a lone block over open air is where drops go to die: the box breaks, the item
	 * pops out with a little sideways velocity, and it slides off a one-block island into the
	 * void. So the pad is the support plus whichever of its four neighbours are missing, which
	 * is cheap enough to build and break every refill and is what lets a print over water, a
	 * ravine or open air refill at all instead of stalling — safely.
	 */
	private void platform() {
		status = "building somewhere to set the box";
		padTodo.removeIf(pos -> mc().level.getBlockState(pos).isSolidRender());
		if (padTodo.isEmpty()) {
			stage = Stage.LAND;
			stageTicks = 0;
			landClosest = Double.MAX_VALUE;
			return;
		}
		BlockPos target = padTodo.get(0);
		int slot = spareBlock();
		if (slot < 0) {
			fail("nothing spare to build a pad from");
			return;
		}
		int hotbar = toHotbar(slot);
		if (hotbar < 0) {
			fail("no hotbar slot for the pad block");
			return;
		}
		if (previousSlot < 0) {
			previousSlot = mc().player.getInventory().getSelectedSlot();
		}
		select(hotbar);
		BlockPos against = supportFor(target);
		if (against == null) {
			// This one has nothing to build against yet — a neighbour we are about to place
			// will give it one. Rotate it to the back rather than failing the cycle.
			if (padTodo.size() > 1) {
				padTodo.add(padTodo.remove(0));
				return;
			}
			fail("no face to build the pad against");
			return;
		}
		InteractUtil.useOnBlock(against, Direction.UP);
		// Recorded once however many attempts the click takes, or cleanup would try to mine
		// the same block three times and the pad would outlive the refill.
		if (!pad.contains(target)) {
			pad.add(target);
		}
	}

	/**
	 * Drops to the base before doing anything else.
	 *
	 * <p>This stage is the whole reason a base exists: a refill starts while the printer
	 * is <em>flying</em> at band height, where there is no ground beside it to stand a box
	 * on. Placing where the printer happens to hover fails almost every time, and on the
	 * occasions it succeeds it leaves a box in the middle of the build.
	 */
	private void land() {
		status = rescue ? "going back for a stranded shulker" : "on the way to refill";
		Vec3 here = mc().player.position();
		// Follow the route that was checked when the spot was chosen, waypoint by
		// waypoint. Flying straight at the target is what failed in play: a spot can be
		// perfectly good and still be walled in on four sides with a roof, and steering
		// into the wall just presses against it until the timeout.
		int before = pathIndex;
		while (pathIndex < path.size()
				&& here.distanceToSqr(Vec3.atBottomCenterOf(path.get(pathIndex))) < 0.8 * 0.8) {
			pathIndex++;
		}
		if (pathIndex != before) {
			// Reached a waypoint: real progress. The timeout is there to catch a landing that
			// is stuck, not one that is merely far — a base A* already proved reachable must
			// never be given up on just for the trip taking longer than a fixed clock.
			stageTicks = 0;
			landClosest = Double.MAX_VALUE;
		}
		boolean onPath = pathIndex < path.size();
		Vec3 target = onPath
				? Vec3.atBottomCenterOf(path.get(pathIndex))
				: Vec3.atBottomCenterOf(standSpot);
		double dist = here.distanceToSqr(target);
		if (!onPath && dist < 0.6 * 0.6) {
			stage = rescue ? Stage.BREAK : Stage.PLACE;
			stageTicks = 0;
			return;
		}
		if (dist < landClosest - 0.01) {
			landClosest = dist; // still closing on the target: not stalled, so keep the deadline fresh
			stageTicks = 0;
		}
		// fast along the route, easing off only for the last few blocks onto the spot
		double speed = onPath || dist > 3.0 * 3.0 ? 0.9 : 0.35;
		Vec3 step = target.subtract(here);
		mc().player.setDeltaMovement(step.lengthSqr() <= speed * speed
				? step : step.normalize().scale(speed));
	}

	/**
	 * Puts the box down, and keeps trying until it is actually there.
	 *
	 * <p>The old version fired one click, assumed it worked and moved to OPEN, which then sat
	 * for a hundred ticks waiting for a block that was never placed — over and over, a print
	 * frozen in front of nothing. A click is a request; the block appearing is the result, and
	 * only the result may advance the stage.
	 *
	 * <p>The commonest reason a click was refused is the simplest one: <b>we were standing in
	 * the space</b>. A block cannot be placed inside a player, so the run has to get out of its
	 * own way first — which it does by waiting, since holding station beside the box is already
	 * happening every tick.
	 */
	private void place() {
		BlockPos spot = boxSpot;
		if (spot == null) {
			fail("no spot to place at");
			return;
		}
		if (stageTicks == 1) {
			// Counted once, on the way in. Re-reading it each retry would capture the count
			// *after* the client predicted the placement, and recovery would then measure the
			// box's return against a number that already had it gone.
			boxesAtPlace = countShulkers();
		}
		if (mc().level.getBlockState(spot).getBlock() instanceof ShulkerBoxBlock) {
			boxPos = spot; // it is down: that, and only that, is what moves us on
			stage = Stage.OPEN;
			stageTicks = 0;
			return;
		}
		if (!mc().level.getBlockState(spot).isAir()) {
			fail("the refill spot was taken");
			return;
		}
		// the box that holds what is still short, not just anything on the list: a second
		// trip round the loop is here precisely to open the carpet box after the cobble one.
		// Packing wants the opposite - an empty one, with room for what we are giving back.
		int slot = stowing.isEmpty() ? findShulker(remaining()) : emptyBox();
		if (slot < 0) {
			fail(stowing.isEmpty() ? "no carried shulker has what is needed"
					: "no empty shulker to pack into");
			return;
		}
		if (mc().player.getBoundingBox().intersects(new AABB(spot))) {
			status = "stepping out of the way";
			return; // settleAt is already easing us to standSpot; the timeout is the deadline
		}
		double reach = mc().player.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
		if (reach > 4.5 * 4.5) {
			status = "closing on the spot";
			return; // too far for the server to accept it; wait rather than spend the click
		}
		status = "placing a shulker";
		// Equip and click together on the retry cadence. Doing the equipping every tick would
		// fire a container swap twenty times a second at a server for one placement.
		if (stageTicks % 8 != 1) {
			return;
		}
		int hotbar = toHotbar(slot);
		if (hotbar < 0) {
			fail("no hotbar slot for the shulker");
			return;
		}
		if (previousSlot < 0) {
			previousSlot = mc().player.getInventory().getSelectedSlot(); // the first box records it, the rest reuse it
		}
		select(hotbar);
		InteractUtil.useOnBlock(spot.below(), Direction.UP);
	}

	private void open() {
		status = "opening the shulker";
		if (boxPos == null || !(mc().level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock)) {
			return; // the placement has not landed yet — the timeout is the deadline
		}
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			stage = Stage.PULL;
			stageTicks = 0;
			return;
		}
		if (stageTicks % 10 == 1) { // one open attempt, retried rather than spammed
			InteractUtil.useOnBlock(boxPos, Direction.UP);
		}
	}

	private void pull() {
		if (!stowing.isEmpty()) {
			push();
			return;
		}
		status = "taking materials";
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu == mc().player.inventoryMenu) {
			stage = Stage.BREAK; // it closed under us; the box still has to come back
			stageTicks = 0;
			return;
		}
		if (pullDelay > 0) {
			pullDelay--;
			return;
		}
		// keep the last empty slot clear: it is where the box lands when we break it
		if (freeSlots() <= 1) {
			stage = Stage.CLOSE;
			return;
		}
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container instanceof Inventory) {
				continue; // our side; we are taking from the box's
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			// Read the item and count now, before the click: takeExactly empties the slot in
			// place, so stack.getItem() would read air a line later. That exact bug logged
			// every take under "air", left taken.get(cobblestone) at zero, so room never
			// shrank and the loop drained the whole box past its budget — the overfill.
			Item item = stack.getItem();
			int room = budget.getOrDefault(item, 0) - taken.getOrDefault(item, 0);
			if (room <= 0) {
				continue; // this item has had its share; something else needs the slots
			}
			int move = Math.min(room, stack.getCount());
			if (ContainerUtil.takeExactly(menu, i, move)) {
				taken.merge(item, move, Integer::sum);
				pullDelay = PULL_DELAY;
				stageTicks = 0; // progress, not a stall — a long shopping list is not a timeout
				return; // one move per step, so the menu stays in sync with the server
			}
		}
		stage = Stage.CLOSE; // nothing left worth taking
	}

	/**
	 * Moves the surplus into the open box, one stack at a time.
	 *
	 * <p>Each move is judged by reading the slot back rather than assumed, for the same reason
	 * the chest deposit is: a full box accepts the click and moves nothing, and a stage that
	 * believes it made progress would sit there clicking for ever.
	 */
	private void push() {
		status = "packing leftovers away";
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu == mc().player.inventoryMenu) {
			stage = Stage.BREAK; // it closed under us; the box still has to come back
			stageTicks = 0;
			return;
		}
		if (pullDelay > 0) {
			pullDelay--;
			return;
		}
		if (pushSlot >= 0) {
			ItemStack now = menu.getSlot(pushSlot).getItem();
			if (ItemStack.isSameItemSameComponents(now, pushStack)
					&& now.getCount() == pushStack.getCount()) {
				pushStuck.add(pushSlot); // the box would not take it
			}
			pushSlot = -1;
		}
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!(slot.container instanceof Inventory) || pushStuck.contains(i)) {
				continue; // the box's own side, or a stack it has already refused
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || !stowing.containsKey(stack.getItem())) {
				continue;
			}
			pushSlot = i;
			pushStack = stack.copy();
			ContainerUtil.click(menu, i, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
			pullDelay = PULL_DELAY;
			stageTicks = 0;
			return;
		}
		stage = Stage.CLOSE; // packed, or the box will take no more
		stageTicks = 0;
	}

	private void close() {
		status = "closing the shulker";
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			ContainerUtil.closeMenu();
		}
		stage = Stage.BREAK;
		stageTicks = 0;
	}

	private void breakBox() {
		status = "picking the shulker back up";
		if (boxPos == null || !(mc().level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock)) {
			stage = Stage.COLLECT; // it is down; now make sure the drop actually reaches us
			stageTicks = 0;
			return;
		}
		// mined tick by tick, not start-then-stop: in survival the stop cancels the
		// progress and the box would stay on the ground forever
		equipTool(boxPos);
		InteractUtil.mineTick(this, MiningActionCoordinator.PRIORITY_SCHEMATIC, boxPos, Direction.UP);
	}

	/**
	 * Puts the best tool in the bag into the hand before mining.
	 *
	 * <p>Worth a whole inventory slot, and the reports say so plainly: a shulker box is two
	 * seconds with a pickaxe and seven and a half by hand — five-and-thirty if you are hovering,
	 * because vanilla quinutuples mining time off the ground. BREAK allows fifteen seconds, so
	 * bare-handed mining sat right on the edge of the timeout and fell off it regularly, and
	 * every time it did the box was left standing and had to be rescued.
	 *
	 * <p>Chosen by asking each stack how fast it breaks <em>this</em> block rather than by
	 * looking for a pickaxe, so the pad — dirt, usually — gets the shovel without a second rule.
	 * Switching resets destroy progress, so it only ever swaps when something genuinely beats
	 * what is already held; once the right tool is in hand this returns on the first line.
	 */
	private void equipTool(BlockPos pos) {
		net.minecraft.world.level.block.state.BlockState state = mc().level.getBlockState(pos);
		Inventory inventory = mc().player.getInventory();
		float best = inventory.getItem(inventory.getSelectedSlot()).getDestroySpeed(state);
		int found = -1;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			float speed = inventory.getItem(slot).getDestroySpeed(state);
			if (speed > best) {
				best = speed;
				found = slot;
			}
		}
		if (found < 0) {
			// Nothing in the bag beats what is already in the hand — which is the normal case
			// once the right tool has been picked up, not a problem. The warning belongs to
			// the other reading of that: nothing beats the hand because there is no tool at
			// all, and getDestroySpeed answers 1.0 for a bare hand. Testing "found < 0" alone
			// announced "no pickaxe" at exactly the moment a pickaxe was being used with.
			if (!toolWarned && best <= 1.0f && state.getBlock() instanceof ShulkerBoxBlock) {
				toolWarned = true;
				note("no pickaxe in the bag - breaking boxes by hand is slow enough to time out");
			}
			return;
		}
		if (previousSlot < 0) {
			previousSlot = inventory.getSelectedSlot();
		}
		int hotbar = toHotbar(found);
		if (hotbar >= 0) {
			select(hotbar);
		}
	}

	/**
	 * Stays until the dropped box is in the bag.
	 *
	 * <p>The stage that did not exist, and whose absence lost boxes. A block drop carries a
	 * ten-tick pickup delay and no attraction toward the player at all — vanilla items do not
	 * fly to you, you have to be within about a block of them when the delay expires. The old
	 * code waited exactly those ten ticks and then walked away, so whether the box came back
	 * was down to whether the player's hitbox happened to overlap it on one particular tick.
	 * Now the drop is looked for and gone to, and the stage does not end while one is still
	 * lying there.
	 */
	private void collect() {
		status = "collecting the shulker";
		ItemEntity drop = nearestDrop();
		if (drop == null) {
			// Nothing on the ground near the spot. Either it is in the bag (the normal case)
			// or it never dropped, and the count says which.
			stage = Stage.RECOVER;
			stageTicks = 0;
			return;
		}
		// Go and get it. Flying, because the drop may be a block below on a ledge, and
		// stepping off after it is exactly the fall the landing logic exists to prevent.
		flyOn();
		Vec3 to = drop.position().add(0.0, 0.2, 0.0).subtract(mc().player.position());
		double speed = 0.25;
		mc().player.setDeltaMovement(to.lengthSqr() <= 0.01 ? Vec3.ZERO
				: to.lengthSqr() <= speed * speed ? to : to.normalize().scale(speed));
	}

	private void recover() {
		status = "resuming";
		if (rescue) {
			// Still standing means the mine did not finish; keep the debt so the next idle
			// tick goes back for it again rather than declaring victory over a box we can see.
			boolean stillThere = mc().level.getBlockState(orphan)
					.getBlock() instanceof ShulkerBoxBlock;
			note(stillThere
					? "stranded shulker at " + orphan.toShortString() + " is still standing"
					: countShulkers() > boxesAtPlace
							? "stranded shulker recovered from " + orphan.toShortString()
							: "stranded shulker at " + orphan.toShortString() + " is gone from the world");
			if (!stillThere) {
				orphan = null;
			}
			rescue = false;
			boxPos = null;
			stage = Stage.IDLE;
			restoreSlot();
			status = "idle";
			failures = 0;
			cooldown = CYCLE_COOLDOWN;
			return;
		}
		// The check that makes the invariant real rather than aspirational: a box that went
		// down and did not come back is a loss, and the run must say so at the moment it
		// happens, not be discovered later by an empty bag.
		if (countShulkers() < boxesAtPlace) {
			orphan = boxPos != null
					&& mc().level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock
							? boxPos : null;
			String lost = orphan != null
					? "shulker still standing at " + orphan.toShortString() + " — going back for it"
					: "a shulker did not come back near " + (boxSpot == null ? "?" : boxSpot.toShortString());
			note(lost);
			lastProblem = lost;
		}
		if (!stowing.isEmpty()) {
			stowing = Map.of();
			pushStuck.clear();
			pushSlot = -1;
			boxPos = null;
			stage = pad.isEmpty() ? Stage.IDLE : Stage.CLEANUP;
			stageTicks = 0;
			if (stage == Stage.IDLE) {
				restoreSlot();
				status = "idle";
			}
			return; // no cooldown: packing is housekeeping at the chest, not a refill
		}
		boxPos = null;
		// One box holds one material; the budget wants several. If part of it is still
		// unfetched and another carried shulker has it, work the next box at the same spot
		// before leaving — otherwise a refill triggered by running out of carpet comes home
		// with nothing but the cobblestone from the first box it happened to open.
		if (orphan == null && !remaining().isEmpty() && findShulker(remaining()) >= 0
				&& freeSlots() > 1) {
			stage = Stage.PLACE;
			stageTicks = 0;
			return;
		}
		stage = pad.isEmpty() ? Stage.IDLE : Stage.CLEANUP;
		stageTicks = 0;
		if (stage == Stage.IDLE) {
			restoreSlot();
			status = "idle";
			cooldown = CYCLE_COOLDOWN;
		}
	}

	/**
	 * Takes the temporary pad back down, so a refill leaves the world as it was.
	 *
	 * <p>Deliberately after {@link #collect}: mining the pad while the box was still lying on
	 * it dropped the box into whatever the pad was built over, which on a print above open air
	 * meant the void.
	 */
	private void cleanup() {
		status = "clearing the pad";
		pad.removeIf(pos -> !mc().level.getBlockState(pos).isSolidRender());
		if (pad.isEmpty() || stageTicks > MINE_TIMEOUT) {
			pad.clear();
			restoreSlot();
			stage = Stage.IDLE;
			status = "idle";
			failures = 0; // a cycle that got this far worked
			cooldown = CYCLE_COOLDOWN;
			return;
		}
		equipTool(pad.get(0));
		InteractUtil.mineTick(this, MiningActionCoordinator.PRIORITY_SCHEMATIC, pad.get(0), Direction.UP);
	}

	private void fail(String why) {
		if (stage == Stage.PLACE && boxSpot != null) {
			// A spot that would not take a box is not going to take one thirty seconds later
			// either, and the preferred base is tried first every single cycle — so without
			// this the run picks the same dead spot for ever. Blacklisted briefly, the search
			// moves on and the print keeps going.
			badSpots.put(boxSpot.asLong(), System.currentTimeMillis());
			why += " [" + placeDetail() + "]";
		}
		lastProblem = why;
		status = why;
		note("restock gave up: " + why);
		if (++failures >= 2) {
			backoff = 200; // stop churning; the caller says so once and keeps printing
			failures = 0;
		}
		// A box still standing is never written off. It is remembered, and the next thing this
		// class does is go back for it — the old code dropped straight to IDLE here and left a
		// full shulker in the world with nothing recording where.
		if (boxPos != null
				&& mc().level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
			orphan = boxPos;
		}
		// A placed box gets the break/collect half — but a BREAK that failed must not come
		// straight back to BREAK, which is an endless loop with the box still on the ground
		// and the printer frozen in front of it. The orphan record is what picks it up instead,
		// after the backoff, so nothing is lost by stopping here.
		stage = boxPos != null && stage != Stage.BREAK && stage != Stage.COLLECT
				? Stage.BREAK
				// A pad we built is ours to take down even when the cycle went wrong; leaving
				// it behind litters the world with a block per failed refill.
				: pad.isEmpty() ? Stage.IDLE : Stage.CLEANUP;
		stageTicks = 0;
		if (stage == Stage.IDLE) {
			rescue = false;
			restoreSlot();
		}
	}

	/**
	 * Everything that was true when a placement would not go down.
	 *
	 * <p>Written because guessing at this has already cost several rounds. A click that is
	 * accepted by every check we make and still produces nothing is being refused for a reason
	 * only the server knows, and the way to find it is to record the state at the moment of
	 * refusal rather than reason about it from the outside — what was in the hand, what was
	 * under the block, how far away we were, whether we were standing in it. One line in the
	 * report beats three more plausible fixes.
	 */
	private String placeDetail() {
		Inventory inventory = mc().player.getInventory();
		ItemStack held = inventory.getItem(inventory.getSelectedSlot());
		Vec3 eye = mc().player.getEyePosition();
		// Anything standing in the block, because vanilla's own canPlace refuses through
		// isUnobstructed and says nothing about why — and that is the one refusal that passes
		// every check made here (the space is air, we are in reach, we are not in it ourselves)
		// and still produces no block.
		StringBuilder blockers = new StringBuilder();
		for (net.minecraft.world.entity.Entity entity
				: mc().level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(boxSpot),
						e -> true)) {
			blockers.append(blockers.length() == 0 ? "" : "+").append(entity.getType().toShortString());
		}
		return String.format(
				"spot=%s state=%s below=%s dist=%.2f held=%s@%d inside=%b in-block=[%s] stand=%s me=%.2f,%.2f,%.2f fly=%b",
				boxSpot.toShortString(),
				mc().level.getBlockState(boxSpot).getBlock(),
				mc().level.getBlockState(boxSpot.below()).getBlock(),
				Math.sqrt(eye.distanceToSqr(Vec3.atCenterOf(boxSpot))),
				held.isEmpty() ? "empty" : held.getItem(), inventory.getSelectedSlot(),
				mc().player.getBoundingBox().intersects(new AABB(boxSpot)),
				blockers,
				standSpot == null ? "-" : standSpot.toShortString(),
				mc().player.getX(), mc().player.getY(), mc().player.getZ(),
				mc().player.getAbilities().flying);
	}

	/** Spots that refused a box lately, and when — see {@link #fail}. */
	private final Map<Long, Long> badSpots = new HashMap<>();
	/** How long a refusing spot is left alone before it is tried again. */
	private static final long BAD_SPOT_MS = 60_000L;

	private boolean refusedLately(BlockPos pos) {
		Long when = badSpots.get(pos.asLong());
		if (when == null) {
			return false;
		}
		if (System.currentTimeMillis() - when > BAD_SPOT_MS) {
			badSpots.remove(pos.asLong());
			return false;
		}
		return true;
	}

	/** The nearest dropped shulker box around the spot we were working, or null. */
	private ItemEntity nearestDrop() {
		BlockPos around = boxPos != null ? boxPos : boxSpot;
		if (around == null) {
			return null;
		}
		// Down as well as out: a drop that rolled off a ledge is still worth going after,
		// and a couple of blocks is well inside what flight can recover from.
		AABB near = new AABB(around).inflate(4.0, 5.0, 4.0);
		ItemEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (ItemEntity entity : mc().level.getEntitiesOfClass(ItemEntity.class, near)) {
			if (!isShulker(entity.getItem())) {
				continue;
			}
			double dist = entity.distanceToSqr(mc().player);
			if (dist < bestDist) {
				bestDist = dist;
				best = entity;
			}
		}
		return best;
	}

	// ---- inventory questions ------------------------------------------------------

	/**
	 * How many inventory slots one refill may fill, whatever the free space.
	 *
	 * <p>The knob for stop frequency. What goes in them is the forecast's decision, not a
	 * rule of thumb; this only says how much bag to hand it. Set from the Printer's
	 * "Restock fill"; leave room for the shulkers.
	 */
	private int fillSlots = 18;

	/** Sets how many slots a refill may fill — see {@link #fillSlots}. */
	public void setFill(int slots) {
		this.fillSlots = Math.max(1, slots);
	}

	/**
	 * What to fetch, asked of the forecast rather than decided here.
	 *
	 * <p>All this contributes is the shape of the bag: how many slots are going spare, how
	 * much room is left in stacks already held (topping those up costs no slot at all, and
	 * ignoring that used to waste a third of a trip), and which materials a carried shulker
	 * could actually supply. {@link MaterialForecast#fill} does the rest, and what comes back
	 * is the mix that flies furthest — not two stacks of everything and half a bag of the
	 * commonest block, which is what the old rule produced whatever the route looked like.
	 */
	private Map<Item, Integer> plan(MaterialForecast forecast) {
		int free = Math.max(0, freeSlots() - 1); // one stays clear for the box
		int slots = Math.min(free, fillSlots);
		if (slots <= 0) {
			return Map.of();
		}
		Map<Item, Integer> carried = new HashMap<>();
		Map<Item, Integer> room = new HashMap<>();
		for (Item item : forecast.totals().keySet()) {
			carried.put(item, count(item));
			room.put(item, partialRoom(item));
		}
		return forecast.fill(carried, room, slots, this::shulkersHave);
	}

	/** Of the forecast, what the bag cannot already cover — the raw shortage, before any cap. */
	private Map<Item, Integer> shortfall(MaterialForecast forecast) {
		Map<Item, Integer> result = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : forecast.totals().entrySet()) {
			int missing = entry.getValue() - count(entry.getKey());
			if (missing > 0) {
				result.put(entry.getKey(), missing);
			}
		}
		return result;
	}

	/** Of this cycle's budget, what has not been taken yet — what another box could still add. */
	private Map<Item, Integer> remaining() {
		Map<Item, Integer> left = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : budget.entrySet()) {
			int need = entry.getValue() - taken.getOrDefault(entry.getKey(), 0);
			if (need > 0) {
				left.put(entry.getKey(), need);
			}
		}
		return left;
	}

	/** How many of an item the player is carrying, loose (not inside a shulker). */
	private int count(Item item) {
		Inventory inventory = mc().player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Spare space in stacks of {@code item} already held — items that cost no new slot. */
	private int partialRoom(Item item) {
		Inventory inventory = mc().player.getInventory();
		int room = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
			}
		}
		return room;
	}

	/** Shulker boxes carried, counted as items — the number a recovery has to restore. */
	private int countShulkers() {
		Inventory inventory = mc().player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isShulker(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private int freeSlots() {
		Inventory inventory = mc().player.getInventory();
		int free = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	/**
	 * Whether a refill could actually get hold of this item.
	 *
	 * <p>Public because the <em>trigger</em> needs it as much as the shopping list does:
	 * being short of a colour no box holds is not a reason to fly to the base, and a caller
	 * that cannot tell the difference will keep going.
	 */
	public boolean canSupply(Item item) {
		return mc().player != null && shulkersHave(item);
	}

	/** Whether any carried shulker holds this item — i.e. a colour a refill could actually fetch. */
	private boolean shulkersHave(Item item) {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!isShulker(stack)) {
				continue;
			}
			for (ItemStack inside : contents(stack)) {
				if (inside.getItem() == item) {
					return true;
				}
			}
		}
		return false;
	}

	/** Inventory slot of a carried shulker holding any of {@code needed}, or -1. */
	private int findShulker(Map<Item, Integer> needed) {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!isShulker(stack)) {
				continue;
			}
			for (ItemStack inside : contents(stack)) {
				if (needed.containsKey(inside.getItem())) {
					return slot;
				}
			}
		}
		return -1;
	}

	private static boolean isShulker(ItemStack stack) {
		return stack.getItem() instanceof BlockItem block
				&& block.getBlock() instanceof ShulkerBoxBlock;
	}

	/** What a carried shulker holds, read off the stack's own component. */
	private static List<ItemStack> contents(ItemStack stack) {
		ItemContainerContents held = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
		if (held == null) {
			return List.of();
		}
		List<ItemStack> items = new ArrayList<>();
		held.allItemsCopyStream().forEach(items::add);
		return items;
	}

	/** Moves an inventory slot into the hotbar if needed; returns the hotbar slot. */
	private int toHotbar(int slot) {
		if (slot < Inventory.getSelectionSize()) {
			return slot;
		}
		Inventory inventory = mc().player.getInventory();
		int target = -1;
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				target = i;
				break;
			}
		}
		if (target < 0) {
			target = 0; // swap with whatever is in the first slot; SWAP is an exchange
		}
		// inventory slots map to menu slots 9..35 in the player's own menu
		InteractUtil.swapWithHotbar(slot < 9 ? slot + 36 : slot, target);
		return target;
	}

	private void select(int hotbarSlot) {
		mc().player.getInventory().setSelectedSlot(hotbarSlot);
		if (mc().getConnection() != null) {
			mc().getConnection().send(
					new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(hotbarSlot));
		}
	}

	private void restoreSlot() {
		if (previousSlot >= 0 && mc().player != null) {
			select(previousSlot);
			previousSlot = -1;
		}
	}

	/** How far out to look for a base on the printed floor before dropping any lower. */
	private static final int BASE_RADIUS = 8;
	/** And how far below build height a base may sit — a short, survivable step, not a plunge. */
	private static final int BASE_DROP = 4;
	/** How far a dropped box may fall beside its spot and still be counted recoverable. */
	private static final int CATCH_DEPTH = 4;
	/** The most air allowed under the feet before flight may be cut — a drop that cannot hurt. */
	private static final double SAFE_DROP = 1.25;
	/**
	 * How near the middle of the stand spot counts as centred.
	 *
	 * <p>0.15 leaves the 0.6-wide player inside 582.35..582.95 of its own block — clear of the
	 * neighbour the box goes in, with room to spare. Anything approaching 0.2 starts to touch.
	 */
	private static final double CENTRED = 0.15;

	/**
	 * Somewhere on the printed floor near the player to stand a box on: solid printed ground
	 * a short hop away, or null when nothing near qualifies (the caller then builds a pad).
	 *
	 * <p><b>Near and high, not straight down.</b> The old scan dived up to twenty-four blocks
	 * beneath the printer and stood the box on whatever ledge it first hit — under a floating
	 * build that is a base in the void, and the plunge to reach it is where the fall damage,
	 * the landing timeouts and the out-of-reach box all came from. The floor the printer has
	 * already laid is real, solid ground one short step away; this walks outward to it at build
	 * height, dropping at most a little, so the player lands on what it just printed. Only the
	 * schematic's own positions are off limits, not the whole region — a box on top of the
	 * finished floor is exactly right.
	 */
	private BlockPos findBoxSpot() {
		// the picked base first: its own cell, then what is around it
		if (preferred != null) {
			if (isBoxSpot(preferred) && standBeside(preferred)) {
				return preferred;
			}
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos spot = preferred.relative(side);
				if (isBoxSpot(spot) && standBeside(spot)) {
					return spot;
				}
			}
			if (isBoxSpot(preferred.below()) && standBeside(preferred.below())) {
				return preferred.below();
			}
			// Say so rather than quietly searching elsewhere. A base you chose by hand and set
			// with .pbase failing the drop test is worth knowing about — it usually means the
			// spot is on a lip, which is exactly where boxes used to be lost.
			note("restock base " + preferred.toShortString()
					+ " is not usable (no safe footing for the drop) — looking near the work");
		}
		// least descent first, then nearest: a spot at the player's own height needs no drop
		// at all, so it is always preferred over one a few blocks down
		BlockPos feet = mc().player.blockPosition();
		for (int dy = 0; dy >= -BASE_DROP; dy--) {
			for (int r = 1; r <= BASE_RADIUS; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue; // one square ring at a time, so the closest spot wins
						}
						BlockPos spot = feet.offset(dx, dy, dz);
						if (isBoxSpot(spot) && standBeside(spot)) {
							return spot;
						}
					}
				}
			}
		}
		// Nothing near the printed floor, so build one — see planPad. There used to be a
		// last-ditch scan up to twenty-four blocks straight down, taking whatever ledge it
		// first hit. It killed the player: a run picked a spot at Y=78 under a build at Y=102,
		// the drop to it was fatal, and the print ended with a corpse and a full shulker
		// stranded on a ledge. No refill is worth a fall that size, and a one-block pad built
		// three blocks down does the same job for free.
		return null;
	}

	/**
	 * Finds ground next to the box and a way to it, keeping both.
	 *
	 * <p>Beside, never above. Hovering over the box put the player on top of the very
	 * block it then had to mine, which is the one place you cannot comfortably work
	 * from — and when the box was placed underfoot the player simply dropped onto it.
	 * Standing on solid ground next to it is how a person would do this.
	 *
	 * <p>Reachability is part of <em>choosing</em> the spot, not something discovered on the
	 * way: a pocket of air with solid ground under it passes every local test and can still
	 * be sealed on all four sides and above, and committing to it means burning a timeout and
	 * starting over. Asking A* first is the same rule the printer's own route follows — the
	 * plan owns the geometry.
	 */
	private boolean standBeside(BlockPos box) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos stand = box.relative(side);
			if (!forbidden.test(stand)
					&& mc().level.getBlockState(stand.below()).isSolidRender()
					&& FlightPath.fits(stand)
					&& routeTo(stand)) {
				standSpot = stand;
				return true;
			}
		}
		return false;
	}

	private boolean routeTo(BlockPos hover) {
		if (!FlightPath.fits(hover)) {
			return false; // the body does not fit there at all
		}
		BlockPos from = mc().player.blockPosition();
		if (from.equals(hover) || from.closerThan(hover, 1.5)) {
			path = List.of();
			return true;
		}
		List<BlockPos> found = FlightPath.find(from, hover, FlightPath.DEFAULT_BUDGET);
		// find() returns a best-effort path; only a route that actually arrives counts
		if (found.isEmpty() || !found.get(found.size() - 1).closerThan(hover, 1.5)) {
			return false;
		}
		path = found;
		return true;
	}

	/**
	 * Works out where to build a pad and what it takes, filling {@link #padTodo}.
	 *
	 * <p>The pad is the support block plus whichever of its four neighbours are missing, so
	 * the box's drop has a floor to land on however it bounces. That is the difference
	 * between the old one-block island — which a drop can and does slide off, into whatever
	 * the print is floating over — and a surface a box cannot be lost from.
	 */
	private boolean planPad() {
		BlockPos support = findPadSpot();
		if (support == null) {
			return false;
		}
		padTodo.clear();
		if (!mc().level.getBlockState(support).isSolidRender()) {
			padTodo.add(support);
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos rim = support.relative(side);
			if (!forbidden.test(rim) && !mc().level.getBlockState(rim).isSolidRender()) {
				padTodo.add(rim);
			}
		}
		if (spareBlock() < 0) {
			return false; // nothing to build it out of
		}
		boxSpot = support.above();
		// Hover beside the box rather than on it: the pad's rim is not somewhere to stand,
		// it is somewhere the drop can land, and settleAt keeps us flying over open air.
		standSpot = boxSpot.relative(Direction.Plane.HORIZONTAL.iterator().next());
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos beside = boxSpot.relative(side);
			if (FlightPath.fits(beside)) {
				standSpot = beside;
				break;
			}
		}
		path = List.of();
		return true;
	}

	/** Where a pad could go: open air below the printer with something to build against. */
	private BlockPos findPadSpot() {
		BlockPos feet = mc().player.blockPosition();
		for (int dy = 2; dy <= 8; dy++) {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos spot = feet.below(dy).relative(side);
				if (!forbidden.test(spot) && !forbidden.test(spot.above())
						&& mc().level.getBlockState(spot).isAir()
						&& mc().level.getBlockState(spot.above()).isAir()
						&& mc().level.getBlockState(spot.above(2)).isAir()
						&& supportFor(spot) != null) {
					return spot;
				}
			}
		}
		return null;
	}

	/** A neighbouring solid block to click when placing at {@code pos}. */
	private BlockPos supportFor(BlockPos pos) {
		for (Direction side : Direction.values()) {
			BlockPos neighbour = pos.relative(side);
			if (mc().level.getBlockState(neighbour).isSolidRender()) {
				return neighbour;
			}
		}
		return null;
	}

	/**
	 * An inventory slot holding a block worth spending on a pad: not one the schematic is
	 * asking for, so a refill never eats the materials it just fetched.
	 */
	private int spareBlock() {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.getItem() instanceof BlockItem && !isShulker(stack)
					&& !want.containsKey(stack.getItem()) && stack.getCount() > 1) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Somewhere a box can stand and be broken again: solid under, air around, not the build,
	 * and <b>nowhere its drop can fall away from</b>.
	 *
	 * <p>Three air blocks, not two. The box takes the lowest one and the player hovers in
	 * the two above it — a player is 1.8 blocks tall, and checking only one meant picking
	 * spots the printer could never reach, where LAND then ran its timeout out and started
	 * the whole cycle again. That loop is a printer that floats and does nothing.
	 */
	private boolean isBoxSpot(BlockPos pos) {
		return !refusedLately(pos)
				&& !forbidden.test(pos)
				&& !forbidden.test(pos.above())
				&& mc().level.getBlockState(pos).isAir()
				&& mc().level.getBlockState(pos.below()).isSolidRender()
				// the player hovers in the space above the box: ask the real hitbox
				// rather than counting air blocks, so slabs and stairs answer correctly
				&& FlightPath.fits(pos.above())
				&& dropSafe(pos);
	}

	/**
	 * Whether a box broken here leaves its drop somewhere we can still reach.
	 *
	 * <p>A drop pops out with a little sideways velocity, so "there is a solid block directly
	 * under the box" is not enough: a spot on the lip of a ledge, or a single block over open
	 * air, passes that test and still loses the box down the drop beside it.
	 *
	 * <p>The bar is what {@link #collect} can actually reach, not what looks tidy. Requiring
	 * the neighbours to be flush was far too strict in play — it rejected a hand-picked
	 * {@code .pbase} sitting on a partly-printed floor and sent the search into a fallback
	 * that killed the player — so a column counts as safe if anything catches the drop within
	 * {@link #CATCH_DEPTH}, which is inside the range the collector searches and flies to. Only
	 * genuinely open air, the void under a floating build, fails now.
	 */
	private boolean dropSafe(BlockPos box) {
		BlockPos floor = box.below();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos beside = floor.relative(side);
			boolean caught = false;
			for (int drop = 0; drop <= CATCH_DEPTH; drop++) {
				if (mc().level.getBlockState(beside.below(drop)).isSolidRender()) {
					caught = true;
					break;
				}
			}
			if (!caught) {
				return false;
			}
		}
		return true;
	}
}
