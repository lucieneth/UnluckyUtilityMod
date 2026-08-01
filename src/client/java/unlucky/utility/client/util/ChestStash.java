package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.modules.player.AutoEat;

/**
 * The supply run: fly to marked chests, drop what the print does not need, and come back
 * with what it does.
 *
 * <p>Two ways of doing that, and the choice is a real trade rather than a tuning knob.
 * <ul>
 * <li><b>Stash only</b> (the default). Boxes are <em>borrowed</em>: taken from the chest,
 *     emptied into the bag right there beside it, and put straight back on the shelf. What
 *     flies home is a bag of blocks and no cargo. An inventory is 36 slots, so a trip carries
 *     about 2000 blocks and a twenty-thousand-block band takes ten of them — but no shulker
 *     is ever opened out over the build, which is where a dropped one is a real loss.
 * <li><b>Carry boxes.</b> The boxes come along and are opened where the work is
 *     ({@link ShulkerRestock}). A box holds 1728 blocks, so a bag of them covers a whole band
 *     in one trip and the travel — the real cost of an AFK print — drops by an order of
 *     magnitude. The price is a bag mostly full of shulkers and box handling in the field.
 * </ul>
 *
 * <p>Borrowing has a subtlety worth stating, because it is not obvious and it caps the whole
 * mode: <b>a box in the bag occupies the slot the unload wants to pour it into.</b> Filling
 * the inventory with boxes leaves room for barely half a bag of blocks. So a stash-only trip
 * takes about half the free space in boxes, empties them into the other half, gives them
 * back, and goes round again — 17 slots, then 26, then 30, then 32. Four rounds; a fifth adds
 * nothing.
 *
 * <p>Either way <b>the bag starts every trip empty</b>. Wrong colours left over from the last
 * band, boxes emptied since, blocks the schematic has stopped asking for — all of it goes
 * back into the stash before anything is taken out. A bag that silts up is a bag with no room.
 *
 * <p>Chests are marked by hand with {@code .stash}, deliberately: a printer that adopted
 * every container you happened to open would eventually deposit a band's worth of concrete
 * into someone's furnace.
 */
public final class ChestStash {
	/** Where in the supply run we are. */
	public enum Stage {
		IDLE, TRAVEL, OPEN, DEPOSIT, PACK, WITHDRAW, CLOSE, UNLOAD, RETURN
	}

	/** Ticks a stage may take before the run is abandoned. */
	private static final int STAGE_TIMEOUT = 120;
	/** Travel gets its own budget: a stash can be a long way from the work. */
	private static final int TRAVEL_TIMEOUT = 400;
	/**
	 * Search budget for the trip out, well above the lane driver's.
	 *
	 * <p>A detour around a block is a handful of nodes; crossing a whole map art to the stash
	 * is thousands, and the default was low enough that the search ran out and the trip was
	 * abandoned before it began.
	 */
	private static final int TRAVEL_BUDGET = 16_000;
	/** Ticks between container clicks, so a server sees a human cadence. */
	private static final int MOVE_DELAY = 2;
	/** Quiet spell after a run, so trips cannot chain one into the next. */
	private static final int TRIP_COOLDOWN = 100;
	/** Bottlenecks to walk past in one tick before accepting this chest has nothing to give. */
	private static final int MAX_BOTTLENECKS = 32;
	/** Emptying a dozen boxes is minutes of legitimate work, not a stall. */
	private static final int UNLOAD_TIMEOUT = 2400;
	/** Borrow-and-return rounds a trip may run before settling for the bag it has. */
	private static final int MAX_ROUNDS = 4;
	/** Blocks a trip must bring home to count as having been worth making. */
	private static final int WORTHWHILE = 64;
	/** And a longer one after a run that found nothing, so an empty stash is not hammered. */
	private static final int EMPTY_COOLDOWN = 1200;
	/** How close to a waypoint counts as reached. */
	private static final double REACHED = 0.8;
	/** Travel speed, and the crawl used for the last block onto the hover spot. */
	private static final double CRUISE = 0.9;
	private static final double EASE = 0.3;

	/** Marked containers, in the order they were marked. */
	private final List<BlockPos> chests = new ArrayList<>();
	/**
	 * What each chest was last seen to hold, counting inside its shulkers.
	 *
	 * <p>So a trip is only made when there is reason to think it will help. An unvisited
	 * chest is assumed to hold everything — optimism is right for somewhere never looked at,
	 * and one wasted trip corrects it forever.
	 *
	 * <p><b>And it goes stale.</b> "One wasted trip corrects it forever" was half right: it
	 * corrects it, and then the correction never expires. A chest looked in before you filled
	 * it stays written off for the rest of the session, so refilling the stash mid-print has
	 * no effect — the printer reports no source for a material sitting in eight boxes it
	 * decided not to open. What a chest held ten minutes ago is not evidence about now, so
	 * these expire back to "unknown", which is the optimistic answer.
	 */
	private final Map<BlockPos, Set<Item>> seen = new HashMap<>();
	private final Map<BlockPos, Long> seenAt = new HashMap<>();
	/** How long a look inside a chest is believed for. */
	private static final long SEEN_TTL_MS = 300_000;

