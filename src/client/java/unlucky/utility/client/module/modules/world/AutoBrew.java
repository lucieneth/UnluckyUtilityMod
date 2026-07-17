package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.BrewQueueSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.BrewingSolver;
import unlucky.utility.client.util.BrewingSolver.State;
import unlucky.utility.client.util.BrewingSolver.Step;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.RotationManager;

/**
 * Brews potions. Turn it on next to your brewing setup and it finds the stands and
 * chests itself; or turn Discover off and show it each one by opening it.
 *
 * <h2>The queue</h2>
 * {@code Queue} is an ordered list — "1 Strength, then 10 Night Vision, then 5
 * Invisibility" — worked front to back, one order at a time. It's a list rather than
 * a single potion because both things a set would throw away matter: the order, and
 * duplicates-as-counts. Editing it mid-run restarts the run, since the counts it was
 * measuring against no longer exist. An order this server can't brew is reported and
 * skipped rather than stalling the ones behind it.
 *
 * <h2>Finding the setup</h2>
 * Stands are settled from the <b>block</b> — a brewing stand is one on sight, nothing
 * to open. Containers are the opposite: the client is never told what's in a chest it
 * hasn't opened (the block entity exists for the lid animation; its inventory is empty
 * client-side), so the block only makes it a candidate and it gets looked inside once.
 * That asymmetry is the whole shape of {@link #scan} and {@link #peek}.
 *
 * <p>Roles come from what's <b>actually inside</b>, never from the block type, so a
 * barrel is as good as a chest and one container holding both jobs gets both. There
 * are no fixed "bottle chest" and "reagent chest" slots: any container holding
 * something brewable joins {@link #chests}, and {@link #pickChest} decides per fetch,
 * preferring one it remembers holding the thing and falling through to the others when
 * that memory is stale. Keep bottles in one and reagents in three others if you like.
 *
 * <p>Positions are per-session and per-world on purpose: a saved coordinate that
 * silently points into a different world is worse than asking again.
 *
 * <h2>Several stands</h2>
 * Show it as many as you like; they're worked <b>round-robin</b>. A brew takes 20
 * seconds, and the stand tells us exactly how many ticks are left — so instead of
 * standing there watching one cook, it parks that stand for precisely that long and
 * goes and loads the next. Three stands is three batches in flight.
 *
 * <p>That makes "how many more do I need?" subtler than it looks: an order of 7 across
 * 3 stands must not have each of them start a full batch of 3. So each stand's bottle
 * count is re-read from the stand itself on every visit, and a stand may only take the
 * order's shortfall <em>minus what the other stands are already brewing</em>. The
 * counts are ground truth re-read per visit, never a running tally that can drift.
 *
 * <h2>Why it keeps opening and closing things</h2>
 * You can only have <b>one container open at a time</b> — that's the server's rule,
 * and every click is checked against the container id it opened for you, so "reach
 * into the chest while the stand is up" does not exist. The machine therefore cycles:
 * work the stand until something is missing, close it, open the chest that has the
 * missing thing, pull it, come back. The stand keeps brewing while we're away, which
 * is why the trip is nearly free.
 *
 * <p>That cycling forces one non-obvious rule: <b>decisions about the stand are made
 * while the stand is open</b>, because the moment we walk to a chest we can no longer
 * see the stand's slots. So each visit does as much as it can and leaves at most one
 * unmet need behind.
 *
 * <h2>Watching it work</h2>
 * It <b>turns to face</b> whatever it's about to touch, over {@code Turn speed}
 * degrees a tick, and only reaches once it's aimed. That's a server-side spoof, so
 * your camera never moves — but the model does, for you in F5 and for everyone else
 * always. It replaced a snap that was invisible either way (one tick is about three
 * frames) and that no human hand could produce.
 *
 * <p>{@code Screens} picks whether you see the windows: <b>Silent</b> works the
 * containers with nothing on screen (see {@code GuiMixin} for why that's possible),
 * <b>Visible</b> lets them open so you can watch it click through them. Orthogonal to
 * the turning — the body language is on the wire either way.
 *
 * <h2>The rest</h2>
 * The recipe chain is derived by {@link BrewingSolver} asking vanilla, so the potion
 * list is whatever this server can brew. Bottles already part-way down the chain get
 * reused. Empty glass bottles are filled from any water source or cauldron in reach.
 * Nothing pathfinds — everything must be within your own reach, and it says so when
 * it isn't.
 *
 * <p>It takes <b>only what it needs</b>: exactly the shortfall, never a whole stack.
 * A chest with 64 glass bottles owes 7 of them for an order of 7, and one blaze powder
 * is twenty brews. That needs {@link #takeExactly}, because QUICK_MOVE can only move
 * all of a stack.
 *
 * <h2>Storage</h2>
 * A container with a <b>hopper straight under it</b> is where potions go — that's the
 * whole rule, and it means a storage chest is told apart from a supply chest by how
 * it's built rather than by anything you have to set. Storage never joins
 * {@link #chests}: it's an output, and treating it as an input would have us fetching
 * our own finished potions back out. Only finished product goes in — see
 * {@link #storable}, which keeps intermediates in the bag for reuse.
 *
 * <p>Two rules keep it honest: bottles in one stand must all agree (a single reagent
 * transforms all three slots, so a mixed stand can't progress and gets emptied), and
 * {@code produced} only counts bottles actually pulled back out, never predicted.
 */
public class AutoBrew extends Module {
	// BrewingStandMenu's own slot constants are private, so they're restated here,
	// read off its constructor's addSlot order: three PotionSlots, then the
	// ingredient, then the fuel. Player slots are found by identity instead (see
	// findSlot), which needs no constant and works for chests of any size.
	private static final int BOTTLE_START = 0;
	private static final int BOTTLE_END = 3;
	private static final int INGREDIENT_SLOT = 3;
	private static final int FUEL_SLOT = 4;

	/** Nothing in reach changed, so back off rather than spin: ~5s. */
	private static final int STALL = 100;