	private Stage stage = Stage.IDLE;
	private int stageTicks;
	private int moveDelay;
	/** Chest being worked, and the ones already worked this trip. */
	private BlockPos target;
	private final Set<BlockPos> visited = new HashSet<>();
	/** Where to hover to reach {@link #target}. */
	private BlockPos hover;
	private List<BlockPos> path = List.of();
	private int pathIndex;
	/** Ticks before a failed partial-route repair may be tried again. */
	private int rerouteDelay;
	private double closest = Double.MAX_VALUE;
	/** What this trip is trying to bring back, decided when it starts. */
	private Map<Item, Integer> shortfall = Map.of();
	/**
	 * Everything the print uses at all, which is a wider set than {@link #shortfall}.
	 *
	 * <p>The two must not be confused, and confusing them deposits your own materials: a
	 * colour you are carrying <em>enough</em> of is absent from the shortfall but is very much
	 * still wanted, and "put back what is not short" would hand it to the chest and then fetch
	 * it again next trip.
	 */
	private Set<Item> wanted = Set.of();
	/**
	 * Everything the print still needs, whatever this particular trip is shopping for.
	 *
	 * <p>Wider than {@link #wanted} on purpose, and the width is the point: a trip's list
	 * narrows to the material the active pass lays, but the bag rightly carries what the
	 * passes after it will lay too. Without this, everything outside the current pass reads
	 * as "not wanted" and gets deposited — including material the last trip went and fetched.
	 */
	private Set<Item> keep = Set.of();
	/** The route this trip is buying for — the order matters, so the totals alone will not do. */
	private MaterialForecast forecast = MaterialForecast.NONE;
	/** Materials this chest turned out not to have, so the search moves past them. */
	private final Set<Item> unavailable = new LinkedHashSet<>();
	/** Materials this chest holds <em>inside boxes</em>, so loose ones of the same can go back. */
	private final Set<Item> boxedHere = new HashSet<>();
	/** The box-opening cycle, borrowed from the caller to empty boxes here at the chest. */
	private ShulkerRestock unloader;
	private java.util.function.Predicate<BlockPos> buildArea;
	/** Whether boxes are drained here and left behind, rather than carried out to the build. */
	private boolean emptyHere;
	/**
	 * Boxes borrowed this round, and the ceiling on them.
	 *
	 * <p>Borrowing is self-limiting in a way that is easy to miss: a box in the bag occupies
	 * the very slot the unload wants to pour it into. Fill the inventory with sixteen boxes and
	 * there is room for nineteen slots of blocks and no more, however many boxes the chest
	 * holds. So a round takes about half the free space in boxes, empties them into the other
	 * half, gives them back, and goes again — 18 slots, then 27, then 32, converging on a bag
	 * that is all blocks. One greedy round cannot get there; three lazy ones can.
	 */
	private int roundBoxes;
	private int roundCap;
	private int rounds;
	/** Whether this visit has already packed a box; one pass is enough. */
	private boolean packed;
	/** Whether this is an opening survey — look in every chest, take nothing. */
	private boolean surveying;
	/** Whether the survey also empties the bag, and whether it has done so yet. */
	private boolean clearing;
	private boolean cleared;
	private int surveyed;
	/** Whether the next ordinary trip should go even with nothing to fetch, to put back. */
	private boolean clearOutWanted;
	/** A source miss turns the rest of this trip into a fresh proof across every chest. */
	private boolean checkingAllChests;
	/**
	 * The slot we clicked last, so a move that did nothing can be told from one that worked.
	 *
	 * <p>Without this a full chest is a deadlock: the click is legal, silently moves nothing,
	 * and the stage resets its own deadline on every attempt because it believes it made
	 * progress. It would sit there clicking the same stack until the world ended.
	 */
	private int pendingSlot = -1;
	private ItemStack pendingStack = ItemStack.EMPTY;
	private Map<Item, Integer> pendingSpend = Map.of();
	/** Slots proven immovable at this chest, skipped for the rest of the visit. */
	private final Set<Integer> stuck = new HashSet<>();
	/** Boxes taken so far this trip, and the ceiling on them. */
	private int boxesTaken;
	private int boxLimit = 12;
	/** Slots kept clear for the on-site unload to have somewhere to put materials. */
	private int reserve = 20;
	/** Material aboard when the trip began, so its worth can be measured at the end. */
	private int heldAtStart;
	private int cooldown;
	/** One mandatory printer-pass transition may launch through an old retry cooldown. */
	private boolean forceNextTrip;
	private String status = "idle";
	private String lastProblem = "";
	private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- marking -----------------------------------------------------------------

	/** {@code .stash} on a container: adds it, or removes it if already marked. */
	public String mark(BlockPos pos) {
		if (pos == null) {
			return "Look at a chest, barrel or shulker box first";
		}
		for (BlockPos kept : chests) {
			if (kept.equals(pos)) {
				chests.remove(kept);
				seen.remove(kept);
				seenAt.remove(kept);
				return "Stash: removed " + pos.toShortString() + " (" + chests.size() + " left)";
			}
		}
		chests.add(pos.immutable());
		return "Stash: added " + pos.toShortString() + " (" + chests.size() + " total)";
	}

	public String clear() {
		int had = chests.size();
		chests.clear();
		seen.clear();
		seenAt.clear();
		return "Stash cleared (" + had + " removed)";
	}

	/** The marked list, for {@code .stash list}. */
	public String describe() {
		if (chests.isEmpty()) {
			return "Stash is empty - look at a chest and type .stash to add it";
		}
		StringBuilder text = new StringBuilder("Stash (" + chests.size() + "):");
		for (BlockPos pos : chests) {
			text.append("\n  ").append(pos.toShortString());
			Long at = seenAt.get(pos);
			Set<Item> held = held(pos);
			if (at == null) {
				text.append(" - not looked in yet (assumed to have everything)");
			} else if (held == null) {
				text.append(" - last looked in ").append((System.currentTimeMillis() - at) / 60_000)
						.append("m ago, expired (assumed to have everything again)");
			} else {
				// The actual contents, because "3 kinds" tells you nothing when the question is
				// why the printer thinks there is no cobblestone in a chest full of it.
				text.append(" - ").append(held.size()).append(" kinds: ").append(short_(held, 40));
			}
		}
		return text.toString();
	}

	public boolean configured() {
		return !chests.isEmpty();
	}

	/** The marked list as a setting string, so a stash survives a restart. */
	public String save() {
		StringBuilder text = new StringBuilder();
		for (BlockPos pos : chests) {
			if (text.length() > 0) {
				text.append(';');
			}
			text.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
		}
		return text.toString();
	}

	/** Reloads {@link #save}'s output, ignoring anything malformed rather than failing. */
	public void load(String saved) {
		chests.clear();
		seen.clear();
		seenAt.clear();
		if (saved == null || saved.isBlank()) {
			return;
		}
		for (String part : saved.split(";")) {
			String[] xyz = part.trim().split("[,\\s]+");
			if (xyz.length != 3) {
				continue;
			}
			try {
				chests.add(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
						Integer.parseInt(xyz[2])));
			} catch (NumberFormatException ignored) {
				// a hand-edited setting must never stop a print
			}
		}
	}

	// ---- the opening survey ---------------------------------------------------------

	/**
	 * Opens every marked chest once, in nearest-first order, and records what is in it.
	 *
	 * <p>Run at the start of a print, because every decision downstream is made against
	 * {@link #seen} and an empty {@code seen} means the first shortage of every material is
	 * answered by a guess. Guesses are cheap when right and expensive when wrong — the trip
	 * that goes to the near chest, does not find the cobblestone, and comes home is the
	 * expensive case, and it repeats per material. One lap of the stash up front replaces the
	 * whole guessing game with a fact, and it costs a few seconds once.
	 *
	 * <p>It also solves the staleness from the other end: a survey is exactly what you want
	 * after refilling the chests, which is when the printer's memory is most wrong.
	 */
	/**
	 * Asks the next trip to put back everything this print has no use for, even if there is
	 * nothing to fetch — see {@code begin}.
	 */
	public void requestClearOut() {
		clearOutWanted = true;
	}

	/**
	 * Lets the next idle-to-trip transition start immediately once.
	 *
	 * <p>This is consumed before the trip is attempted. If the source is actually missing,
	 * the ordinary failure cooldown still applies afterwards instead of retrying every tick.
	 */
	public void forceNextTrip() {
		forceNextTrip = true;
	}

	public boolean beginSurvey(boolean alsoEmptyTheBag) {
		if (chests.isEmpty() || mc().player == null || busy()) {
			return false;
		}
		surveying = true;
		clearing = alsoEmptyTheBag;
		cleared = false;
		surveyed = 0;
		visited.clear();
		shortfall = Map.of();
		// Nothing is wanted yet — there is no plan this early — so worthDepositing says yes to
		// every block, which is exactly "start with an empty bag".
		wanted = Set.of();
		if (!nextChest()) {
			surveying = false;
			note("stash check: could not reach any chest from here");
			return false;
		}
		note("checking the stash (" + chests.size() + " chest(s)) before starting");
		return true;
	}

	public boolean surveying() {
		return surveying;
	}

	/** Closes this chest and moves the lap on, ending it when there are no more to look in. */
	private void nextSurveyStop() {
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			ContainerUtil.closeMenu();
		}
		visited.add(target);
		surveyed++;
		if (!nextChest()) {
			finishSurvey();
		}
	}

	/** Ends the survey, reporting what the stash turned out to hold between them. */
	private void finishSurvey() {
		surveying = false;
		clearing = false;
		stage = Stage.IDLE;
		stageTicks = 0;
		status = "idle";
		target = null;
		Set<Item> everything = new LinkedHashSet<>();
		for (BlockPos pos : chests) {
			Set<Item> here = held(pos);
			if (here != null) {
				everything.addAll(here);
			}
		}
		note("stash check done: " + surveyed + " chest(s), " + everything.size()
				+ " kind(s) available - " + short_(everything, 12));
	}

	// ---- questions the caller asks ------------------------------------------------

	public boolean busy() {
		return stage != Stage.IDLE;
	}

	/**
	 * Whether a trip could set out shortly — idle and near the end of its quiet spell.
	 *
	 * <p>Asked by the printer before it decides to stand still and wait for one. The two
	 * cooldowns mean very different things: the short one after an ordinary trip is worth
	 * waiting through, while the long one after a run that found nothing means the stash had
	 * nothing to give, and standing in a field for a minute waiting for that is simply lost
	 * time. Building whatever else can be built is the better answer there.
	 */
	public boolean readySoon() {
		return !busy() && cooldown <= TRIP_COOLDOWN;
	}

	public Stage stage() {
		return stage;
	}

	/** The waypoints still to fly on the way out, for the route overlay. Empty when idle. */
	public List<BlockPos> remainingPath() {
		if (stage != Stage.TRAVEL || path.isEmpty()) {
			return List.of();
		}
		return path.subList(Math.min(pathIndex, path.size()), path.size());
	}

	/** Where the current leg ends — the hover spot beside the chest, or null. */
	public BlockPos destination() {
		return busy() ? hover : null;
	}

	/** The chest being worked, or null — what the trip is actually for. */
	public BlockPos targetChest() {
		return target;
	}

	public String status() {
		return status;
	}

	/** True while a container we asked for is opening — the silent-screen gate. */
	public boolean expectingOpen() {
		return stage == Stage.OPEN || stage == Stage.DEPOSIT || stage == Stage.WITHDRAW
				|| stage == Stage.CLOSE || stage == Stage.RETURN;
	}

	/** Next line for the caller's event trail, or "" — call until it returns empty. */
	public String takeEvent() {
		String taken = events.pollFirst();
		return taken == null ? "" : taken;
	}

	/**
	 * Queues a line for the trail.
	 *
	 * <p>A queue rather than the single slot this used to be. The caller drains once a tick
	 * and a trip regularly produces two lines in one — "gave up" immediately followed by the
	 * next chest, or a run finishing and the next beginning — so the first was overwritten
	 * before anyone read it. The trail then showed runs completing with no run ever starting,
	 * which is a misleading picture of exactly the thing the trail exists to show.
	 */
	private void note(String line) {
		if (events.size() < 32) { // a trail nobody is draining must not grow without bound
			events.addLast(line);
		}
	}

	/**
	 * Whether a trip could plausibly bring this item back.
	 *
	 * <p>Chests never looked in count as yes. That is what lets the very first shortage send
	 * the printer to the stash at all, and one visit replaces the guess with the truth.
	 */
	public boolean mightSupply(Item item) {
		for (BlockPos pos : chests) {
			Set<Item> held = held(pos);
			if (held == null || held.contains(item)) {
				return true;
			}
		}
		return false;
	}

	/** What a chest holds, or null for "unknown" — never looked in, or looked in too long ago. */
	private Set<Item> held(BlockPos pos) {
		Long at = seenAt.get(pos);
		if (at == null || System.currentTimeMillis() - at > SEEN_TTL_MS) {
			return null;
		}
		return seen.get(pos);
	}

	public String debug() {
		return String.format("stage=%s ticks=%d chests=%d target=%s boxes=%d/%d short=%s cooldown=%d last=%s",
				stage, stageTicks, chests.size(), target == null ? "-" : target.toShortString(),
				boxesTaken, boxLimit, shortfall, cooldown, lastProblem.isEmpty() ? "-" : lastProblem);
	}

	public void reset() {
		stage = Stage.IDLE;
		stageTicks = 0;
		target = null;
		visited.clear();
		forceNextTrip = false;
		status = "idle";
	}

	/** Everything the print still needs — never deposited, whatever this trip is buying. */
	public void setKeep(Set<Item> items) {
		this.keep = items;
	}

	/** How many boxes a trip may bring, and how many slots to leave free for unloading. */
	public void setLimits(int boxes, int reserveSlots) {
		this.boxLimit = Math.max(1, boxes);
		this.reserve = Math.max(2, reserveSlots);
	}

	// ---- the run -------------------------------------------------------------------

	/**
	 * One tick of the supply run. Pass the forecast while {@link #busy()} is false to start
	 * one; keep calling every tick until it returns false.
	 *
	 * @return true while the run is driving, so the caller holds off its own work
	 */
	public boolean tick(MaterialForecast forecast, ShulkerRestock unloader,
			java.util.function.Predicate<BlockPos> buildArea, boolean emptyHere) {
		if (mc().player == null || mc().level == null || chests.isEmpty()) {
			return false;
		}
		if (AutoEat.busy()) {
			yieldToAutoEat();
			return busy();
		}
		this.unloader = unloader;
		this.buildArea = buildArea;
		this.emptyHere = emptyHere;
		// Live, every tick, not the snapshot the trip left with. A run that set out needing
		// five thousand cobblestone and comes back to a band rescanned down to fifty-two must
		// pour fifty-two into the bag, not two thousand: the boxes are emptied minutes after
		// the plan that chose them, and the last fill of a material is exactly where the gap
		// is widest. One real run overshot by 1,354 blocks this way.
		this.forecast = forecast;
		if (stage == Stage.IDLE) {
			boolean forced = forceNextTrip;
			forceNextTrip = false;
			if (forced) {
				cooldown = 0;
			} else if (cooldown > 0) {
				cooldown--;
				return false;
			}
			if (!begin(forecast)) {
				return false;
			}
		}
		int limit = switch (stage) {
			case TRAVEL -> TRAVEL_TIMEOUT;
			case UNLOAD, PACK -> UNLOAD_TIMEOUT;
			default -> STAGE_TIMEOUT;
		};
		if (++stageTicks > limit) {
			fail("timed out in " + stage);
			return busy();
		}
		switch (stage) {
			case TRAVEL -> travel();
			case OPEN -> open();
			case DEPOSIT -> deposit();
			case PACK -> pack();
			case WITHDRAW -> withdraw();
			case CLOSE -> close();
			case UNLOAD -> unload();
			case RETURN -> returnBoxes();
			default -> {
			}
		}
		return busy();
	}

	/** Closes only this run's container and yields the hand before AutoEat starts using it. */
	public void yieldToAutoEat() {
		if (expectingOpen() && mc().player != null
				&& mc().player.containerMenu != mc().player.inventoryMenu) {
			ContainerUtil.closeMenu();
		}
	}

	/**
	 * Empties the borrowed boxes into the bag, right here at the chest.
	 *
	 * <p>This is what "refill at the stash" means rather than "carry the stash around". The
	 * boxes never leave the chest's side: they come out, they are poured into the inventory,
	 * and they go back on the shelf. What flies home is thirty-odd slots of blocks and no
	 * cargo — no bag half full of shulkers, and no box handling out over the build where a
	 * dropped one is a real loss.
	 *
	 * <p>Driven urgently, because the cooldown that keeps a struggling printer from working
	 * through its boxes in the field is ten seconds of standing still when the boxes are going
	 * straight back where they came from.
	 */
	private void unload() {
		status = "emptying boxes into the bag";
		ShulkerRestock.Stage before = unloader.stage();
		if (unloader.tick(forecast, buildArea, true)) {
			if (unloader.stage() != before) {
				stageTicks = 0; // it is getting somewhere; the deadline is for being stuck
			}
			return;
		}
		stage = Stage.RETURN;
		stageTicks = 0;
	}

	/** Goes back to the chest to put the emptied boxes away before flying home. */
	private void returnBoxes() {
		status = "putting the boxes back";
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu == mc().player.inventoryMenu) {
			if (!carryingBoxes()) {
				finish(); // nothing left to give back
				return;
			}
			// The unload happens beside the chest, not on it, and the box spot can be a few
			// blocks off. Get back within arm's reach before spending a click on it.
			Vec3 here = mc().player.position();
			if (here.distanceToSqr(Vec3.atCenterOf(target)) > 4.0 * 4.0) {
				grantFlight();
				Vec3 step = Vec3.atBottomCenterOf(hover).subtract(here);
				mc().player.setDeltaMovement(step.lengthSqr() <= EASE * EASE
						? step : step.normalize().scale(EASE));
				return;
			}
			hold();
			if (stageTicks % 10 == 1) {
				InteractUtil.useOnBlock(target, Direction.UP);
			}
			return;
		}
		hold();
		if (moveDelay > 0) {
			moveDelay--;
			return;
		}
		settlePending(menu);
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!(slot.container instanceof Inventory) || stuck.contains(i)) {
				continue;
			}
			// Every box goes back, emptied or not — a box the unload could not drain is still
			// the stash's, and carrying it home is the thing this mode exists to avoid.
			if (!isShulker(slot.getItem())) {
				continue;
			}
			expect(menu, i, Map.of());
			return;
		}
		// The boxes are back and the bag is that much emptier, so another round can fit more.
		// This is the loop that turns "borrow half a bag" into "leave with a full one".
		if (++rounds < MAX_ROUNDS && !shortfall.isEmpty() && freeSlots() > reserve + 2) {
			beginRound();
			return;
		}
		ContainerUtil.closeMenu();
		// This chest is spent, but the list may not be. Going home now is what made a trip
		// come back with sixty-four cobblestone when the next chest along held the rest —
		// and then set straight off again for it. The chest was already marked visited by
		// close(), so this can only move forward.
		if (!shortfall.isEmpty() && freeSlots() > reserve && nextChest()) {
			return;
		}
		finish();
	}

	/** Whether any shulker is still aboard — the test for whether a return leg is needed. */
	private boolean carryingBoxes() {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (isShulker(inventory.getItem(slot))) {
				return true;
			}
		}
		return false;
	}

	private boolean begin(MaterialForecast forecast) {
		if (forecast == null || forecast.isEmpty()) {
			return false;
		}
		shortfall = shortfallOf(forecast);
		wanted = Set.copyOf(forecast.totals().keySet());
		unavailable.clear();
		checkingAllChests = false;
		// Normally there is no reason to fly with nothing to fetch. A clear-out is the
		// exception: the bag is full of the last band's colours, this band does not want them,
		// and every slot they hold is a slot the next fill cannot use.
		boolean dumping = clearOutWanted && !depositable().isEmpty();
		if (shortfall.isEmpty() && !dumping) {
			return false;
		}
		visited.clear();
		boxesTaken = 0;
		rounds = 0;
		packed = false;
		heldAtStart = held(wanted);
		if (!nextChest()) {
			// Two very different failures used to share one message, and the wrong one got
			// believed for hours: "the stash hasn't got it" calls for restocking the chest,
			// "I couldn't get there" calls for trying again shortly. Say which, and back off
			// for a minute only when the stash genuinely cannot help.
			boolean anyUseful = false;
			for (BlockPos pos : chests) {
				Set<Item> held = held(pos);
				if (held == null || !java.util.Collections.disjoint(held, shortfall.keySet())) {
					anyUseful = true;
					break;
				}
			}
			lastProblem = anyUseful
					? "could not reach any stash chest from here"
					: "stash has none of " + short_(shortfall.keySet());
			status = lastProblem;
			note("supply run skipped: " + lastProblem);
			cooldown = anyUseful ? TRIP_COOLDOWN : EMPTY_COOLDOWN;
			return false;
		}
		note("supply run to " + target.toShortString() + " for " + shortfall.size() + " item(s): "
				+ short_(shortfall.keySet()));
		return true;
	}

	/**
	 * Picks the next chest worth opening and routes to it, or returns false.
	 *
	 * <p>Nearest first among the ones that might hold something still wanted. Distance is the
	 * only ordering that matters here: every marked chest is somewhere the player chose, so
	 * there is no such thing as a bad one, only a far one.
	 */
	private boolean nextChest() {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		Vec3 here = mc().player.position();
		for (BlockPos pos : chests) {
			if (visited.contains(pos)) {
				continue;
			}
			Set<Item> held = held(pos);
			// A survey wants every chest, including the ones it already knows about — that
			// is the point of it: replacing belief with a fresh look.
			boolean useful = surveying || checkingAllChests || held == null;
			if (!useful) {
				for (Item item : shortfall.keySet()) {
					if (held.contains(item)) {
						useful = true;
						break;
					}
				}
			}
			// Somewhere to put things counts as useful too, or a bag full of empty boxes has
			// nowhere to go once every chest is known not to hold what we still want.
			if (!useful && !depositable().isEmpty()) {
				useful = true;
			}
			if (!useful) {
				continue;
			}
			double distance = here.distanceToSqr(Vec3.atCenterOf(pos));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos;
			}
		}
		// Cached surveys are a fast path, not authority to lose a print over. A miss opens the
		// rest of the marked stash once, refreshing its contents and proving whether the source
		// is really absent. This catches a chest that was refilled after its last remembered
		// visit and prevents "one chest opened" from becoming "the floor is finished".
		if (best == null && !surveying && !checkingAllChests && !shortfall.isEmpty()) {
			checkingAllChests = true;
			return nextChest();
		}
		if (best == null || !routeTo(best)) {
			return false;
		}
		target = best;
		stage = Stage.TRAVEL;
		stageTicks = 0;
		closest = Double.MAX_VALUE;
		pathIndex = 0;
		rerouteDelay = 0;
		// Immovability is a fact about one chest, not about the trip: a stack the last chest
		// had no room for may well fit in this one.
		stuck.clear();
		pendingSlot = -1;
		pendingStack = ItemStack.EMPTY;
		pendingSpend = Map.of();
		// "This chest hasn't got it" is a fact about that chest, not about the stash — the
		// next one along may be the one holding all the light grey.
		unavailable.clear();
		return true;
	}

	/**
	 * Finds somewhere to hover within arm's reach of the chest, and a way there.
	 *
	 * <p>A route is <em>preferred</em>, not required. Demanding a complete A* solution before
	 * the trip may start was a real and badly-disguised failure: the stash is forty blocks
	 * across a printed map art, the search budget is finite, and a partial path was treated as
	 * no path — so the run was refused outright and the printer sat on a minute's cooldown
	 * with a bag of cobblestone it could neither spend nor return. The lane driver has always
	 * handled this the other way round, flying straight and dealing with obstacles as it meets
	 * them, and the travel stage has both a stall check and a timeout to catch a route that
	 * really is blocked. A generous budget first, a straight line if that fails, and give up
	 * only when there is nowhere beside the chest a body even fits.
	 */
	private boolean routeTo(BlockPos chest) {
		List<BlockPos> spots = new ArrayList<>();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			spots.add(chest.relative(side));
		}
		spots.add(chest.above());
		for (Direction side : Direction.Plane.HORIZONTAL) {
			spots.add(chest.above().relative(side));
		}
		BlockPos from = mc().player.blockPosition();
		for (BlockPos spot : spots) {
			if (!FlightPath.fits(spot)) {
				continue;
			}
			hover = spot;
			if (from.closerThan(spot, 1.5)) {
				path = List.of();
				return true;
			}
			// A* returns its closest partial route when its budget runs out. Keep it: crossing
			// that safe part and asking again is how a long route gets round a build. Throwing it
			// away used to replace a real route with a straight line and an up-only collision
			// escape, which wedges underneath any ceiling.
			path = FlightPath.find(from, spot, TRAVEL_BUDGET);
			return true;
		}
		return false;
	}

	private void travel() {
		status = "flying to the stash";
		grantFlight();
		Vec3 here = mc().player.position();
		int before = pathIndex;
		while (pathIndex < path.size()
				&& here.distanceToSqr(Vec3.atBottomCenterOf(path.get(pathIndex))) < REACHED * REACHED) {
			pathIndex++;
		}
		if (pathIndex != before) {
			// real progress along a route A* already proved: the deadline is for being stuck,
			// not for being far
			stageTicks = 0;
			closest = Double.MAX_VALUE;
		}
		boolean onPath = pathIndex < path.size();
		Vec3 goal = Vec3.atBottomCenterOf(onPath ? path.get(pathIndex) : hover);
		double distance = here.distanceToSqr(goal);
		if (!onPath && distance < 0.6 * 0.6) {
			stage = Stage.OPEN;
			stageTicks = 0;
			return;
		}
		if (!onPath && rerouteDelay-- <= 0) {
			List<BlockPos> repaired = FlightPath.find(mc().player.blockPosition(), hover,
					TRAVEL_BUDGET);
			rerouteDelay = 10;
			if (!repaired.isEmpty()) {
				path = repaired;
				pathIndex = 0;
				closest = Double.MAX_VALUE;
				return;
			}
		}
		if (distance < closest - 0.01) {
			closest = distance;
			stageTicks = 0;
		}
		double speed = onPath || distance > 3.0 * 3.0 ? CRUISE : EASE;
		Vec3 step = goal.subtract(here);
		mc().player.setDeltaMovement(clearStep(step.lengthSqr() <= speed * speed
				? step : step.normalize().scale(speed), speed));
	}

	/**
	 * Lifts a step just enough to slide over whatever the body would clip.
	 *
	 * <p>This is only the short-distance escape while A* is being repaired. It tries vertical,
	 * sideways and downward exits; an up-only fallback turns a ceiling into a permanent trap.
	 */
	private Vec3 clearStep(Vec3 step, double speed) {
		Vec3 from = mc().player.position();
		if (FlightPath.fitsAt(from.add(step))) {
			return step;
		}
		for (double climb : new double[] { 0.2, 0.5, 1.0 }) {
			Vec3 lifted = new Vec3(step.x, Math.max(step.y, climb), step.z);
			if (FlightPath.fitsAt(from.add(lifted))) {
				return lifted;
			}
		}
		for (double drop : new double[] { -0.2, -0.5, -1.0 }) {
			Vec3 lowered = new Vec3(step.x, Math.min(step.y, drop), step.z);
			if (FlightPath.fitsAt(from.add(lowered))) {
				return lowered;
			}
		}
		Vec3 flat = new Vec3(step.x, 0.0, step.z);
		if (flat.lengthSqr() > 1.0e-6) {
			Vec3 side = new Vec3(-flat.z, 0.0, flat.x).normalize().scale(speed);
			for (Vec3 escape : new Vec3[] { side, side.scale(-1.0),
					new Vec3(side.x, -0.3, side.z), new Vec3(-side.x, -0.3, -side.z) }) {
				if (FlightPath.fitsAt(from.add(escape))) {
					return escape;
				}
			}
		}
		return Vec3.ZERO; // no blind climb into a ceiling; the route repair or timeout decides
	}

	private void open() {
		status = "opening the stash";
		hold();
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu != mc().player.inventoryMenu) {
			// Wait for the server's contents before reading them. A menu the client has just
			// built is empty, and recording *that* as what the chest holds would write the
			// stash off as barren permanently — mightSupply would answer no for ever after and
			// no trip would be made again. The tell is stateId: only the server's content
			// packet ever stamps a non-zero one on it.
			if (menu.getStateId() == 0) {
				return;
			}
			remember(menu);
			if (surveying) {
				if (clearing && !cleared) {
					// One chest takes the bag; the rest of the lap is just looking.
					stage = Stage.DEPOSIT;
					stageTicks = 0;
					moveDelay = 0;
					return;
				}
				// Looked in, nothing taken. That is the whole of a survey visit.
				nextSurveyStop();
				return;
			}
			stage = Stage.DEPOSIT;
			stageTicks = 0;
			moveDelay = 0;
			return;
		}
		if (stageTicks % 10 == 1) { // one attempt, retried rather than spammed
			InteractUtil.useOnBlock(target, Direction.UP);
		}
	}

	/**
	 * Puts back everything the print has no use for, before taking anything out.
	 *
	 * <p>Order matters: depositing first is what makes room for the boxes, and a trip that
	 * withdrew first would fill the bag and then have nowhere to put the emptied boxes it came
	 * to return.
	 */
	private void deposit() {
		status = "putting back what is not needed";
		hold();
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu == mc().player.inventoryMenu) {
			stage = Stage.CLOSE;
			return;
		}
		if (moveDelay > 0) {
			moveDelay--;
			return;
		}
		settlePending(menu);
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!(slot.container instanceof Inventory) || stuck.contains(i)) {
				continue; // the chest's side, or a stack this chest has no room for
			}
			if (!worthDepositing(slot.getItem())) {
				continue;
			}
			expect(menu, i, Map.of());
			return;
		}
		// The chest turned something away and we are still carrying blocks it should have -
		// so it is full, and the only way to hand them back is inside a box. Once per visit:
		// a second attempt would find nothing new to pack.
		if (!packed && !stuck.isEmpty() && !depositable().isEmpty() && emptyBoxAboard()) {
			packed = true;
			ContainerUtil.closeMenu();
			stage = Stage.PACK;
			stageTicks = 0;
			return;
		}
		if (surveying) {
			// A survey never withdraws; the bag is empty now and that was the point.
			cleared = true;
			note("bag emptied into " + target.toShortString() + " - starting clear");
			nextSurveyStop();
			return;
		}
		// Re-read the shortfall now that the bag has been through the deposit. It was worked
		// out at the start of the trip against a bag that has since changed, and buying to a
		// stale figure is how a trip hands back more than it fetches.
		shortfall = shortfallOf(forecast);
		beginRound();
	}

	/**
	 * Packs what the chest would not take into a spare box, then goes back to depositing.
	 *
	 * <p>Costs no chest space: the box being filled came out of that chest, so its slot is
	 * still waiting for it. This is what stops a bag silting up with blocks it can never give
	 * back — and a silted bag is why later trips could only borrow a box or two and came home
	 * with ten of something instead of a stack.
	 */
	private void pack() {
		status = "packing leftovers into a spare box";
		ShulkerRestock.Stage before = unloader.stage();
		if (unloader.stowTick(surplus(), buildArea)) {
			if (unloader.stage() != before) {
				stageTicks = 0;
			}
			return;
		}
		// back to the chest to put the now-loaded box away
		stuck.clear();
		pendingSlot = -1;
		pendingStack = ItemStack.EMPTY;
		pendingSpend = Map.of();
		stage = Stage.OPEN;
		stageTicks = 0;
	}

	/** The blocks the chest refused, as a count map for the packer. */
	private Map<Item, Integer> surplus() {
		Map<Item, Integer> out = new HashMap<>();
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (worthDepositing(stack) && !isShulker(stack)) {
				out.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return out;
	}

	/** Whether a box with room to pack into is aboard. */
	private boolean emptyBoxAboard() {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isShulker(stack) && contents(stack).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Opens a withdrawal round, sizing how much of the free space may go to boxes. */
	private void beginRound() {
		roundBoxes = 0;
		roundCap = emptyHere ? Math.max(1, freeSlots() / 2) : boxLimit;
		stage = Stage.WITHDRAW;
		stageTicks = 0;
	}

	/**
	 * Whether a stack in our bag belongs in the chest rather than on the trip.
	 *
	 * <p>Blocks and boxes only. Everything else — the pickaxe the shulkers are broken with,
	 * food, whatever else you fly with — is left strictly alone. "Dump what the print does not
	 * need" has to stop short of the tools the print is done with, and the cost of being
	 * careful here is a couple of slots, against a printer that deposits its own pickaxe and
	 * can never open another box.
	 */
	private boolean worthDepositing(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (isShulker(stack)) {
			return contents(stack).isEmpty(); // emptied boxes go home; loaded ones are the cargo
		}
		if (!(stack.getItem() instanceof BlockItem)) {
			return false;
		}
		Item item = stack.getItem();
		// Two questions, and they were being answered as one. "Does this trip's shopping list
		// mention it" is not the same as "does the print still need it": the list narrows to
		// the material the current pass is laying, while the bag legitimately carries what the
		// passes after it will lay. Conflating them made the printer hand back the carpet it
		// had just fetched, then buy it again — cobblestone, carpets, cobblestone, carpets,
		// with a "supply run done: 0 blocks aboard" in the middle of it.
		if (!wanted.contains(item) && !keep.contains(item)) {
			return true; // nothing here or ahead wants it; the chest can have it
		}
		// It is wanted. Trading loose blocks for a box of the same thing only makes sense when
		// the box is coming *with* us: it buys a slot, and slots are what cap a cargo run.
		// Emptying at the chest it buys nothing at all — the box is drained into these very
		// slots and handed straight back — so the "trade" is a deposit and a withdrawal that
		// cancel. Worse, they do not quite cancel: the shortfall was worked out before the
		// deposit, so the trip gives back forty-two loose, buys the forty-seven it was already
		// planning to, and comes home five better off than it left. A real run did exactly
		// that twice in twenty seconds.
		return !emptyHere && boxedHere.contains(item) && shortfall.containsKey(item);
	}

	/**
	 * Settles the previous click before making another.
	 *
	 * <p>A container click is a request, not a result. Reading back whether the slot actually
	 * changed is what turns "I clicked it" into "it moved", and it is the difference between
	 * skipping a stack that cannot go anywhere and hammering it forever.
	 */
	private void settlePending(AbstractContainerMenu menu) {
		if (pendingSlot < 0) {
			return;
		}
		ItemStack now = menu.getSlot(pendingSlot).getItem();
		if (ItemStack.isSameItemSameComponents(now, pendingStack)
				&& now.getCount() == pendingStack.getCount()) {
			stuck.add(pendingSlot);
			// nothing moved, so the shortfall it was booked against is still outstanding
			pendingSpend.forEach((item, count) -> shortfall.merge(item, count, Integer::sum));
			if (stage == Stage.WITHDRAW && isShulker(pendingStack)) {
				boxesTaken--; // it never came aboard
				roundBoxes--;
			}
		}
		pendingSlot = -1;
		pendingStack = ItemStack.EMPTY;
		pendingSpend = Map.of();
	}

	/** Records a click so {@link #settlePending} can judge it next tick. */
	private void expect(AbstractContainerMenu menu, int slot, Map<Item, Integer> booked) {
		pendingSlot = slot;
		pendingStack = menu.getSlot(slot).getItem().copy();
		pendingSpend = booked;
		ContainerUtil.click(menu, slot, 0, ContainerInput.QUICK_MOVE);
		moveDelay = MOVE_DELAY;
		stageTicks = 0; // real progress, judged next tick — a long shopping list is not a stall
	}

	/** Whether anything in the bag is worth putting back — see {@link #nextChest}. */
	private Set<Item> depositable() {
		Set<Item> found = new LinkedHashSet<>();
		if (mc().player == null) {
			return found;
		}
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (worthDepositing(stack)) {
				found.add(stack.getItem());
			}
		}
		return found;
	}

	/**
	 * Takes the boxes that best serve the work ahead, then loose blocks to top up.
	 *
	 * <p>Boxes are scored by how much of the outstanding shortfall they actually cover, and
	 * taken greedily. Scoring beats taking whatever is nearest for the obvious reason: a chest
	 * of thirty boxes usually holds several of the same colour, and a trip that came back with
	 * five boxes of the block already carried has flown for nothing.
	 */
	private void withdraw() {
		status = "loading up";
		hold();
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu == mc().player.inventoryMenu) {
			stage = Stage.CLOSE;
			return;
		}
		if (moveDelay > 0) {
			moveDelay--;
			return;
		}
		settlePending(menu);
		// Emptied here, boxes are borrowed rather than cargo, so the fleet-wide limit does not
		// apply — only how much of this round's free space they may occupy.
		boolean full = emptyHere
				? roundBoxes >= roundCap || freeSlots() <= reserve
				: boxesTaken >= boxLimit || freeSlots() <= reserve;
		if (full) {
			stage = Stage.CLOSE; // full enough; the rest is somebody else's trip
			stageTicks = 0;
			return;
		}
		// Take whatever unblocks the route soonest. Scoring boxes by how much of the total
		// shortfall they cover looks sensible and is not: over a whole band every colour is
		// short by hundreds, so a box of the green needed once at the very end scores like the
		// light grey needed from the first lane, and a trip comes home unable to lay block
		// one. The bottleneck — the first material the route runs out of — is the only thing
		// worth buying, and buying it moves the bottleneck along to the next one. Repeated,
		// that is a greedy walk up the route, which is exactly the order the printer needs.
		Map<Item, Integer> have = aboard(forecast.totals().keySet());
		int bestSlot = -1;
		boolean bestIsBox = false;
		for (int attempt = 0; attempt < MAX_BOTTLENECKS && bestSlot < 0; attempt++) {
			Item bottleneck = forecast.firstShortfall(have, item -> !unavailable.contains(item));
			if (bottleneck == null) {
				break; // the route is covered by what is already aboard
			}
			int most = 0;
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.getSlot(i);
				if (slot.container instanceof Inventory || stuck.contains(i)) {
					continue; // ours, or a stack that would not come out
				}
				ItemStack stack = slot.getItem();
				if (stack.isEmpty()) {
					continue;
				}
				int held = 0;
				boolean box = isShulker(stack);
				if (box) {
					for (ItemStack inside : contents(stack)) {
						if (inside.is(bottleneck)) {
							held += inside.getCount();
						}
					}
				} else if (stack.is(bottleneck)) {
					held = stack.getCount();
				}
				if (held > most) {
					most = held;
					bestSlot = i;
					bestIsBox = box;
				}
			}
			if (bestSlot < 0) {
				// This chest cannot help with that one. Note it and ask what the route wants
				// after it, rather than stopping the trip over a single colour.
				unavailable.add(bottleneck);
			}
		}
		if (bestSlot < 0) {
			stage = Stage.CLOSE; // nothing here unblocks anything
			stageTicks = 0;
			return;
		}
		ItemStack taking = menu.getSlot(bestSlot).getItem();
		// Book what this covers before the click: the slot empties in place, so reading it
		// afterwards reports air — the same trap that once logged every restock take under
		// "air" and let a box be drained past its budget. Booked rather than committed, so
		// settlePending can hand it back if the stack turns out not to move.
		Map<Item, Integer> booked = new HashMap<>();
		if (bestIsBox) {
			for (ItemStack inside : contents(taking)) {
				spend(booked, inside.getItem(), inside.getCount());
			}
			boxesTaken++;
			roundBoxes++;
		} else {
			spend(booked, taking.getItem(), taking.getCount());
		}
		expect(menu, bestSlot, booked);
	}

	/** Takes {@code count} off the shortfall, recording how much was actually taken off. */
	private void spend(Map<Item, Integer> booked, Item item, int count) {
		int have = shortfall.getOrDefault(item, 0);
		if (have <= 0) {
			return;
		}
		int used = Math.min(have, count);
		booked.merge(item, used, Integer::sum);
		if (have - used > 0) {
			shortfall.put(item, have - used);
		} else {
			shortfall.remove(item);
		}
	}

	private void close() {
		status = "closing the stash";
		hold();
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			ContainerUtil.closeMenu();
		}
		// A survey that lands here got its menu closed under it mid-deposit; carry on with the
		// lap rather than treating it as the end of a supply run it never was.
		if (surveying) {
			cleared = true;
			nextSurveyStop();
			return;
		}
		visited.add(target);
		// Boxes drain here, before anything else happens: the return leg needs this chest, and
		// wandering to the next one first would leave the empties at the wrong address.
		if (emptyHere && carryingBoxes()) {
			stage = Stage.UNLOAD;
			stageTicks = 0;
			return;
		}
		// Still short, still room, and another chest to try: keep going rather than fly home
		// half loaded and come straight back.
		if (!shortfall.isEmpty() && boxesTaken < boxLimit && freeSlots() > reserve
				&& nextChest()) {
			return;
		}
		finish();
	}

	private void finish() {
		stage = Stage.IDLE;
		stageTicks = 0;
		status = "idle";
		surveying = false;
		clearOutWanted = false; // asked for and done, whatever the trip otherwise achieved
		// Judged on what the bag actually gained, not on whether clicks happened. Those are
		// different questions, and answering the wrong one cost a real run dearly: boxes were
		// borrowed, the unload could not place them, they were dutifully put back, and the trip
		// reported success because items had moved. Coverage was unchanged, so the trigger fired
		// again immediately — three round trips in forty seconds, achieving nothing each time.
		// A trip that did not improve the bag is a trip not worth repeating soon.
		int gained = held(wanted) - heldAtStart;
		// Worded for what was actually established. `unavailable` is filled per chest, so
		// "stash has no cobblestone" was a claim about the whole stash made on the evidence of
		// the one or two chests this trip got round to opening before the bag filled up — and
		// it read as "go and mine some more", for a material sitting in the next chest along.
		String missing = unavailable.isEmpty() ? ""
				: " - no " + short_(unavailable) + " in the " + visited.size() + " chest(s) opened";
		// Did it get what it came for? That is the question, and the net bag change is not it.
		//
		// A trip deposits before it withdraws — deliberately, since a chest that holds a
		// colour in boxes can hand back a slot's worth of loose blocks and return a stack.
		// Both halves count against `gained`, so a run that put back thirty and took forty-one
		// scored eleven and was written off as "the stash could only spare 11 blocks". The
		// punishment for that verdict is the long cooldown, so a *successful* trip locked the
		// printer out of the stash for a minute, and it went back to building understocked.
		// One real run showed exactly this: taken={black=7, gray=24, light_gray=10}, reported
		// as eleven, then a 1161-tick lockout.
		boolean satisfied = shortfall.isEmpty() || gained >= WORTHWHILE;
		note(gained > 0 || satisfied
				? "supply run done: " + gained + " blocks aboard"
						+ (shortfall.isEmpty() ? " (list cleared)" : " - still short " + short_(shortfall.keySet()))
						+ missing
				: "supply run brought nothing back" + missing);
		cooldown = satisfied ? TRIP_COOLDOWN : EMPTY_COOLDOWN;
		if (!satisfied) {
			lastProblem = gained > 0
					? "the stash could only spare " + gained + " blocks"
					: "the stash had nothing the print could use";
		}
		target = null;
	}

	/** How much of the material the print uses is aboard, loose or boxed. */
	private int held(Set<Item> materials) {
		int total = 0;
		for (int count : aboard(materials).values()) {
			total += count;
		}
		return total;
	}

	private void fail(String why) {
		lastProblem = why;
		status = why;
		note((surveying ? "stash check gave up on this chest: " : "supply run gave up: ") + why);
		if (mc().player != null && mc().player.containerMenu != mc().player.inventoryMenu) {
			ContainerUtil.closeMenu();
		}
		if (target != null) {
			visited.add(target);
		}
		// One unreachable chest is not a reason to abandon the lap; the rest are still worth
		// looking in, and the whole value of the survey is in the chests it *can* reach.
		if (surveying) {
			if (!nextChest()) {
				finishSurvey();
			}
			return;
		}
		stage = Stage.IDLE;
		stageTicks = 0;
		target = null;
		cooldown = TRIP_COOLDOWN;
	}

	/** Records what a chest holds, counting inside its boxes — see {@link #seen}. */
	private void remember(AbstractContainerMenu menu) {
		Set<Item> held = new HashSet<>();
		boxedHere.clear();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container instanceof Inventory) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			held.add(stack.getItem());
			for (ItemStack inside : contents(stack)) {
				held.add(inside.getItem());
				boxedHere.add(inside.getItem()); // available densely, so loose ones can go back
			}
		}
		seen.put(target, held);
		seenAt.put(target, System.currentTimeMillis());
	}

	/** What the forecast wants that the bag cannot cover, boxes included. */
	private Map<Item, Integer> shortfallOf(MaterialForecast forecast) {
		Map<Item, Integer> result = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : forecast.totals().entrySet()) {
			int missing = entry.getValue() - carried(entry.getKey());
			if (missing > 0) {
				result.put(entry.getKey(), missing);
			}
		}
		return result;
	}

	/**
	 * How much of each interesting material is aboard, loose or boxed, in one pass.
	 *
	 * <p>One pass rather than one per material: the bottleneck search asks this every tick of
	 * the withdrawal, and going per-item would re-read all thirty-six slots and re-parse every
	 * box's contents once per colour — fourteen times the work for the same answer.
	 */
	private Map<Item, Integer> aboard(Set<Item> interesting) {
		Map<Item, Integer> have = new HashMap<>();
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (interesting.contains(stack.getItem())) {
				have.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
			for (ItemStack inside : contents(stack)) {
				if (interesting.contains(inside.getItem())) {
					have.merge(inside.getItem(), inside.getCount(), Integer::sum);
				}
			}
		}
		return have;
	}

	/** How much of an item the player has, loose or inside a carried box. */
	private int carried(Item item) {
		Inventory inventory = mc().player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
			for (ItemStack inside : contents(stack)) {
				if (inside.is(item)) {
					total += inside.getCount();
				}
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

	/** A few item names without namespaces, for a chat line that has to stay one line. */
	private static String short_(Set<Item> items) {
		return short_(items, 4);
	}

	/** As {@link #short_(Set)} with a chosen cap — the stash listing wants the whole truth. */
	private static String short_(Set<Item> items, int limit) {
		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (Item item : items) {
			if (shown == limit) {
				text.append(" +").append(items.size() - shown).append(" more");
				break;
			}
			if (shown > 0) {
				text.append(", ");
			}
			String id = item.getDescriptionId();
			text.append(id.substring(id.lastIndexOf('.') + 1));
			shown++;
		}
		return text.toString();
	}

	private static boolean isShulker(ItemStack stack) {
		return stack.getItem() instanceof BlockItem block
				&& block.getBlock() instanceof ShulkerBoxBlock;
	}

	private static List<ItemStack> contents(ItemStack stack) {
		ItemContainerContents held = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
		if (held == null) {
			return List.of();
		}
		List<ItemStack> items = new ArrayList<>();
		held.allItemsCopyStream().forEach(items::add);
		return items;
	}

	/** Parks at the chest with flight asserted, so vanilla cannot sag us out of reach mid-click. */
	private void hold() {
		grantFlight();
		mc().player.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * Turns flight on and tells the server, but only when something changed.
	 *
	 * <p>Setting the flag without sending an abilities packet leaves the server thinking the
	 * player is in free fall for the length of the trip, and it bills the arrival. Guarded on
	 * change, so re-asserting every tick costs nothing.
	 */
	private void grantFlight() {
		net.minecraft.world.entity.player.Abilities abilities = mc().player.getAbilities();
		if (abilities.mayfly && abilities.flying) {
			return;
		}
		abilities.mayfly = true;
		abilities.flying = true;
		mc().player.onUpdateAbilities();
	}
}