	public final BrewQueueSetting queue = add(new BrewQueueSetting("Queue",
			"What to brew, in order. Left-click a potion to add one, right-click to remove one"));
	public final ModeSetting screens = add(new ModeSetting("Screens",
			"Silent: work the containers with no windows on screen. Visible: watch it click through them",
			"Silent", "Silent", "Visible"));
	public final NumberSetting turnSpeed = add(new NumberSetting("Turn speed",
			"Degrees per tick it turns to face things. 180 snaps instantly", 20, 1, 180, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between actions", 2, 0, 20, 1));
	public final BooleanSetting discover = add(new BooleanSetting("Discover",
			"Find stands and look inside containers within reach, instead of waiting to be shown them", true));
	public final BooleanSetting emptyPotions = add(new BooleanSetting("Empty potions",
			"Put finished potions into storage: a container with a hopper under it", true));
	public final BooleanSetting fuel = add(new BooleanSetting("Fuel", "Keep the stand fed with blaze powder", true));
	public final BooleanSetting fillBottles = add(new BooleanSetting("Fill bottles", "Fill empty glass bottles from water in reach", true));
	public final BooleanSetting disableWhenDone = add(new BooleanSetting("Disable when done", "Turn the module off once the whole queue is brewed", true));

	/** Set at click time by MultiPlayerGameModeMixin — see {@link #onBlockUsed}. */
	private static BlockPos lastUsed;

	/** Every stand you've shown it. Worked round-robin — see {@link #pickStand}. */
	private final List<BlockPos> stands = new ArrayList<>();
	/** Ticks left on each stand's brew, so a busy one is skipped instead of re-opened. */
	private final Map<BlockPos, Integer> standBusy = new HashMap<>();
	/** In-chain bottles sitting in each stand. Re-read from the stand on every visit. */
	private final Map<BlockPos, Integer> standLoad = new HashMap<>();
	private int standIndex;
	/** Every container you've shown it (or it found) that held something brewable. */
	private final List<BlockPos> chests = new ArrayList<>();
	/**
	 * What was in each chest last time we looked. Whole stacks, not item ids, because
	 * a potion's identity lives in its components — an id can't tell Awkward from
	 * Strength. Only a hint for search order; the live menu is always the truth.
	 */
	private final Map<BlockPos, List<ItemStack>> chestSeen = new HashMap<>();
	/** Chests already checked for the current fetch and found wanting. */
	private final java.util.Set<BlockPos> fetchTried = new java.util.HashSet<>();
	/** Where finished potions go: containers with a hopper beneath them. */
	private final List<BlockPos> storage = new ArrayList<>();
	/** Discovery: containers found in reach and still to be looked inside. */
	private final List<BlockPos> toPeek = new ArrayList<>();
	private final java.util.Set<BlockPos> peeked = new java.util.HashSet<>();
	private BlockPos lastScan;
	private int scanCooldown;
	/** Where the currently open menu lives, or null when only the inventory is open. */
	private BlockPos menuPos;
	private AbstractContainerMenu lastMenu;
	/** Container we asked for, so we don't re-learn our own opens. */
	private BlockPos expecting;
	/** The open menu has been given a role; stop re-reading it. */
	private boolean classified;
	/** True for the instant we're closing a container ourselves — see {@link #closeMenu}. */
	private boolean closing;
	/** Assignments are world-local; a level change makes every coordinate a lie. */
	private Level lastLevel;

	// the single outstanding need, carried while we go to a chest for it
	private Predicate<ItemStack> fetchMatch;
	private String fetchLabel;
	private int fetchAmount;
	private boolean fetchIsBottles;

	private boolean filling;
	private int parkedFrom = -1;
	private int prevSelected = -1;

	/** Index into the queue, and how many of that order are already in your bags. */
	/** Which order each stand is working. Stands are allocated to <b>work</b>, not to
	 * orders: 9 bottles of one order is three batches and takes three stands, exactly as
	 * four small orders take four. */
	private final Map<BlockPos, Integer> standOrder = new HashMap<>();
	/** Bottles pulled back out, per order. Counted on the way out, never predicted. */
	private final Map<Integer, Integer> producedPer = new HashMap<>();
	/** Orders we've already said "done" about, so the chat line fires once. */
	private final java.util.Set<Integer> announced = new java.util.HashSet<>();
	/** The order whose stand we're servicing — what a fetch or a fill is for. */
	private int activeOrder = -1;
	private int cooldown;
	/** The queue as it looked when we started; an edit mid-run restarts the run. */
	private String queueStamp = "";
	/** One line saying what the machine is doing, for the Brewing HUD widget. */
	private String status = "Idle";
	private String warned = "";

	public AutoBrew() {
		super("AutoBrew", "Brews potions from assigned chests", Category.WORLD);
	}

	/** Called from the interact mixin for every block we right-click. */
	public static void onBlockUsed(BlockPos pos) {
		lastUsed = pos;
	}

	/**
	 * Whether {@code GuiMixin} should swallow the window for the container that's
	 * opening right now. True only while we're waiting on an open <b>we</b> asked for,
	 * so containers the player opens by hand still show — which they must, since
	 * opening one by hand is how a chest gets assigned in the first place.
	 */
	public boolean suppressesScreens() {
		return isEnabled() && screens.is("Silent") && expecting != null;
	}

	/**
	 * Whether {@code GuiMixin} should swallow a {@code setScreen(null)} right now.
	 *
	 * <p>True only while {@link #closeMenu} is closing a container of ours, and that
	 * narrow window is the whole point: vanilla's close path ends in
	 * {@code gui.setScreen(null)}, which doesn't care that the screen it's clearing
	 * belongs to you rather than to the container. Since we close one every few ticks,
	 * chat and the pause menu were being slammed shut a tick after you opened them.
	 */
	public boolean suppressesClose() {
		return isEnabled() && screens.is("Silent") && closing;
	}

	/**
	 * Closes the container we have open, without touching the player's screen.
	 *
	 * <p>There's no vanilla call for "close the menu but leave my GUI alone" —
	 * {@code LocalPlayer.closeContainer()} always ends in {@code setScreen(null)}, and
	 * the plain {@code Player.closeContainer()} underneath it is protected. So we flag
	 * the call and let GuiMixin drop that one screen clear.
	 */
	private void closeMenu() {
		closing = true;
		try {
			mc().player.closeContainer();
		} finally {
			closing = false;
		}
	}

	@Override
	protected void onEnable() {
		lastScan = null; // sweep again from wherever you are now
		toPeek.clear();
		peeked.clear();
		scanCooldown = 0;
		producedPer.clear();
		standOrder.clear();
		announced.clear();
		activeOrder = -1;
		cooldown = 0;
		queueStamp = "";
		warned = "";
		clearFetch();
		filling = false;
	}

	@Override
	protected void onDisable() {
		restoreHand();
		clearFetch();
		filling = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gameMode == null || mc().level == null) {
			return;
		}
		if (mc().level != lastLevel) {
			lastLevel = mc().level;
			stands.clear();
			standBusy.clear();
			standLoad.clear();
			chests.clear();
			chestSeen.clear();
			storage.clear();
			toPeek.clear();
			peeked.clear();
			lastScan = null;
		}
		Map<State, List<Step>> paths = BrewingSolver.solve(mc().level.potionBrewing());
		learn(paths);

		// an edit to the queue mid-run restarts it: the counts it was measuring
		// against no longer exist, so carrying them forward would just be wrong
		String stamp = queue.get().toString();
		if (!stamp.equals(queueStamp)) {
			queueStamp = stamp;
			producedPer.clear();
			standOrder.clear();
			announced.clear();
			activeOrder = -1;
			warned = "";
		}
		if (queue.get().isEmpty()) {
			status = "Nothing queued";
			warn("nothing queued — open the Queue setting and left-click the potions you want");
			return;
		}
		if (allDone(paths)) {
			finish();
			return;
		}
		standBusy.replaceAll((pos, ticks) -> Math.max(0, ticks - 1));
		// re-sweep as you walk: the point is to notice the station you just walked up to
		if (discover.get() && --scanCooldown <= 0) {
			scanCooldown = 20;
			BlockPos here = mc().player.blockPosition();
			if (lastScan == null || here.distSqr(lastScan) > 4) {
				lastScan = here;
				scan();
			}
		}
		if (stands.isEmpty()) {
			status = "No brewing stand";
			warn(discover.get()
					? "no brewing stand in reach — walk up to one, or open it while I'm on"
					: "open your brewing stand once while I'm on, so I know which one it is");
			return;
		}
		if (cooldown-- > 0) {
			return;
		}
		if (filling) {
			State fillTarget = targetOf(activeOrder);
			if (fillTarget == null || !paths.containsKey(fillTarget)) {
				filling = false; // the order it was for went away
				return;
			}
			status = "Filling bottles for " + BrewingSolver.label(fillTarget);
			fillBottles(paths.get(fillTarget), fillTarget);
			return;
		}
		if (peek()) {
			status = "Looking in containers (" + toPeek.size() + " left)";
			return;
		}
		if (deposit(paths)) {
			return;
		}
		if (fetchMatch != null) {
			runFetch();
			return;
		}
		BlockPos stand = pickStand();
		if (stand == null) {
			status = "Waiting on brews (" + brewSecondsLeft() + "s)";
			return; // every stand is mid-brew: nothing to do but let them cook
		}
		Integer order = orderFor(stand, paths);
		if (order == null) {
			// every order is already covered by the other stands — this one has no work
			standBusy.put(stand, 40);
			standIndex = (standIndex + 1) % stands.size();
			return;
		}
		activeOrder = order;
		State target = targetOf(order);
		if (!ensureOpen(stand)) {
			return;
		}
		if (!(mc().player.containerMenu instanceof BrewingStandMenu menu)) {
			warn("the block at " + pretty(stand) + " isn't a brewing stand any more — forgetting it");
			forget(stand);
			return;
		}
		runStand(menu, stand, order, paths.get(target), target);
	}

	// ---------------------------------------------------------------- learning

	/**
	 * Reads whatever container is open and remembers it. Every open teaches us
	 * something — yours and ours alike — so this no longer skips the ones we asked
	 * for: a fetch is also the best chance to refresh what's in that chest.
	 *
	 * <p>Roles aren't sticky slots any more. A container that holds anything brewable
	 * joins {@link #chests}, and which one gets used for what is decided per fetch by
	 * {@link #pickChest}. That's what lets you keep bottles in one chest, reagents in
	 * three others, and both in a fifth.
	 */
	private void learn(Map<State, List<Step>> paths) {
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (menu == mc().player.inventoryMenu) {
			menuPos = null;
			lastMenu = null;
			classified = false;
			return;
		}
		if (menu != lastMenu) {
			lastMenu = menu;
			menuPos = lastUsed;
			expecting = null;
			classified = false;
		}
		if (classified || menuPos == null || !synced(menu)) {
			return; // its contents haven't landed yet — see synced()
		}
		if (menu instanceof BrewingStandMenu) {
			classified = true;
			if (!stands.contains(menuPos)) {
				stands.add(menuPos);
				ChatUtil.info("§dAutoBrew§7: stand " + stands.size() + " at " + pretty(menuPos));
			}
			return;
		}
		boolean bottles = findSlot(menu, false, this::isBottle) >= 0;
		boolean reagents = findSlot(menu, false, stack -> isReagent(stack, paths)) >= 0;
		if (!bottles && !reagents) {
			return; // holds nothing we want; leave it unclassified rather than latch "empty"
		}
		classified = true;
		if (isStorage(menuPos)) {
			// Storage is an output, never an input. Letting it join `chests` would have
			// us fetching our own finished potions straight back out of it.
			if (!storage.contains(menuPos)) {
				storage.add(menuPos);
				ChatUtil.info("§dAutoBrew§7: potion storage at " + pretty(menuPos));
			}
			return;
		}
		remember(menu, menuPos);
		if (!chests.contains(menuPos)) {
			chests.add(menuPos);
			ChatUtil.info("§dAutoBrew§7: " + (bottles && reagents ? "bottles + reagents" : bottles ? "bottles" : "reagents")
					+ " at " + pretty(menuPos) + " (" + chests.size() + " chest" + (chests.size() == 1 ? "" : "s") + ")");
		}
	}

	/**
	 * Sweeps everything in reach and takes what it can for free.
	 *
	 * <p>Stands are settled on the spot: a brewing stand is one by its <b>block</b>, so
	 * no opening needed. Containers are the opposite — the client is never told what's
	 * in a chest it hasn't opened (the block entity is there for the lid animation, and
	 * its inventory is empty client-side), so the block only makes it a <i>candidate</i>
	 * and it goes on {@link #toPeek} to be looked inside once.
	 *
	 * <p>Re-runs as you walk, since the point is to notice the station you just walked
	 * up to. Throttled by distance moved and by a second, so it isn't a per-tick sweep
	 * of a few hundred blocks. Anything already known or already peeked is skipped, so
	 * a rescan standing still costs nothing.
	 */
	private void scan() {
		int range = (int) Math.ceil(mc().player.blockInteractionRange());
		BlockPos origin = mc().player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-range, -range, -range),
				origin.offset(range, range, range))) {
			if (!inReach(pos)) {
				continue;
			}
			BlockState state = mc().level.getBlockState(pos);
			if (state.is(Blocks.BREWING_STAND)) {
				if (!stands.contains(pos)) {
					BlockPos found = pos.immutable(); // betweenClosed reuses one cursor
					stands.add(found);
					ChatUtil.info("§dAutoBrew§7: found stand " + stands.size() + " at " + pretty(found));
				}
			} else if (mc().level.getBlockEntity(pos) instanceof net.minecraft.world.Container) {
				if (isStorage(pos)) {
					if (!storage.contains(pos)) {
						BlockPos found = pos.immutable();
						storage.add(found);
						ChatUtil.info("§dAutoBrew§7: potion storage at " + pretty(found));
					}
				} else if (!chests.contains(pos) && !peeked.contains(pos) && !toPeek.contains(pos)) {
					toPeek.add(pos.immutable());
				}
			}
		}
	}

	/** Opens one pending container so {@link #learn} can see inside. True while busy. */
	private boolean peek() {
		while (!toPeek.isEmpty() && !inReach(toPeek.get(0))) {
			toPeek.remove(0); // wandered out of range before we got to it
		}
		if (toPeek.isEmpty()) {
			return false;
		}
		BlockPos pos = toPeek.get(0);
		if (!ensureOpen(pos)) {
			return true;
		}
		// the menu is up, so learn() has already read it this tick — either way we're done
		peeked.add(pos);
		toPeek.remove(0);
		return true;
	}

	/** Snapshots what's in the open container, so pickChest can guess where to go. */
	private void remember(AbstractContainerMenu menu, BlockPos pos) {
		List<ItemStack> seen = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) {
				seen.add(slot.getItem().copy());
			}
		}
		chestSeen.put(pos, seen);
	}

	private boolean isBottle(ItemStack stack) {
		return stack.is(Items.GLASS_BOTTLE) || stateOf(stack) != null;
	}

	/** Anything that appears as a reagent in any chain this server can brew. */
	private boolean isReagent(ItemStack stack, Map<State, List<Step>> paths) {
		for (List<Step> chain : paths.values()) {
			for (Step step : chain) {
				if (stack.is(step.reagent())) {
					return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------- the stand

	private void runStand(BrewingStandMenu menu, BlockPos stand, int order, List<Step> chain, State target) {
		// Mid-brew: every click now would just fight it, and the stage can't have moved.
		// The stand tells us exactly how long it'll be, so park it for that long and go
		// do another one instead of sitting here for 20 seconds.
		if (menu.getBrewingTicks() > 0) {
			status = "Brewing at " + pretty(stand) + " (" + (menu.getBrewingTicks() / 20) + "s)";
			standBusy.put(stand, menu.getBrewingTicks() + 5);
			standIndex = (standIndex + 1) % stands.size();
			return;
		}
		if (fuel.get() && menu.getFuel() == 0 && !supply(menu, stack -> stack.is(Items.BLAZE_POWDER),
				"blaze powder", FUEL_SLOT)) {
			return; // one powder is 20 brews; there's never a reason to tip the stack in
		}
		List<State> bottles = new ArrayList<>();
		for (int slot = BOTTLE_START; slot < BOTTLE_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty()) {
				bottles.add(stateOf(stack));
			}
		}
		int stage = stageOf(bottles, chain, target);
		if (stage < 0) {
			standLoad.put(stand, 0);
			moveOut(menu, stand, order, false); // off-chain or out of step — start the batch clean
			return;
		}
		// ground truth for this stand's contribution to the order, re-read every visit
		standLoad.put(stand, bottles.size());
		if (stage == chain.size() && !bottles.isEmpty()) {
			moveOut(menu, stand, order, true);
			return;
		}
		if (loadBottles(menu, stand, order, bottles, chain, target)) {
			return;
		}
		if (bottles.isEmpty()) {
			// Nothing to brew here — the order is already covered by the other stands,
			// so loadBottles rightly declined. Falling through to feedReagent would put
			// a reagent in an empty stand, which then never brews and never changes, so
			// the next visit sees "reagent already in" and waits on it forever. Park it
			// briefly and go find a stand that has work.
			standBusy.put(stand, 40);
			standIndex = (standIndex + 1) % stands.size();
			return;
		}
		feedReagent(menu, chain.get(stage).reagent());
	}

	/**
	 * Tops the stand up to what's still needed. Bottles already part-way down the
	 * chain count, so a stack of awkward potions saves a brew — but only one state at
	 * a time, since a stand holding mixed stages can't progress.
	 */
	private boolean loadBottles(BrewingStandMenu menu, BlockPos stand, int order, List<State> bottles,
			List<Step> chain, State target) {
		// what this stand may take: the order's shortfall, minus whatever the *other*
		// stands are already brewing toward it (this one's own bottles are the point)
		int want = Math.min(BOTTLE_END - BOTTLE_START, remaining(order, stand));
		if (bottles.size() >= want) {
			return false;
		}
		State load = bottles.isEmpty() ? bestAvailable(chain, target) : bottles.get(0);
		if (load != null) {
			int source = findSlot(menu, true, stack -> load.equals(stateOf(stack)));
			if (source >= 0) {
				// QUICK_MOVE is safe for bottles: vanilla routes a potion to the bottle
				// slots itself, and no potion is ever mistaken for fuel
				click(menu, source, 0, ContainerInput.QUICK_MOVE);
				cooldown = delay.getInt();
				return true;
			}
		}
		if (!bottles.isEmpty()) {
			return false; // short of a full batch, but we can brew what we've got
		}
		// nothing to load: go get some, and fall back to filling glass at water
		fetch(stack -> inChain(stateOf(stack), chain, target), "bottles", want, true);
		return true;
	}

	/** The furthest-along in-chain bottle we actually hold — fewest brews from here. */
	private State bestAvailable(List<Step> chain, State target) {
		for (int i = chain.size() - 1; i >= 0; i--) {
			State state = chain.get(i).result();
			if (!state.equals(target) && countInv(stack -> state.equals(stateOf(stack))) > 0) {
				return state;
			}
		}
		return countInv(stack -> BrewingSolver.WATER_BOTTLE.equals(stateOf(stack))) > 0
				? BrewingSolver.WATER_BOTTLE
				: null;
	}

	/**
	 * Puts the next reagent in.
	 *
	 * <p><b>Not</b> QUICK_MOVE, which would look right and silently break Strength:
	 * vanilla's quickMoveStack offers the fuel slot first, and blaze powder is both a
	 * fuel and a reagent — so every blaze powder we shift-clicked would land in the
	 * fuel slot and the stand would wait forever for an ingredient. An explicit
	 * pickup-and-place names the slot instead of hoping.
	 */
	private void feedReagent(BrewingStandMenu menu, Item reagent) {
		status = "Adding " + name(reagent) + " at " + pretty(menuPos);
		ItemStack current = menu.getSlot(INGREDIENT_SLOT).getItem();
		if (current.is(reagent)) {
			return; // already in, and the stand starts on its own — just wait
		}
		if (!current.isEmpty()) {
			click(menu, INGREDIENT_SLOT, 0, ContainerInput.QUICK_MOVE); // wrong reagent, take it back
			cooldown = delay.getInt();
			return;
		}
		supply(menu, stack -> stack.is(reagent), name(reagent), INGREDIENT_SLOT);
	}

	/**
	 * Gets {@code match} into the stand, or goes to fetch it. Returns true when the
	 * stand already had it in the player's slots and nothing else is needed.
	 *
	 * <p>Always places <b>exactly one</b>, never the stack: the stand eats one reagent
	 * per brew and one blaze powder fuels twenty, so tipping a stack of 64 in just
	 * parks your things in a block and forces us to fish them back out on the next
	 * reagent change.
	 */
	private boolean supply(BrewingStandMenu menu, Predicate<ItemStack> match, String labelText, int targetSlot) {
		int source = findSlot(menu, true, match);
		if (source < 0) {
			fetch(match, labelText, 1, false);
			return false;
		}
		placeOne(menu, source, targetSlot);
		cooldown = delay.getInt();
		return false;
	}

	/** Shift-clicks one bottle out of the stand. Counted only when it's the finished one. */
	private void moveOut(BrewingStandMenu menu, BlockPos stand, int order, boolean finished) {
		for (int slot = BOTTLE_START; slot < BOTTLE_END; slot++) {
			if (!menu.getSlot(slot).getItem().isEmpty()) {
				click(menu, slot, 0, ContainerInput.QUICK_MOVE);
				standLoad.put(stand, Math.max(0, standLoad.getOrDefault(stand, 0) - 1));
				if (finished) {
					producedPer.merge(order, 1, Integer::sum); // counted out, never guessed in
					if (remaining(order, null) <= 0) {
						announce(order);
					}
				}
				cooldown = delay.getInt();
				return;
			}
		}
	}

	// -------------------------------------------------------------- fetching

	private void fetch(Predicate<ItemStack> match, String labelText, int amount, boolean bottles) {
		fetchMatch = match;
		fetchLabel = labelText;
		fetchAmount = amount;
		fetchIsBottles = bottles;
		fetchTried.clear(); // a fresh need gets to look everywhere again
	}

	private void clearFetch() {
		fetchMatch = null;
		fetchLabel = null;
		fetchIsBottles = false;
		fetchTried.clear();
	}

	/**
	 * Where to look next for {@code fetchMatch}: a chest we remember holding it and
	 * haven't tried yet, else any untried chest (what we remember may be stale), else
	 * null when we've been through them all. Out-of-reach chests are skipped rather
	 * than stalled on — with several chests, one being far away isn't an error.
	 */
	private BlockPos pickChest() {
		for (BlockPos pos : chests) {
			if (fetchTried.contains(pos) || !inReach(pos)) {
				continue;
			}
			List<ItemStack> seen = chestSeen.get(pos);
			if (seen != null && seen.stream().anyMatch(fetchMatch)) {
				return pos;
			}
		}
		for (BlockPos pos : chests) {
			if (!fetchTried.contains(pos) && inReach(pos)) {
				return pos;
			}
		}
		return null;
	}

	/** A container with a hopper straight under it is where potions go. */
	private boolean isStorage(BlockPos pos) {
		return mc().level.getBlockState(pos.below()).is(Blocks.HOPPER);
	}

	/**
	 * Should this stack be put away? Only finished product: something an order actually
	 * asked for, and not something an unfinished order still wants as a step.
	 *
	 * <p>That second half matters because the two overlap. Awkward Potion is a target
	 * in its own right and a rung on the ladder to most others — store it while Healing
	 * is still cooking and we'd walk to the chest, put it away, then walk back and brew
	 * a fresh one. Anything you brewed that nothing is waiting on goes; the rest stays
	 * in the bag where {@code bestAvailable} can reuse it.
	 */
	private boolean storable(ItemStack stack, Map<State, List<Step>> paths) {
		State state = stateOf(stack);
		if (state == null) {
			return false;
		}
		boolean wanted = false;
		for (int order = 0; order < queue.get().size(); order++) {
			State target = targetOf(order);
			if (target == null || !paths.containsKey(target)) {
				continue;
			}
			if (state.equals(target)) {
				wanted = true;
				continue; // the finished article — never an intermediate for its own order
			}
			if (producedPer.getOrDefault(order, 0) < goalOf(order) && inChain(state, paths.get(target), target)) {
				return false; // still a step on the way to something we owe
			}
		}
		return wanted;
	}

	/** Walks finished potions to a hopper-fed chest. True while it has work. */
	private boolean deposit(Map<State, List<Step>> paths) {
		if (!emptyPotions.get() || countInv(stack -> storable(stack, paths)) == 0) {
			return false;
		}
		BlockPos to = null;
		for (BlockPos pos : storage) {
			if (inReach(pos)) {
				to = pos;
				break;
			}
		}
		if (to == null) {
			return false; // no storage in reach; the potions just ride along in the bag
		}
		status = "Storing potions at " + pretty(to);
		if (!ensureOpen(to)) {
			return true;
		}
		AbstractContainerMenu menu = mc().player.containerMenu;
		int slot = findSlot(menu, true, stack -> storable(stack, paths));
		if (slot < 0) {
			return false; // nothing left to put away
		}
		click(menu, slot, 0, ContainerInput.QUICK_MOVE);
		cooldown = delay.getInt();
		return true;
	}

	private boolean inReach(BlockPos pos) {
		return mc().player.isWithinBlockInteractionRange(pos, 1.0);
	}

	private void runFetch() {
		status = "Fetching " + fetchLabel;
		if (countInv(fetchMatch) >= fetchAmount) {
			clearFetch();
			return;
		}
		BlockPos from = pickChest();
		if (from == null) {
			// every chest is checked; empties in hand still beat giving up
			if (fetchIsBottles && fillBottles.get() && countInv(stack -> stack.is(Items.GLASS_BOTTLE)) > 0) {
				filling = true;
				clearFetch();
				return;
			}
			stall(chests.isEmpty()
					? "no chests yet — open one while I'm on, or turn Discover on"
					: "out of " + fetchLabel);
			return;
		}
		status = "Fetching " + fetchLabel + " from " + pretty(from);
		if (!ensureOpen(from)) {
			return;
		}
		AbstractContainerMenu menu = mc().player.containerMenu;
		remember(menu, from); // we're looking anyway; keep the hint fresh
		int source = findSlot(menu, false, fetchMatch);
		if (source >= 0) {
			// only the shortfall: a chest holding 64 of something owes us what we asked
			// for, not its whole stack
			if (takeExactly(menu, source, fetchAmount - countInv(fetchMatch))) {
				cooldown = delay.getInt();
				return;
			}
			stall("no room in your inventory for " + fetchLabel);
			return;
		}
		if (fetchIsBottles && fillBottles.get()) {
			Predicate<ItemStack> isGlass = stack -> stack.is(Items.GLASS_BOTTLE);
			// Ask for the shortfall, and *only* look for empties while there is one.
			// Testing the chest first instead would wedge here: once we hold enough,
			// the chest still has glass, so we'd keep re-entering to take zero of it.
			int short_ = fetchAmount - countInv(fetchMatch) - countInv(isGlass);
			int glass = short_ > 0 ? findSlot(menu, false, isGlass) : -1;
			if (glass >= 0 && takeExactly(menu, glass, short_)) {
				cooldown = delay.getInt();
				return; // pull them first, then fill
			}
			if (countInv(isGlass) > 0) {
				filling = true;
				clearFetch();
				return;
			}
		}
		if (countInv(fetchMatch) > 0) {
			clearFetch(); // not the full batch, but enough to get on with
			return;
		}
		fetchTried.add(from); // nothing here — try the next chest rather than give up
	}

	// ---------------------------------------------------------- water filling

	/**
	 * Fills empty glass bottles at a water source or cauldron in reach.
	 *
	 * <p>The rotation is a server-side spoof rather than a real look, because it has
	 * to be: {@code BottleItem} raycasts for fluid <b>on the server</b>, using the
	 * rotation we sent it, so nodding the client's camera at the water would fill
	 * nothing. Same trick AutoXPRepair uses to throw bottles at its own feet.
	 */
	private void fillBottles(List<Step> chain, State target) {
		int want = Math.min(BOTTLE_END - BOTTLE_START, remaining(activeOrder, null));
		if (countInv(stack -> inChain(stateOf(stack), chain, target)) >= want
				|| countInv(stack -> stack.is(Items.GLASS_BOTTLE)) == 0) {
			filling = false;
			restoreHand();
			return;
		}
		// the hotbar swap below talks to the inventory menu, so nothing else may be open
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			closeMenu();
			cooldown = delay.getInt();
			return;
		}
		BlockPos water = findWater();
		if (water == null) {
			filling = false;
			restoreHand();
			stall("no water in reach to fill bottles");
			return;
		}
		if (!holdGlass()) {
			return;
		}
		if (!RotationManager.face(Vec3.atCenterOf(water), turnSpeed.getFloat())) {
			return; // still turning; no cooldown or the turn stalls
		}
		InteractUtil.useItem();
		cooldown = delay.getInt();
	}

	/** Nearest water source or cauldron the server would let us reach. */
	private BlockPos findWater() {
		int range = (int) Math.ceil(mc().player.blockInteractionRange());
		BlockPos origin = mc().player.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-range, -range, -range),
				origin.offset(range, range, range))) {
			if (!isWater(pos) || !mc().player.isWithinBlockInteractionRange(pos, 0.0)) {
				continue;
			}
			double distance = mc().player.distanceToSqr(Vec3.atCenterOf(pos));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos.immutable(); // betweenClosed hands back one reused cursor
			}
		}
		return best;
	}

	private boolean isWater(BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		if (state.is(Blocks.WATER_CAULDRON)) {
			return true; // any level fills a bottle
		}
		FluidState fluid = state.getFluidState();
		return fluid.is(Fluids.WATER) && fluid.isSource();
	}

	/** Gets glass bottles into the main hand, parking a deep stack in the hotbar if needed. */
	private boolean holdGlass() {
		Inventory inv = mc().player.getInventory();
		if (inv.getItem(inv.getSelectedSlot()).is(Items.GLASS_BOTTLE)) {
			return true;
		}
		if (prevSelected < 0) {
			prevSelected = inv.getSelectedSlot();
		}
		int hotbar = InteractUtil.findHotbarItem(Items.GLASS_BOTTLE);
		if (hotbar >= 0) {
			inv.setSelectedSlot(hotbar);
			return true;
		}
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).is(Items.GLASS_BOTTLE)) {
				InteractUtil.swapWithHotbar(i, 0); // 9..35 index and menu slot coincide
				parkedFrom = i;
				inv.setSelectedSlot(0);
				cooldown = delay.getInt();
				return false; // settle
			}
		}
		return false;
	}

	/** Undoes the hotbar park. Safe to call twice; a no-op once restored. */
	private void restoreHand() {
		if (mc().player == null || mc().player.containerMenu != mc().player.inventoryMenu) {
			parkedFrom = prevSelected = -1; // can't click the inventory menu now; give up cleanly
			return;
		}
		if (parkedFrom >= 0) {
			InteractUtil.swapWithHotbar(parkedFrom, 0);
			parkedFrom = -1;
		}
		if (prevSelected >= 0) {
			mc().player.getInventory().setSelectedSlot(prevSelected);
			prevSelected = -1;
		}
	}

	// ------------------------------------------------------------- containers

	/**
	 * True once {@code pos} is the open container. Otherwise it spends this tick
	 * getting there — closing whatever else is up, or right-clicking the block — and
	 * returns false. One step per tick, so the server sees a sane sequence.
	 */
	private boolean ensureOpen(BlockPos pos) {
		if (pos.equals(menuPos) && mc().player.containerMenu != mc().player.inventoryMenu) {
			return synced(mc().player.containerMenu);
		}
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			closeMenu();
			cooldown = delay.getInt();
			return false;
		}
		if (!mc().player.isWithinBlockInteractionRange(pos, 1.0)) {
			stall("out of reach of " + pretty(pos));
			return false;
		}
		// turn to face it before reaching for it. No cooldown while turning: face()
		// has to be called every tick or the turn stops halfway
		if (!RotationManager.face(Vec3.atCenterOf(pos), turnSpeed.getFloat())) {
			return false;
		}
		expecting = pos;
		InteractUtil.useOnBlock(pos, Direction.UP);
		cooldown = delay.getInt();
		return false;
	}

	/**
	 * Has the server actually told us what's in this menu yet?
	 *
	 * <p><b>A menu exists before its contents do.</b> {@code ClientboundOpenScreen}
	 * builds it and {@code ClientboundContainerSetContent} fills it, and they are two
	 * packets — so for a tick or more, a freshly opened brewing stand reads as
	 * <i>completely empty</i>: no bottles, no ingredient, {@code getFuel() == 0},
	 * {@code getBrewingTicks() == 0}. Every one of those is a lie that looks exactly
	 * like a real, idle, empty stand.
	 *
	 * <p>That's what wrecked multi-step chains. We'd re-open a stand that was 400 ticks
	 * into brewing Awkward, see "empty and idle", not park it, tip more fuel in and load
	 * more bottles on top — and never once see the finished potion to pull it out.
	 *
	 * <p>The tell is {@code stateId}: a menu the client just built has 0, and only the
	 * server's content packet ever stamps a real one on it. (It wraps at 32768 changes
	 * within one open container, which is not a thing that happens.)
	 */
	private boolean synced(AbstractContainerMenu menu) {
		return menu.getStateId() != 0;
	}

	/**
	 * A slot on one side of the menu. Player slots are the ones backed by the player's
	 * own Inventory — asking the slot beats hardcoding an index, and it works for a
	 * brewing stand and a double chest alike.
	 */
	private int findSlot(AbstractContainerMenu menu, boolean playerSide, Predicate<ItemStack> match) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container instanceof Inventory != playerSide) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty() && match.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	private int countInv(Predicate<ItemStack> match) {
		Inventory inv = mc().player.getInventory();
		int total = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && match.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Moves exactly {@code n} items out of {@code sourceSlot} into the player.
	 *
	 * <p>QUICK_MOVE can't do this: it always takes the <b>whole stack</b>, so asking a
	 * chest for 7 glass bottles handed over all 64. There's no "move n" click, so this
	 * builds one out of the clicks that exist — take the stack onto the cursor,
	 * right-click {@code n} times to drop them one at a time, then put the remainder
	 * back where it came from.
	 *
	 * <p>All of it in a single tick, deliberately: a cursor that survives a tick is a
	 * stack the player can lose by closing the GUI at the wrong moment. Same rule as
	 * {@link #feedReagent}.
	 *
	 * @return false if there was nowhere to put them
	 */
	private boolean takeExactly(AbstractContainerMenu menu, int sourceSlot, int n) {
		if (n <= 0) {
			return false;
		}
		ItemStack source = menu.getSlot(sourceSlot).getItem();
		if (source.getCount() <= n) {
			click(menu, sourceSlot, 0, ContainerInput.QUICK_MOVE); // the whole stack is what we wanted anyway
			return true;
		}
		int target = freeSlot(menu, source);
		if (target < 0) {
			return false;
		}
		click(menu, sourceSlot, 0, ContainerInput.PICKUP); // cursor takes the stack
		for (int i = 0; i < n; i++) {
			click(menu, target, 1, ContainerInput.PICKUP); // right-click drops exactly one
		}
		click(menu, sourceSlot, 0, ContainerInput.PICKUP); // remainder goes back
		return true;
	}

	/** Places exactly one of {@code sourceSlot}'s stack into {@code targetSlot}. */
	private void placeOne(AbstractContainerMenu menu, int sourceSlot, int targetSlot) {
		click(menu, sourceSlot, 0, ContainerInput.PICKUP);
		click(menu, targetSlot, 1, ContainerInput.PICKUP);
		click(menu, sourceSlot, 0, ContainerInput.PICKUP);
	}

	/** A player slot we can drop {@code like} into: empty for preference, else a part-stack with room. */
	private int freeSlot(AbstractContainerMenu menu, ItemStack like) {
		int partial = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!(slot.container instanceof Inventory)) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				return i;
			}
			if (partial < 0 && ItemStack.isSameItemSameComponents(stack, like)
					&& stack.getCount() < stack.getMaxStackSize()) {
				partial = i;
			}
		}
		return partial;
	}

	// ------------------------------------------- what the Brewing widget reads

	public String status() {
		return status;
	}

	/** "Splash Strength" of the order in hand, or null when there isn't one. */
	/** The bottle order {@code i} wants. */
	public State targetOfOrder(int order) {
		return targetOf(order);
	}

	public int goalOf(int order) {
		return order < 0 || order >= queue.get().size() ? 0 : BrewQueueSetting.countIn(queue.get().get(order));
	}

	public int producedOf(int order) {
		return producedPer.getOrDefault(order, 0);
	}

	/** Which order a stand is working, or -1. */
	public int orderOfStand(BlockPos stand) {
		return standOrder.getOrDefault(stand, -1);
	}

	public List<BlockPos> standList() {
		return stands;
	}

	public List<BlockPos> chestList() {
		return chests;
	}

	public List<BlockPos> storageList() {
		return storage;
	}

	/** Seconds left on a stand's brew, 0 when it's free. */
	public int standSeconds(BlockPos stand) {
		return standBusy.getOrDefault(stand, 0) / 20;
	}

	public int standLoad(BlockPos stand) {
		return standLoad.getOrDefault(stand, 0);
	}

	/** What we last saw inside a chest. Empty until we've looked in it. */
	public List<ItemStack> chestContents(BlockPos chest) {
		return chestSeen.getOrDefault(chest, List.of());
	}

	private int brewSecondsLeft() {
		int most = 0;
		for (int ticks : standBusy.values()) {
			most = Math.max(most, ticks);
		}
		return most / 20;
	}

	private void click(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
		mc().gameMode.handleContainerInput(menu.containerId, slot, button, input, mc().player);
	}

	// ------------------------------------------------------------------ chain

	/**
	 * Which step of the chain the bottles sit on, or -1 when they can't be worked
	 * with: off-chain, or disagreeing with each other. Empty is stage 0 — nothing
	 * loaded yet, so nothing is out of step.
	 */
	private int stageOf(List<State> bottles, List<Step> chain, State target) {
		int stage = -1;
		for (State bottle : bottles) {
			int found = indexOf(bottle, chain, target);
			if (found < 0 || (stage >= 0 && found != stage)) {
				return -1;
			}
			stage = found;
		}
		return stage < 0 ? 0 : stage;
	}

	/** Steps already done: 0 = water bottle, chain.size() = finished. -1 = not on the chain. */
	private int indexOf(State bottle, List<Step> chain, State target) {
		if (bottle == null) {
			return -1;
		}
		if (bottle.equals(target)) {
			return chain.size();
		}
		if (bottle.equals(BrewingSolver.WATER_BOTTLE)) {
			return 0;
		}
		for (int i = 0; i < chain.size(); i++) {
			if (chain.get(i).result().equals(bottle)) {
				return i + 1;
			}
		}
		return -1;
	}

	private boolean inChain(State state, List<Step> chain, State target) {
		return indexOf(state, chain, target) >= 0;
	}

	// --------------------------------------------------------------- plumbing

	/** Current order done (or undoable): report it and move down the queue. */
	/** Says an order is finished, once, the moment its last bottle is pulled. */
	private void announce(int order) {
		State done = targetOf(order);
		if (done != null && !announced.contains(order)) {
			announced.add(order);
			ChatUtil.info("§dAutoBrew§7: " + producedPer.getOrDefault(order, 0) + "x "
					+ BrewingSolver.label(done) + " done");
		}
	}

	/** The whole queue is through. */
	private void finish() {
		UnluckyClient.INSTANCE.notifications.add("AutoBrew", queue.total() + " bottles brewed",
				new ItemStack(Items.BREWING_STAND));
		if (disableWhenDone.get()) {
			setEnabled(false);
		} else {
			producedPer.clear(); // left on: run the whole queue again
			standOrder.clear();
			announced.clear();
		}
	}

	private State stateOf(ItemStack stack) {
		return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion()
				.map(holder -> new State(stack.getItem(), holder))
				.orElse(null);
	}

	/**
	 * Bottles still owed on the current order, ignoring whatever is already sitting in
	 * {@code except}. Pass null to count every stand.
	 *
	 * <p>Subtracting the in-flight bottles is what stops several stands each brewing a
	 * full batch of 3 for an order of 7 and overshooting by 2. Every stand's figure is
	 * re-read from the stand itself on each visit, so this is a live sum, not a tally
	 * that can drift.
	 */
	private int remaining(int order, BlockPos except) {
		if (order < 0 || order >= queue.get().size()) {
			return 0;
		}
		int elsewhere = 0;
		for (Map.Entry<BlockPos, Integer> entry : standLoad.entrySet()) {
			// only stands working THIS order count against it — a stand brewing
			// Invisibility says nothing about how much Healing is still owed
			if (!entry.getKey().equals(except) && Integer.valueOf(order).equals(standOrder.get(entry.getKey()))) {
				elsewhere += entry.getValue();
			}
		}
		return BrewQueueSetting.countIn(queue.get().get(order)) - producedPer.getOrDefault(order, 0) - elsewhere;
	}

	/** The bottle an order wants, or null if the entry is junk. */
	private State targetOf(int order) {
		if (order < 0 || order >= queue.get().size()) {
			return null;
		}
		return BrewingSolver.fromKey(BrewQueueSetting.keyOf(queue.get().get(order)));
	}

	/**
	 * Which order this stand should work: the one it already holds bottles for, else
	 * the first order with uncovered work left.
	 *
	 * <p>Stands are allocated to <b>work</b>, not to orders, and that one rule covers
	 * both shapes: nine bottles of a single order is three batches so it claims three
	 * stands, and four small orders claim four. A stand keeps its order while it still
	 * has bottles in it, or the batch it's mid-way through would be orphaned the moment
	 * another order looked more urgent.
	 *
	 * <p>Null when every order is already covered — the caller parks the stand.
	 */
	private Integer orderFor(BlockPos stand, Map<State, List<Step>> paths) {
		Integer held = standOrder.get(stand);
		if (held != null && standLoad.getOrDefault(stand, 0) > 0 && remaining(held, null) + standLoad.get(stand) > 0) {
			return held;
		}
		for (int order = 0; order < queue.get().size(); order++) {
			State target = targetOf(order);
			if (target == null || !paths.containsKey(target)) {
				continue; // can't be brewed here; allDone() reports it once
			}
			if (remaining(order, null) > 0) {
				standOrder.put(stand, order);
				return order;
			}
		}
		return null;
	}

	/**
	 * Every order brewed, or unbrewable here. Reports the unbrewable ones once.
	 *
	 * <p>Measured on {@code producedPer} — bottles actually <b>pulled back out</b> —
	 * and emphatically not on {@link #remaining}, which subtracts bottles already
	 * loaded into stands. Ask remaining and every order reads as "covered" the moment
	 * the last bottle goes <i>in</i>, so we'd call it finished, switch off, and walk
	 * away from three stands mid-brew. In is not done; out is done.
	 */
	private boolean allDone(Map<State, List<Step>> paths) {
		for (int order = 0; order < queue.get().size(); order++) {
			State target = targetOf(order);
			if (target == null || !paths.containsKey(target)) {
				warn("can't brew " + (target == null ? "one of the queued potions" : BrewingSolver.label(target))
						+ " here — skipping it");
				continue;
			}
			if (producedPer.getOrDefault(order, 0) < goalOf(order)) {
				return false;
			}
		}
		return true;
	}

	/** Next stand that isn't mid-brew, round-robin. Null when they're all cooking. */
	private BlockPos pickStand() {
		for (int i = 0; i < stands.size(); i++) {
			int index = (standIndex + i) % stands.size();
			BlockPos stand = stands.get(index);
			if (standBusy.getOrDefault(stand, 0) <= 0) {
				standIndex = index;
				return stand;
			}
		}
		return null;
	}

	private void forget(BlockPos stand) {
		stands.remove(stand);
		standBusy.remove(stand);
		standLoad.remove(stand);
		standIndex = 0;
	}

	/**
	 * The item's display name. Via a real one-item stack, <b>not</b>
	 * {@code item.getName(ItemStack.EMPTY)} — that hands back an empty string, which
	 * turned every "out of X" into a blank "out of ".
	 */
	private static String name(Item item) {
		return new ItemStack(item).getHoverName().getString();
	}

	private static String pretty(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	/** Nothing we can do until the world changes: say so once, then check back slowly. */
	private void stall(String message) {
		status = "§cStuck: " + message;
		warn(message);
		cooldown = STALL;
	}

	private void warn(String message) {
		if (!message.equals(warned)) {
			warned = message;
			ChatUtil.info("§cAutoBrew: " + message);
		}
	}
}
