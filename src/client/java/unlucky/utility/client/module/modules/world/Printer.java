package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.FlightPath;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.LitematicaBridge;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.PlacementSolver;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;

/**
 * Builds the schematic Litematica has loaded, placing the blocks it says are missing.
 *
 * <p>Litematica keeps a "ghost world" of the schematic at its placement; every tick we
 * ask it what belongs in the positions around us, compare against the real world, and
 * click the mismatches into existence. All of the schematic handling — loading, moving
 * the placement, the layer slider — stays in Litematica's own UI; see
 * {@link LitematicaBridge} for the (small, read-only) surface we use.
 *
 * <p>Which click to send is {@link PlacementSolver}'s job, and it is what makes stairs,
 * logs and hoppers come out facing the right way, and three-layer snow actually three
 * layers: positions are compared by <em>state</em>, and a click is only sent when vanilla's
 * own placement logic predicts it closes the gap.
 *
 * <p>Recreated from kkllffaa/meteor-litematica-printer (the scan/sort/place shape) and
 * Nippaku-Zanmu/Seija-Printer (randomised timing, the recently-placed blacklist, forcing
 * sneak so clicks never open a container, and the simulate-vanilla insight the solver is
 * built on). Blocks already present but facing the wrong way still need breaking first —
 * that is the one case left open.
 */
public class Printer extends Module {
	private static final int MENU_HOTBAR_START = 36;
	/** Hotbar slot we borrow when a needed block only exists in the main inventory. */
	private static final int WORK_SLOT = 0;

	public final NumberSetting range = add(new NumberSetting("Range",
			"How far to reach for blocks. Above ~4.5 the server may reject the click.", 4.5, 1, 6, 0.1));
	public final NumberSetting perTick = add(new NumberSetting("Blocks/tick",
			"Blocks placed per batch. High values are fast and very obvious.", 1, 1, 16, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks to wait between batches.", 1, 0, 20, 1));
	public final NumberSetting jitter = add(new NumberSetting("Jitter",
			"Extra random ticks added to each delay, so the rhythm isn't machine-perfect.", 0, 0, 10, 1));
	public final ModeSetting sort = add(new ModeSetting("Sort",
			"Which missing block to place first.", "Nearest",
			"Nearest", "Furthest", "Bottom up", "Top down"));
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Place with nothing to click against. Most servers reject this.", false));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Place blocks you have no line of sight to. Most servers reject this.", false));
	public final ModeSetting rotate = add(new ModeSetting("Rotate",
			"Face the block being placed. Silent snaps server-side; Smooth turns over several ticks.",
			"Silent", "Off", "Silent", "Smooth"));
	public final NumberSetting turnSpeed = add(new NumberSetting("Turn speed",
			"Degrees per tick in Smooth mode.", 20, 1, 180, 1));
	public final BooleanSetting precise = add(new BooleanSetting("Precise",
			"Only place when the click is predicted to produce exactly the state the schematic "
					+ "wants. Off places the closest match instead — faster, but leaves "
					+ "wrongly-turned blocks behind.", true));
	public final BooleanSetting sneak = add(new BooleanSetting("Sneak",
			"Hold sneak while clicking so chests and doors open instead of nothing being placed.", true));
	public final BooleanSetting swing = add(new BooleanSetting("Swing",
			"Show the arm swing.", true));
	public final BooleanSetting returnSlot = add(new BooleanSetting("Return slot",
			"Put the hotbar selection back when idle or switched off.", true));
	public final BooleanSetting dirtAsGrass = add(new BooleanSetting("Dirt as grass",
			"Use dirt where the schematic asks for grass blocks.", true));
	public final BooleanSetting creativeRestock = add(new BooleanSetting("Creative restock",
			"In creative, pull any needed block straight into the hotbar.", true));
	public final ModeSetting movement = add(new ModeSetting("Movement",
			"Travel to reach schematic beyond arm's length. Fly grants the flight ability, "
					+ "so it needs creative or a server that permits flying.", "Off",
			"Off", "Fly"));
	public final NumberSetting flySpeed = add(new NumberSetting("Fly speed",
			"Blocks per tick while travelling. Slows to a crawl where there is work in "
					+ "reach, so it keeps placing on the move; the higher this goes the "
					+ "further past a waypoint each tick carries you.", 0.6, 0.05, 1.5, 0.05));
	public final BooleanSetting autoLayers = add(new BooleanSetting("Auto layers",
			"Drive Litematica's layer view: build one band bottom-up, then move up a band. "
					+ "Needs Movement on, since it is finishing a band that advances it. "
					+ "Your own layer settings are put back when the module stops.", true));
	public final NumberSetting bandHeight = add(new NumberSetting("Band height",
			"Y levels built per band with Auto layers on. Staircased maparts scatter each "
					+ "single level thinly across the whole map, so 2 halves the travel; "
					+ "above 3 the reach from above can no longer cover the band.", 2, 1, 16, 1));
	public final ModeSetting schematic = add(new ModeSetting("Schematic",
			"Which loaded placement to build. All builds every enabled one as a single job; "
					+ "pick a name and everything — reach, route, counters and the clock — "
					+ "is scoped to it, so two schematics placed at once stay two jobs.",
			ALL, ALL));
	public final BooleanSetting stopWhenDone = add(new BooleanSetting("Stop when done",
			"Switch the module off once there is nothing left it can place.", true));
	public final BooleanSetting showRoute = add(new BooleanSetting("Show route",
			"Draw the lane the printer plans to fly, so what it intends is visible.", true));
	public final BlockListSetting only = add(new BlockListSetting("Only",
			"Place nothing but these — right-click to pick. Empty means everything.", Set.of()));
	public final BlockListSetting ignore = add(new BlockListSetting("Ignore",
			"Never place these — right-click to pick.", Set.of()));
	public final BooleanSetting render = add(new BooleanSetting("Render",
			"Flash a box on each block as it's placed.", true));
	public final NumberSetting fadeTime = add(new NumberSetting("Fade",
			"Ticks a placed box stays visible.", 6, 1, 40, 1));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Placed-block box color", 0x6095BEFF));

	/** A click we have sent and the state it should produce — see {@link #pending}. */
	private record Pending(BlockState expected, long at) {
	}

	/**
	 * Positions we have clicked and not yet seen change.
	 *
	 * <p>Rate-limiting alone is not enough for blocks that stack. A click takes a round trip
	 * to come back, and if we click again in the meantime the second one is not a retry — it
	 * is another snow layer. So each entry records the state its click should produce, and
	 * the position is left alone until exactly that shows up (or {@link #PENDING_MS} passes
	 * and we assume the server dropped it). That makes every click count once, which is what
	 * stops layers overshooting and then never settling.
	 */
	private final Map<Long, Pending> pending = new HashMap<>();
	/**
	 * Positions no click can currently improve, mapped to when we gave up. Mostly blocks
	 * that were placed facing the wrong way and can only be fixed by breaking them: without
	 * this they would be re-solved on every tick forever, for nothing.
	 */
	private final Map<Long, Long> unsolvable = new HashMap<>();
	/** Placed positions still fading out, mapped to ticks remaining. */
	private final Map<Long, Integer> fading = new HashMap<>();
	private final List<BlockPos> candidates = new ArrayList<>();
	private final BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();

	/**
	 * Minimum gap between two clicks on the same position, on top of the
	 * wait-until-it-changed rule.
	 */
	private static final long RECENT_MS = 300L;
	/**
	 * How long to keep waiting for a click to show up before assuming the server threw it
	 * away and letting the position be tried again.
	 */
	private static final long PENDING_MS = 1500L;
	/** How long a position stays written off before we try solving it again. */
	private static final long UNSOLVABLE_MS = 3000L;

	/** The Schematic picker's "everything at once" option. */
	private static final String ALL = "All";

	private int cooldown;
	private int previousSlot = -1;
	/** So "load a schematic" is said once per idle spell, not every tick. */
	private boolean announcedIdle;
	/**
	 * The region being printed, refreshed once a tick from the Schematic picker.
	 *
	 * <p>Cached because {@link #wantsBlock} asks for it tens of thousands of times a tick
	 * between the reach scan and the background tally, and each fresh answer walks every
	 * placement's sub-region boxes.
	 */
	private LitematicaBridge.Region printRegion;
	/** Whether the picker names one placement, so work outside it is not ours. */
	private boolean scoped;

	/** Where the automation currently is in its plan — see {@link #navigate}. */
	private enum Phase {
		/** Scanning the band into {@link #bandWork}, a slice per tick. */
		PLAN,
		/** Flying the lane route, placing along the way. */
		DRIVE,
		/** Parked at the route's end, letting in-flight clicks land before rescanning. */
		SETTLE
	}

	private Phase phase = Phase.PLAN;
	/** The placement the current plan was built for; a change restarts the plan. */
	private LitematicaBridge.Region region;
	private int bandMinY;
	private int bandMaxY;
	/** Every position that still wanted a block when the band was last scanned. */
	private final List<BlockPos> bandWork = new ArrayList<>();
	/** The lane route over {@link #bandWork}: waypoints flown in order, all at one height. */
	private final List<Vec3> lane = new ArrayList<>();
	private int laneIndex;
	/** Waypoints around an unexpected obstacle, flown before the lane resumes. */
	private List<BlockPos> detour = List.of();
	/** Whether the current waypoint has had its one detour attempt. */
	private boolean detoured;
	/** Ticks without getting closer to the current waypoint, and the best distance yet. */
	private int stalledTicks;
	private double closestSoFar = Double.MAX_VALUE;
	/** X column the band scan has reached; MIN_VALUE when no scan is in progress. */
	private int planCursor = Integer.MIN_VALUE;
	/** Lane passes flown over the current band. */
	private int passesThisBand;
	/** Whether anything has been placed since the band was last scanned. */
	private boolean placedThisPass;
	/** Ticks spent settling, so a click the server dropped cannot hold the phase forever. */
	private int settleTicks;
	/** The user's Litematica layer view, borrowed while Auto layers drives it. */
	private LitematicaBridge.LayerView savedLayers;
	private boolean grantedFlight;
	/** Set once there is nothing left to place, so the report is made once. */
	private boolean finished;
	/** The last angle aimed at, re-asserted each tick so the visible pose does not flicker. */
	private float aimYaw;
	private float aimPitch;
	private long aimAtMs;
	/** How long the printer keeps looking at its last target after the last aim. */
	private static final long AIM_HOLD_MS = 1000L;

	/** How close counts as having reached a waypoint. */
	private static final double WAYPOINT_REACHED = 0.8;
	/** Ticks without progress toward a waypoint before it counts as blocked. */
	private static final int STALL_TICKS = 12;
	/** Distance-squared improvement that counts as progress rather than drift. */
	private static final double PROGRESS_EPSILON = 0.01;
	/** Fraction of travel speed used while unplaced work is within reach. */
	private static final double WORK_ZONE_SPEED = 0.25;
	/** Cells the band scan visits per tick, so planning a huge band cannot stall a tick. */
	private static final int PLAN_CELLS_PER_TICK = 40_000;
	/** Ticks to wait on in-flight clicks before rescanning anyway. */
	private static final int SETTLE_TICKS_MAX = 30;
	/** Splits a lane where the gap between work along it exceeds this many blocks. */
	private static final int LANE_GAP_SPLIT = 16;
	/** The eye above the feet, for working out horizontal reach from lane height. */
	private static final double EYE_HEIGHT = 1.62;
	/** Upcoming waypoints drawn by Show route. */
	private static final int ROUTE_PREVIEW = 16;
	/** Ceiling on the closing verification scan, so a huge placement can't stall a tick. */
	private static final long VERIFY_CELL_CAP = 2_000_000L;

	public Printer() {
		super("Printer", "Build Litematica schematics automatically", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		cooldown = 0;
		pending.clear();
		unsolvable.clear();
		fading.clear();
		announcedIdle = false;
		// The counters deliberately survive a toggle: switching off to restock, move or
		// fix something by hand is part of doing the print, not the end of it. Only a
		// different job resets them, which is newPrintCheck's call.
		resetNavigation();

		// present() is only a loader lookup, so it is safe here; anything that reaches
		// into Litematica proper is not — see LitematicaBridge#hasSchematic. This runs
		// at startup too, when config load re-enables whatever was on last session.
		if (!LitematicaBridge.present()) {
			ChatUtil.info("Printer needs Litematica installed.");
			setEnabled(false);
		}
	}

	@Override
	protected void onDisable() {
		restoreSlot();
		releaseFlight();
		aimAtMs = 0L; // stop holding the pose, so the head goes back to the camera
		// the layer view is the user's, only borrowed — put it back even if we were
		// switched off mid-band
		LitematicaBridge.restoreLayerView(savedLayers);
		savedLayers = null;
		pending.clear();
		unsolvable.clear();
		fading.clear();
		candidates.clear();
		resetNavigation();
	}

	private void resetNavigation() {
		phase = Phase.PLAN;
		region = null;
		bandWork.clear();
		lane.clear();
		laneIndex = 0;
		detour = List.of();
		detoured = false;
		stalledTicks = 0;
		closestSoFar = Double.MAX_VALUE;
		planCursor = Integer.MIN_VALUE;
		passesThisBand = 0;
		placedThisPass = false;
		settleTicks = 0;
		finished = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().gameMode == null) {
			fading.clear();
			return;
		}
		decayFading();
		if (render.get()) {
			renderFading();
		}

		// typing in chat or shuffling an inventory shouldn't fire clicks
		if (mc().gui.screen() != null) {
			restoreSlot();
			return;
		}
		if (!LitematicaBridge.hasSchematic()) {
			// said here rather than in onEnable: that runs before the renderer exists
			if (!announcedIdle) {
				announcedIdle = true;
				ChatUtil.info("Printer is on — load a schematic in Litematica to start.");
			}
			restoreSlot();
			return;
		}
		announcedIdle = false;
		refreshSchematics();
		if (printRegion == null) {
			restoreSlot();
			return; // the picked schematic is not placed (any more) — nothing to build
		}
		newPrintCheck();
		if (!finished) {
			workTicks++; // the clock only runs while there is a job in progress
		}
		tallyTick();
		holdAim();

		long now = System.currentTimeMillis();
		expirePending(now);
		unsolvable.values().removeIf(gaveUpAt -> now - gaveUpAt > UNSOLVABLE_MS);

		if (cooldown > 0) {
			cooldown--;
		} else {
			collectCandidates(now);
			if (candidates.isEmpty()) {
				restoreSlot();
			} else {
				printBatch(now);
			}
		}

		// Outside the place cooldown, so the route keeps being followed between batches.
		// How fast to follow it is navigate's call — it crawls while anything is in
		// reach, so moving never outruns placing.
		if (!movement.is("Off") && !finished) {
			navigate(now);
		}
	}

	private void printBatch(long now) {
		sortCandidates();
		int budget = smoothTurning() ? 1 : perTick.getInt();
		int placed = 0;
		for (BlockPos pos : candidates) {
			if (placed >= budget) {
				break;
			}
			PlacementSolver.Solution sent = placeAt(pos);
			if (sent != null) {
				placed++;
				// what this click should produce, so the next one waits for *this* step
				// rather than for any change at all
				pending.put(pos.asLong(),
						new Pending(PlacementSolver.settle(sent.predicted(), pos), now));
				if (render.get()) {
					fading.put(pos.asLong(), fadeTime.getInt());
				}
			}
		}
		if (placed > 0) {
			recordPlaced(placed);
			placedThisPass = true;
			cooldown = delay.getInt() + (jitter.getInt() > 0
					? mc().player.getRandom().nextInt(jitter.getInt() + 1) : 0);
		}
	}

	/**
	 * Drops in-flight clicks that have either landed or been lost.
	 *
	 * <p>Clearing landed clicks here rather than inside the candidate filter is what lets
	 * {@link #needsWork} stay a pure predicate — the band scan and the reach scan both
	 * ask it, and a filter that quietly mutated state while merely being consulted would
	 * be a trap.
	 */
	private void expirePending(long now) {
		pending.entrySet().removeIf(entry -> {
			Pending sent = entry.getValue();
			if (now - sent.at() > PENDING_MS) {
				note("click lost at " + BlockPos.of(entry.getKey()).toShortString()
						+ " (expected " + sent.expected().getBlock() + ")");
				return true; // assume the server threw it away
			}
			return now - sent.at() >= RECENT_MS
					&& mc().level.getBlockState(BlockPos.of(entry.getKey())) == sent.expected();
		});
	}

	/** Every position in reach that the schematic wants and the world doesn't have. */
	private void collectCandidates(long now) {
		candidates.clear();
		double reach = range.get();
		int limit = (int) Math.ceil(reach);
		BlockPos origin = mc().player.blockPosition();
		Vec3 eye = mc().player.getEyePosition();
		AABB playerBox = mc().player.getBoundingBox();

		for (int dx = -limit; dx <= limit; dx++) {
			for (int dy = -limit; dy <= limit; dy++) {
				for (int dz = -limit; dz <= limit; dz++) {
					scan.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (eye.distanceToSqr(scan.getX() + 0.5, scan.getY() + 0.5, scan.getZ() + 0.5)
							> reach * reach) {
						continue;
					}
					if (playerBox.intersects(new AABB(scan))) {
						// we're standing in it. Not written off: with movement on, this is
						// a reason to step aside, and the position comes back the moment
						// we do
						continue;
					}
					if (!needsWork(scan, now)) {
						continue;
					}
					candidates.add(scan.immutable());
				}
			}
		}
	}

	/**
	 * Whether this position still needs a block placed, judged <em>without</em> reference
	 * to where the player is standing.
	 *
	 * <p>One definition, wherever the question is asked, so the plan can never chase
	 * work the placer would refuse — the two drifting apart is exactly the kind of bug
	 * that looks like the printer wandering off for no reason.
	 *
	 * <p>Stance-dependent tests (reach, and the player's own box) stay in the caller,
	 * since they are true from one place and false from another.
	 */
	private boolean needsWork(BlockPos pos, long now) {
		if (unsolvable.containsKey(pos.asLong())) {
			return false;
		}
		if (pending.containsKey(pos.asLong())) {
			// a click is in flight. Waiting for the *expected* state rather than merely a
			// different one is what keeps a stacking block from racing ahead of the
			// server and ending up with more layers than the schematic asked for;
			// expirePending is what lets it through once it has landed.
			return false;
		}
		return wantsBlock(pos);
	}

	/**
	 * Whether the schematic wants a block here that the world does not have — the lasting
	 * part of the question, with nothing momentary in it.
	 *
	 * <p>Kept apart from {@link #needsWork} because the two are asked at different times
	 * for different reasons, and conflating them was a real bug. {@link #plan} builds
	 * its picture of the band once per pass; if that picture is filtered by the momentary
	 * state — a click in flight, a position written off thirty ticks ago — then those
	 * positions are simply <em>absent</em> for the rest of the pass, invisible to the route
	 * even once they come good again. The scan asks this; everything else asks
	 * {@link #needsWork}, which layers the momentary filters on top.
	 */
	private boolean wantsBlock(BlockPos pos) {
		return wantsBlock(pos, true);
	}

	/**
	 * As {@link #wantsBlock(BlockPos)}; the tally passes {@code honourLayerRange} false
	 * because Auto layers clamps Litematica's view to the band being built, and totals
	 * clamped to one band would tell the HUD the rest of the schematic does not exist.
	 */
	private boolean wantsBlock(BlockPos pos, boolean honourLayerRange) {
		// A named schematic is a fence, not a preference: the ghost world holds every
		// placement's blocks at once, so without this the printer would happily build a
		// neighbouring placement the moment one drifted into reach
		if (scoped && (printRegion == null
				|| !printRegion.contains(pos.getX(), pos.getY(), pos.getZ()))) {
			return false;
		}
		if (honourLayerRange
				&& !LitematicaBridge.withinLayerRange(pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		BlockState required = LitematicaBridge.required(pos);
		if (required == null || required.isAir() || !required.getFluidState().isEmpty()) {
			return false;
		}
		BlockState current = mc().level.getBlockState(pos);
		// state, not block: snow that needs three layers has the right *block* after the
		// first click and would otherwise count as finished
		if (current == required) {
			return false;
		}
		if (current.getBlock() == required.getBlock()) {
			// Same block, different state. Either it just needs more clicks (snow layers,
			// slab to double) or the difference is one vanilla derives from the
			// surroundings and no click can set — a fence's connections are whatever is
			// next to it here, not what the file recorded. Only worth the (pricier)
			// settle() call on this narrow path.
			if (current == PlacementSolver.settle(required, pos)) {
				return false;
			}
		} else if (!current.canBeReplaced()) {
			return false; // a different solid block; breaking it is a later phase
		}
		if (ignore.contains(required.getBlock())
				|| (!only.get().isEmpty() && !only.contains(required.getBlock()))) {
			return false;
		}
		if (required.getBlock().asItem() == Items.AIR) {
			return false; // nothing a player could hold places this
		}
		return required.canSurvive(mc().level, pos); // no support yet — a later pass gets it
	}

	/**
	 * Moves the player so the printer can finish a schematic bigger than its reach.
	 *
	 * <p>The design principle, learned the hard way: <b>the plan owns geometry, feedback
	 * owns only the throttle.</b> An earlier version chose where to go per tick from
	 * scored candidates, with half a dozen reflexes patching the failure modes that
	 * caused — and the interaction of those reflexes became the failure mode. Now the
	 * route over a band is computed once from a snapshot of what is missing
	 * ({@link #plan}), flown plainly ({@link #drive}) at a height fixed for the whole
	 * band, and the only thing decided per tick is speed. Bobbing, dithering and flying
	 * to empty ground are not patched here; they are unrepresentable.
	 *
	 * <p>The cycle per band is scan, fly the lanes, settle, rescan. A rescan that finds
	 * nothing moves the band up; one that finds leftovers flies again as long as passes
	 * keep placing something, and otherwise books what is stuck and moves on — so the
	 * module always terminates, and says what it could not do.
	 */
	private void navigate(long now) {
		LitematicaBridge.Region bounds = printRegion;
		if (bounds == null) {
			return; // nothing placed, so nowhere to go
		}
		if (!bounds.equals(region)) {
			restartPlan(bounds);
		}
		// Hands off while the player steers; the plan survives and resumes after.
		if (MoveUtil.hasInput(mc().player)) {
			return;
		}
		switch (phase) {
			case PLAN -> plan(now);
			case DRIVE -> drive();
			case SETTLE -> settle();
		}
		if (showRoute.get()) {
			renderRoute();
		}
	}

	/** A new or moved placement means a new plan, from the bottom band up. */
	private void restartPlan(LitematicaBridge.Region bounds) {
		region = bounds;
		finished = false;
		if (autoLayers.get()) {
			startBand(bounds.min().getY(), Math.min(
					bounds.min().getY() + bandHeight.getInt() - 1, bounds.max().getY()));
		} else {
			// the user drives the layer slider by hand; the whole height is one band
			startBand(bounds.min().getY(), bounds.max().getY());
		}
	}

	private void startBand(int minY, int maxY) {
		bandMinY = minY;
		bandMaxY = maxY;
		passesThisBand = 0;
		placedThisPass = false;
		planCursor = Integer.MIN_VALUE;
		phase = Phase.PLAN;
		if (autoLayers.get()) {
			if (savedLayers == null) {
				savedLayers = LitematicaBridge.captureLayerView();
			}
			LitematicaBridge.setLayerBand(bandMinY, bandMaxY);
		}
	}

	/**
	 * Scans the band into {@link #bandWork}, a slice of columns per tick, then decides:
	 * fly a pass over it, move the band up, or finish.
	 *
	 * <p>The snapshot is taken with {@link #wantsBlock} — the lasting question only — so
	 * a click in flight or a temporary write-off cannot hide a block from the plan.
	 *
	 * <p>The progress rule is the whole autonomy story: the first pass over a band always
	 * flies, and each further pass must have placed something since the last scan to earn
	 * another. What survives that is genuinely stuck — wrong-facing blocks that need
	 * breaking, spots with no workable click — and gets counted in the closing report
	 * instead of being circled forever.
	 */
	private void plan(long now) {
		hold();
		if (planCursor == Integer.MIN_VALUE) {
			bandWork.clear();
			planCursor = region.min().getX();
		}
		int cells = 0;
		BlockPos.MutableBlockPos walk = new BlockPos.MutableBlockPos();
		while (planCursor <= region.max().getX() && cells < PLAN_CELLS_PER_TICK) {
			for (int y = bandMinY; y <= bandMaxY; y++) {
				for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
					cells++;
					walk.set(planCursor, y, z);
					if (wantsBlock(walk)) {
						bandWork.add(walk.immutable());
					}
				}
			}
			planCursor++;
		}
		if (planCursor <= region.max().getX()) {
			return; // scan continues next tick
		}
		planCursor = Integer.MIN_VALUE;

		note(String.format("scan band %d..%d: %d missing (pass %d, placed since last=%b)",
				bandMinY, bandMaxY, bandWork.size(), passesThisBand, placedThisPass));
		if (bandWork.isEmpty() || (passesThisBand > 0 && !placedThisPass)) {
			if (!bandWork.isEmpty()) {
				note("giving up on band " + bandMinY + ".." + bandMaxY + " with "
						+ bandWork.size() + " left (no progress last pass)");
			}
			advanceBand(now);
			return;
		}
		buildLane();
		note("pass " + (passesThisBand + 1) + ": " + lane.size() + " waypoints, pitch "
				+ lanePitch());
		placedThisPass = false;
		passesThisBand++;
		laneIndex = 0;
		detour = List.of();
		detoured = false;
		resetDriveProgress();
		phase = Phase.DRIVE;
	}

	private void advanceBand(long now) {
		if (autoLayers.get() && bandMaxY < region.max().getY()) {
			startBand(bandMaxY + 1,
					Math.min(bandMaxY + bandHeight.getInt(), region.max().getY()));
			return;
		}
		finishAll(now);
	}

	private void finishAll(long now) {
		finished = true;
		lane.clear();
		if (autoLayers.get() && region != null) {
			// widen back out before counting, or the tally only sees the last band
			LitematicaBridge.setLayerBand(region.min().getY(), region.max().getY());
		}
		int left = countRemaining(now);
		note("finished: " + left + " left");
		// worded for what was actually measured: with an Only or Ignore list set, "left"
		// counts only blocks the printer was allowed to place, so claiming the schematic
		// is complete would be overstating it
		ChatUtil.info(left == 0
				? "Printer: nothing left to place."
				: "Printer: done, " + left + " block(s) it could not place.");
		if (stopWhenDone.get()) {
			setEnabled(false);
		}
	}

	/**
	 * How much of the placement still needs a block. Only for the closing report, so it
	 * scans the whole region — capped, so an enormous schematic cannot stall a tick.
	 */
	private int countRemaining(long now) {
		if (region == null) {
			return 0;
		}
		BlockPos.MutableBlockPos walk = new BlockPos.MutableBlockPos();
		int count = 0;
		long cells = 0;
		for (int x = region.min().getX(); x <= region.max().getX(); x++) {
			for (int y = region.min().getY(); y <= region.max().getY(); y++) {
				for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
					if (++cells > VERIFY_CELL_CAP) {
						return count;
					}
					walk.set(x, y, z);
					if (needsWork(walk, now)) {
						count++;
					}
				}
			}
		}
		return count;
	}

	/**
	 * Turns {@link #bandWork} into serpentine lanes.
	 *
	 * <p>Lanes run along the footprint's longer axis, spaced so everything between two
	 * lanes is within placing reach of one of them, and exist only where work does: empty
	 * strips are skipped outright, and a long workless stretch splits a lane so the gap
	 * is crossed at full speed or not at all. Each lane segment sits on the mean of its
	 * work across the strip — through the middle of the cluster, not along its edge. The
	 * height is one block above the band for the entire route, which makes level flight
	 * over flat work a property of the plan rather than an aspiration.
	 */
	private void buildLane() {
		lane.clear();
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : bandWork) {
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		boolean alongX = maxX - minX >= maxZ - minZ;
		int pitch = lanePitch();

		// Strips are laid where the work is, not on a fixed grid: walk the work across
		// the footprint and cut a new strip only when the next row no longer fits under
		// one lane's reach. A cluster narrower than the swath gets exactly one lane,
		// through its middle - the fixed grid used to slice clusters at whatever offset
		// it happened to land on, which is how a lane ended up hugging one edge with
		// three rows on one side of it and one on the other.
		List<BlockPos> sorted = new ArrayList<>(bandWork);
		sorted.sort(Comparator.comparingInt(pos -> alongX ? pos.getZ() : pos.getX()));
		List<List<BlockPos>> strips = new ArrayList<>();
		List<BlockPos> strip = null;
		int stripStart = Integer.MIN_VALUE;
		for (BlockPos pos : sorted) {
			int perp = alongX ? pos.getZ() : pos.getX();
			if (strip == null || perp - stripStart >= pitch) {
				strip = new ArrayList<>();
				strips.add(strip);
				stripStart = perp;
			}
			strip.add(pos);
		}

		double routeY = routeY();
		double half = halfWidth();
		boolean reverse = false;
		for (List<BlockPos> laneWork : strips) {
			laneWork.sort(Comparator.comparingInt(pos -> alongX ? pos.getX() : pos.getZ()));
			// segments: {alongStart, alongEnd, lanePerp}, split where the work gaps
			List<double[]> segments = new ArrayList<>();
			int start = Integer.MIN_VALUE;
			int previous = Integer.MIN_VALUE;
			long perpSum = 0;
			int perpMin = Integer.MAX_VALUE;
			int perpMax = Integer.MIN_VALUE;
			int count = 0;
			for (BlockPos pos : laneWork) {
				int along = alongX ? pos.getX() : pos.getZ();
				int perp = alongX ? pos.getZ() : pos.getX();
				if (start == Integer.MIN_VALUE) {
					start = along;
				} else if (along - previous > LANE_GAP_SPLIT) {
					segments.add(new double[] { start, previous,
							segmentPerp(perpSum, count, perpMin, perpMax, half) });
					start = along;
					perpSum = 0;
					perpMin = Integer.MAX_VALUE;
					perpMax = Integer.MIN_VALUE;
					count = 0;
				}
				previous = along;
				perpSum += perp;
				perpMin = Math.min(perpMin, perp);
				perpMax = Math.max(perpMax, perp);
				count++;
			}
			if (start != Integer.MIN_VALUE) {
				segments.add(new double[] { start, previous,
						segmentPerp(perpSum, count, perpMin, perpMax, half) });
			}
			if (reverse) {
				Collections.reverse(segments);
			}
			for (double[] segment : segments) {
				double lanePerp = segment[2];
				double from = (reverse ? segment[1] : segment[0]) + 0.5;
				double to = (reverse ? segment[0] : segment[1]) + 0.5;
				lane.add(alongX ? new Vec3(from, routeY, lanePerp)
						: new Vec3(lanePerp, routeY, from));
				lane.add(alongX ? new Vec3(to, routeY, lanePerp)
						: new Vec3(lanePerp, routeY, to));
			}
			reverse = !reverse;
		}
		// Fly the route from whichever end is nearer. Always starting at the same corner
		// meant hauling back across the whole build after every band — the single
		// weirdest-looking habit the router had — and a serpentine reversed is still a
		// serpentine, so the coverage guarantee is untouched.
		if (lane.size() > 1) {
			Vec3 here = mc().player.position();
			if (here.distanceToSqr(lane.get(lane.size() - 1)) < here.distanceToSqr(lane.get(0))) {
				Collections.reverse(lane);
			}
		}
	}

	/** The flying height for this band: feet one block above its top. */
	private double routeY() {
		return bandMaxY + 1;
	}

	/**
	 * Where a lane segment sits across its strip: the mean of its work, clamped so both
	 * extremes stay within reach. The mean alone follows the mass, and skewed work could
	 * pull the lane far enough that the far row fell out of reach entirely.
	 */
	private static double segmentPerp(long perpSum, int count, int perpMin, int perpMax,
			double half) {
		double mean = (double) perpSum / count + 0.5;
		double lowest = perpMax + 0.5 - (half - 0.5);
		double highest = perpMin + 0.5 + (half - 0.5);
		return lowest > highest ? mean : Math.max(lowest, Math.min(highest, mean));
	}

	/**
	 * What remains of reach after the height a lane flies at: the half-width one lane
	 * covers across itself.
	 */
	private double halfWidth() {
		double eyeAbove = routeY() + EYE_HEIGHT - (bandMinY + 0.5);
		double reach = range.get();
		return reach > eyeAbove ? Math.sqrt(reach * reach - eyeAbove * eyeAbove) : 1.0;
	}

	/**
	 * Lane spacing, derived from reach: two half-widths, minus a block so the swath
	 * edges overlap. Reach 4.5 over a one-high band gives a pitch of six.
	 */
	private int lanePitch() {
		return Math.max(1, (int) (2.0 * halfWidth()) - 1);
	}

	/**
	 * Follows the lane. Speed is the only decision made here — crawl while unplaced work
	 * is in reach, so nothing exits reach before the placer has had several ticks at it,
	 * and full speed over finished ground. Geometry belongs to the lane.
	 *
	 * <p>The one exception is an obstacle the plan did not know about — terrain poking
	 * above the band, most often. A blocked waypoint gets one {@link FlightPath} detour,
	 * and if that fails too it is skipped rather than fought; the next pass will see
	 * whatever was missed.
	 */
	private void drive() {
		if (detour.isEmpty() && laneIndex >= lane.size()) {
			settleTicks = 0;
			phase = Phase.SETTLE;
			return;
		}
		Vec3 target = detour.isEmpty()
				? lane.get(laneIndex)
				: Vec3.atBottomCenterOf(detour.get(0));
		Vec3 delta = target.subtract(mc().player.position());
		double distanceSq = delta.lengthSqr();
		if (distanceSq <= WAYPOINT_REACHED * WAYPOINT_REACHED) {
			if (detour.isEmpty()) {
				laneIndex++;
				detoured = false;
			} else {
				detour = detour.subList(1, detour.size());
			}
			resetDriveProgress();
			return;
		}
		if (distanceSq < closestSoFar - PROGRESS_EPSILON) {
			closestSoFar = distanceSq;
			stalledTicks = 0;
		} else if (++stalledTicks > STALL_TICKS) {
			blockedWaypoint(target);
			return;
		}
		grantFlight();
		double speed = flySpeed.get() * (candidates.isEmpty() ? 1.0 : WORK_ZONE_SPEED);
		Vec3 step = distanceSq <= speed * speed ? delta : delta.normalize().scale(speed);
		mc().player.setDeltaMovement(clearStep(step, speed));
	}

	/**
	 * Lifts a step just enough to slide over whatever the body would clip.
	 *
	 * <p>The lane's height is right for reach, but the world is not obliged to be clear
	 * there: terrain crosses the art, and partial blocks — snow layers, carpet, walls —
	 * poke fractions over the surface the route hugs. A collision that small used to stop
	 * flight dead until the stall check noticed, seconds later; a player just taps space.
	 * Up-only on purpose: coming back down is the lane's job, pulled by the next
	 * waypoint's fixed height, so this assist cannot reintroduce bobbing — it can only
	 * lift, and only while something is actually in the way.
	 */
	private Vec3 clearStep(Vec3 step, double speed) {
		Vec3 from = mc().player.position();
		if (FlightPath.fitsAt(from.add(step))) {
			return step;
		}
		// smallest lift that clears: a snow layer costs centimetres, a kerb a half block
		for (double climb : new double[] { 0.2, 0.5, 1.0 }) {
			Vec3 lifted = new Vec3(step.x, Math.max(step.y, climb), step.z);
			if (FlightPath.fitsAt(from.add(lifted))) {
				return lifted;
			}
		}
		return new Vec3(0.0, speed, 0.0); // a wall: rise until the forward step clears
	}

	/** One detour attempt per waypoint; a waypoint that defeats that too is skipped. */
	private void blockedWaypoint(Vec3 target) {
		resetDriveProgress();
		if (!detoured) {
			detoured = true;
			List<BlockPos> around = FlightPath.find(mc().player.blockPosition(),
					BlockPos.containing(target), FlightPath.DEFAULT_BUDGET);
			if (!around.isEmpty()) {
				detour = around;
				note("waypoint " + laneIndex + " blocked at "
						+ BlockPos.containing(target).toShortString() + ", detouring "
						+ around.size() + " steps");
				return;
			}
		}
		// unreachable: leave its work to the next pass and carry on with the lane
		note("waypoint " + laneIndex + " unreachable at "
				+ BlockPos.containing(target).toShortString() + ", skipped");
		detour = List.of();
		detoured = false;
		laneIndex++;
	}

	private void resetDriveProgress() {
		stalledTicks = 0;
		closestSoFar = Double.MAX_VALUE;
	}

	/**
	 * Parks at the end of the route until in-flight clicks have landed, so the rescan
	 * judges what actually happened rather than what is still in the post.
	 */
	private void settle() {
		hold();
		if (pending.isEmpty() || ++settleTicks > SETTLE_TICKS_MAX) {
			planCursor = Integer.MIN_VALUE;
			phase = Phase.PLAN;
		}
	}

	/**
	 * Keeps flight asserted and the player parked. Without this, ticks where nothing
	 * steers hand the body back to vanilla, which unsets flying and lets drag and
	 * gravity sag the player — the original cause of drifting over flat work.
	 */
	private void hold() {
		grantFlight();
		mc().player.setDeltaMovement(Vec3.ZERO);
	}

	/** The next stretch of lane, drawn in-world so a wrong plan is visible, not a mystery. */
	private void renderRoute() {
		int shown = 0;
		for (int i = Math.min(laneIndex, Math.max(0, lane.size() - 1));
				i < lane.size() && shown < ROUTE_PREVIEW; i++, shown++) {
			// green for where it is headed right now, white for the rest of the plan
			boolean next = i == laneIndex;
			Render3D.blockBox(BlockPos.containing(lane.get(i)),
					next ? 0xC040FF60 : 0x80FFFFFF, next ? 2.0f : 1.5f,
					next ? 0x4040FF60 : 0, true);
		}
	}
	// ---- progress stats (for the HUD widgets) -----------------------------------

	/** Blocks placed since the module was switched on. */
	private int totalPlaced;
	/** Per-second placement counts over the last minute: {epochSecond, count}. */
	private final ArrayDeque<long[]> placeHistory = new ArrayDeque<>();
	/** The placement the tally is walking; a change restarts it. */
	private LitematicaBridge.Region tallyRegion;
	/** X column the tally has reached; MIN_VALUE when between cycles. */
	private int tallyCursor = Integer.MIN_VALUE;
	private int tallyMissing;
	private final Map<Item, Integer> tallyItems = new HashMap<>();
	/** Last completed cycle's results; -1 / empty until a first cycle lands. */
	private int totalMissing = -1;
	private int placedAtTally;
	private List<Map.Entry<Item, Integer>> materialsView = List.of();
	/** Ticks left of the pause between tally cycles. */
	private int tallyRest;
	/** Ticks worked on the current print — see {@link #elapsedSeconds()}. */
	private int workTicks;
	/** Which job the counters belong to: schematic pick plus the region it resolved to. */
	private String printKey;

	/** Cells the tally visits per tick — deliberately lighter than the band scan. */
	private static final int TALLY_CELLS_PER_TICK = 20_000;
	/** Ticks between tally cycles, so the background scan is mostly asleep. */
	private static final int TALLY_REST_TICKS = 60;
	/** Seconds of placement history the rate is measured over. */
	private static final long RATE_WINDOW_S = 60;

	private void recordPlaced(int count) {
		totalPlaced += count;
		long second = System.currentTimeMillis() / 1000L;
		long[] tail = placeHistory.peekLast();
		if (tail != null && tail[0] == second) {
			tail[1] += count;
		} else {
			placeHistory.addLast(new long[] { second, count });
		}
		while (!placeHistory.isEmpty()
				&& second - placeHistory.peekFirst()[0] > RATE_WINDOW_S) {
			placeHistory.removeFirst();
		}
	}

	/**
	 * Keeps a whole-schematic count of missing blocks and materials ticking over in the
	 * background: one budgeted walk of the placement, a rest, then again. Continuous
	 * re-scanning means the numbers correct themselves — placements, failures and hand
	 * edits all land in the next cycle without any bookkeeping to get wrong.
	 */
	private void tallyTick() {
		LitematicaBridge.Region bounds = printRegion;
		if (bounds == null) {
			totalMissing = -1;
			materialsView = List.of();
			tallyCursor = Integer.MIN_VALUE;
			return;
		}
		if (!bounds.equals(tallyRegion)) {
			tallyRegion = bounds;
			tallyCursor = Integer.MIN_VALUE;
			tallyRest = 0;
		}
		if (tallyCursor == Integer.MIN_VALUE && tallyRest > 0) {
			tallyRest--;
			return;
		}
		if (tallyCursor == Integer.MIN_VALUE) {
			tallyMissing = 0;
			tallyItems.clear();
			tallyCursor = bounds.min().getX();
		}
		int cells = 0;
		BlockPos.MutableBlockPos walk = new BlockPos.MutableBlockPos();
		while (tallyCursor <= bounds.max().getX() && cells < TALLY_CELLS_PER_TICK) {
			for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
				for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
					cells++;
					walk.set(tallyCursor, y, z);
					if (!wantsBlock(walk, false)) {
						continue;
					}
					tallyMissing++;
					BlockState required = LitematicaBridge.required(walk);
					if (required != null) {
						Item item = required.getBlock().asItem();
						if (dirtAsGrass.get() && item == Items.GRASS_BLOCK) {
							item = Items.DIRT;
						}
						if (item != Items.AIR) {
							tallyItems.merge(item, 1, Integer::sum);
						}
					}
				}
			}
			tallyCursor++;
		}
		if (tallyCursor > bounds.max().getX()) {
			totalMissing = tallyMissing;
			placedAtTally = totalPlaced;
			List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(tallyItems.entrySet());
			sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
			materialsView = List.copyOf(sorted);
			tallyCursor = Integer.MIN_VALUE;
			tallyRest = TALLY_REST_TICKS;
		}
	}

	/**
	 * Refills the Schematic picker from what Litematica has placed, and resolves the
	 * choice into {@link #printRegion} for the tick.
	 *
	 * <p>A pick that is no longer placed falls back to All rather than silently building
	 * nothing: the schematic being gone is the normal way that happens (you unloaded it),
	 * and a printer that sits still with no explanation is the worse failure.
	 */
	private void refreshSchematics() {
		List<String> modes = new ArrayList<>();
		modes.add(ALL);
		modes.addAll(LitematicaBridge.placementNames());
		if (!modes.equals(schematic.getModes())) {
			schematic.setModes(modes);
		}
		if (!modes.contains(schematic.get())) {
			schematic.set(ALL);
		}
		scoped = !schematic.is(ALL);
		printRegion = LitematicaBridge.bounds(scoped ? schematic.get() : null);
	}

	/**
	 * Starts a fresh set of counters whenever the job changes.
	 *
	 * <p>Identity is the pick plus the region it resolves to, so moving a placement or
	 * switching to another one begins a new print: totals and elapsed time that ran two
	 * builds together would describe neither.
	 */
	private void newPrintCheck() {
		String key = schematic.get() + "@" + printRegion.min() + printRegion.max();
		if (key.equals(printKey)) {
			return;
		}
		printKey = key;
		workTicks = 0;
		totalPlaced = 0;
		placeHistory.clear();
		tallyRegion = null;
		tallyCursor = Integer.MIN_VALUE;
		totalMissing = -1;
		materialsView = List.of();
	}

	/** Blocks placed since the module came on. */
	public int placedTotal() {
		return totalPlaced;
	}

	/**
	 * Seconds of work on the current print.
	 *
	 * <p>Counted in ticks rather than off the wall clock, which makes every pause free:
	 * a closed inventory screen, a missing schematic, a finished build, the module
	 * switched off, the game paused in singleplayer — none of them tick, so none of them
	 * are counted, and nothing has to know it is a pause.
	 */
	public long elapsedSeconds() {
		return workTicks / 20;
	}

	/** Whole-schematic missing count, live-adjusted between tallies; -1 while unknown. */
	public int missingTotal() {
		if (totalMissing < 0) {
			return -1;
		}
		return Math.max(0, totalMissing - (totalPlaced - placedAtTally));
	}

	/** Blocks per second over the last minute of placing. */
	public double placeRate() {
		long second = System.currentTimeMillis() / 1000L;
		long sum = 0;
		long oldest = second;
		for (long[] entry : placeHistory) {
			sum += entry[1];
			oldest = Math.min(oldest, entry[0]);
		}
		return sum == 0 ? 0.0 : (double) sum / Math.max(1, second - oldest);
	}

	/** Seconds to finish everything at the current rate; -1 while unknowable. */
	public long etaSeconds() {
		int missing = missingTotal();
		double rate = placeRate();
		return missing < 0 || rate < 0.01 ? -1 : Math.round(missing / rate);
	}

	/** Materials the whole schematic still needs, largest first. Empty until counted. */
	public List<Map.Entry<Item, Integer>> materials() {
		return materialsView;
	}

	/** One line of what the automation is doing, for the HUD. */
	public String hudStatus() {
		if (!LitematicaBridge.hasSchematic()) {
			return "waiting for a schematic";
		}
		if (printRegion == null) {
			return scoped ? schematic.get() + " is not placed" : "nothing placed";
		}
		if (finished) {
			return "done";
		}
		if (movement.is("Off")) {
			return "printing in place";
		}
		String band = "band " + bandMinY + (bandMaxY != bandMinY ? ".." + bandMaxY : "")
				+ " pass " + Math.max(1, passesThisBand);
		return switch (phase) {
			case PLAN -> "scanning " + band;
			case DRIVE -> band + ", waypoint " + Math.min(laneIndex + 1, lane.size())
					+ "/" + lane.size();
			case SETTLE -> band + ", settling";
		};
	}

	// ---- in-game bug reports ----------------------------------------------------

	/** Rolling trail of what the automation decided, for {@link #report}. */
	private final ArrayDeque<String> events = new ArrayDeque<>();

	/** Entries the trail keeps; older ones fall off the front. */
	private static final int EVENT_TRAIL = 300;

	/**
	 * Adds to the event trail. An event identical to one still in the trail is dropped,
	 * which is what keeps a block that is re-written-off every few seconds from flooding
	 * out the history around it.
	 */
	private void note(String event) {
		for (String kept : events) {
			if (kept.endsWith(event)) {
				return;
			}
		}
		events.addLast(String.format("[%tT] %s", new java.util.Date(), event));
		if (events.size() > EVENT_TRAIL) {
			events.removeFirst();
		}
	}

	/**
	 * Writes a full diagnostic to {@code config/unlucky/printer-reports/}: the route and
	 * phase state, the recent event trail, and — for the block under the crosshair — the
	 * schematic-vs-world comparison, every filter's verdict in the order the printer
	 * applies them, and a live solver run. Built so "it missed one", seen in game, can be
	 * handed over with the whole story attached instead of a guess: look at the gap, type
	 * {@code .report}, keep playing.
	 */
	public void report(java.util.function.Consumer<String> out) {
		if (mc().player == null || mc().level == null) {
			out.accept("Join a world first");
			return;
		}
		long now = System.currentTimeMillis();
		List<String> lines = new ArrayList<>();
		lines.add("Unlucky Printer report " + java.time.LocalDateTime.now());
		lines.add(String.format("player %.2f %.2f %.2f yaw=%.1f pitch=%.1f",
				mc().player.getX(), mc().player.getY(), mc().player.getZ(),
				mc().player.getYRot(), mc().player.getXRot()));
		lines.add(String.format(
				"state: %s band=%d..%d pass=%d waypoint=%d/%d cand=%d pending=%d unsolvable=%d finished=%b",
				phase, bandMinY, bandMaxY, passesThisBand, laneIndex, lane.size(),
				candidates.size(), pending.size(), unsolvable.size(), finished));
		lines.add("settings: movement=" + movement.get() + " speed=" + flySpeed.get()
				+ " bandHeight=" + bandHeight.getInt() + " precise=" + precise.get()
				+ " airPlace=" + airPlace.get() + " throughWalls=" + throughWalls.get()
				+ " range=" + range.get() + " perTick=" + perTick.getInt());
		LitematicaBridge.LayerView view = LitematicaBridge.captureLayerView();
		lines.add("litematica view: " + (view == null ? "none"
				: view.mode() + "[" + view.rangeMin() + ".." + view.rangeMax() + "]"));
		lines.add("region: " + (region == null ? "none"
				: region.min().toShortString() + " .. " + region.max().toShortString()));
		lines.add("");

		BlockPos target = findReportTarget();
		if (target == null) {
			lines.add("target: nothing missing along the crosshair (looked 60 blocks out)");
		} else {
			trace(target, now, lines);
		}

		lines.add("");
		lines.add("route (" + lane.size() + " waypoints, > = next, x = done):");
		for (int i = 0; i < lane.size(); i++) {
			Vec3 point = lane.get(i);
			lines.add(String.format("  %s%3d: %.1f %.1f %.1f",
					i < laneIndex ? "x" : i == laneIndex ? ">" : " ", i,
					point.x, point.y, point.z));
		}
		lines.add("");
		lines.add("events (oldest first):");
		for (String event : events) {
			lines.add("  " + event);
		}

		try {
			java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getConfigDir().resolve("unlucky/printer-reports");
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Path file = dir.resolve("report-" + new java.text.SimpleDateFormat(
					"yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
			java.nio.file.Files.write(file, lines);
			out.accept("Report saved: " + file.getFileName() + (target == null
					? " (no missing block on the crosshair)"
					: " (target " + target.toShortString() + ")"));
		} catch (java.io.IOException e) {
			out.accept("Could not write report: " + e.getMessage());
		}
	}

	/**
	 * What the report is about: the first position along the crosshair where the
	 * schematic wants a block the world does not have. Ghost blocks have no collision,
	 * so a plain raycast slides straight through the very thing being pointed at —
	 * walking the sight line against the schematic is what makes "look at the gap and
	 * type .report" land on the gap.
	 */
	private BlockPos findReportTarget() {
		Vec3 eye = mc().player.getEyePosition();
		Vec3 look = mc().player.getLookAngle();
		BlockPos last = null;
		for (double t = 0.5; t <= 60.0; t += 0.25) {
			BlockPos pos = BlockPos.containing(eye.add(look.scale(t)));
			if (pos.equals(last)) {
				continue;
			}
			last = pos;
			BlockState required = LitematicaBridge.required(pos);
			if (required != null && !required.isAir() && required.getFluidState().isEmpty()
					&& mc().level.getBlockState(pos) != required) {
				return pos;
			}
			if (!mc().level.getBlockState(pos).canBeReplaced()) {
				// the sight line reached real, correct world — nothing ghostly before it
				return null;
			}
		}
		return null;
	}

	/** Every filter's verdict for one position, in the order the printer applies them. */
	private void trace(BlockPos pos, long now, List<String> lines) {
		BlockState required = LitematicaBridge.required(pos);
		BlockState current = mc().level.getBlockState(pos);
		lines.add("target " + pos.toShortString() + ":");
		lines.add("  required: " + required);
		lines.add("  current:  " + current);
		if (required == null || required.isAir()) {
			lines.add("  verdict: schematic wants nothing here");
			return;
		}
		if (!required.getFluidState().isEmpty()) {
			lines.add("  verdict: schematic wants a fluid - not placeable by hand");
			return;
		}
		lines.add("  in current band: " + (pos.getY() >= bandMinY && pos.getY() <= bandMaxY)
				+ " | in last scan: " + bandWork.contains(pos));
		lines.add("  layer range: " + (LitematicaBridge.withinLayerRange(
				pos.getX(), pos.getY(), pos.getZ()) ? "in" : "OUT"));
		Long gaveUpAt = unsolvable.get(pos.asLong());
		lines.add("  unsolvable: " + (gaveUpAt == null ? "no"
				: "written off " + (now - gaveUpAt) + "ms ago"));
		Pending inFlight = pending.get(pos.asLong());
		lines.add("  pending: " + (inFlight == null ? "no"
				: "expecting " + inFlight.expected() + ", " + (now - inFlight.at()) + "ms old"));
		lines.add("  settled target: " + PlacementSolver.settle(required, pos));
		lines.add("  only/ignore: " + (ignore.contains(required.getBlock()) ? "IGNORED"
				: !only.get().isEmpty() && !only.contains(required.getBlock())
						? "not in Only" : "pass"));
		Item item = required.getBlock().asItem();
		if (dirtAsGrass.get() && item == Items.GRASS_BLOCK) {
			item = Items.DIRT;
		}
		lines.add("  item: " + item + " | available: " + hasItem(item));
		lines.add("  canSurvive: " + required.canSurvive(mc().level, pos));
		lines.add("  replaceable: " + (current.getBlock() == required.getBlock()
				? "same block, " + (current == PlacementSolver.settle(required, pos)
						? "settled state matches (counts as done)" : "state differs")
				: String.valueOf(current.canBeReplaced())));
		lines.add(String.format("  reach: %.2f of %.2f from here",
				Math.sqrt(mc().player.getEyePosition().distanceToSqr(
						pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)),
				range.get()));
		lines.add("  wantsBlock: " + wantsBlock(pos) + " | needsWork: " + needsWork(pos, now));
		PlacementSolver.Solution solution = PlacementSolver.solve(pos, required,
				item.getDefaultInstance(),
				new PlacementSolver.Options(range.get(), airPlace.get(), throughWalls.get(),
						sneak.get(), !rotate.is("Off")));
		if (solution == null) {
			lines.add("  solver: REFUSED - no click from here predicts any improvement");
		} else {
			lines.add(String.format(
					"  solver: click %s face=%s -> predicts %s (exact=%b convergent=%b%s)",
					solution.hit().getBlockPos().toShortString(), solution.hit().getDirection(),
					solution.predicted(), solution.exact(), solution.convergent(),
					precise.get() && !solution.exact() && !solution.convergent()
							? " - GATED by Precise" : ""));
		}
	}

	/** Turns flight on, remembering whether it was ours to give. */
	private void grantFlight() {
		Abilities abilities = mc().player.getAbilities();
		if (!abilities.mayfly) {
			abilities.mayfly = true;
			mc().player.onUpdateAbilities();
			grantedFlight = true;
		}
		abilities.flying = true;
	}

	private void releaseFlight() {
		if (!grantedFlight) {
			return;
		}
		grantedFlight = false;
		if (mc().player == null || mc().player.isCreative()) {
			return;
		}
		Abilities abilities = mc().player.getAbilities();
		abilities.mayfly = false;
		abilities.flying = false;
		mc().player.onUpdateAbilities();
	}

	private void sortCandidates() {
		Vec3 eye = mc().player.getEyePosition();
		Comparator<BlockPos> order = switch (sort.get()) {
			case "Furthest" -> Comparator.comparingDouble(pos -> -distanceSqr(eye, pos));
			case "Bottom up" -> Comparator.comparingInt(BlockPos::getY);
			case "Top down" -> Comparator.comparingInt((BlockPos pos) -> -pos.getY());
			default -> Comparator.comparingDouble(pos -> distanceSqr(eye, pos));
		};
		// within a layer, still work outward from where we're standing
		if (sort.is("Bottom up") || sort.is("Top down")) {
			order = order.thenComparingDouble(pos -> distanceSqr(eye, pos));
		}
		candidates.sort(order);
	}

	private static double distanceSqr(Vec3 eye, BlockPos pos) {
		return eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/** Places the schematic's block at {@code pos}. The solution when a click went out. */
	private PlacementSolver.Solution placeAt(BlockPos pos) {
		BlockState required = LitematicaBridge.required(pos);
		if (required == null || required.isAir()) {
			return null;
		}
		Item item = required.getBlock().asItem();
		if (dirtAsGrass.get() && item == Items.GRASS_BLOCK) {
			item = Items.DIRT;
		}
		// checked before solving, so a missing item costs no simulation and no slot switch
		if (!hasItem(item)) {
			note("out of " + item);
			return null;
		}

		// Solved against a plain stack of the item rather than the held one: what the
		// placement rules read off it is the item's identity, and this way nothing is
		// equipped until a click is known to be worth sending.
		PlacementSolver.Solution solution = PlacementSolver.solve(pos, required,
				item.getDefaultInstance(),
				new PlacementSolver.Options(range.get(), airPlace.get(), throughWalls.get(),
						sneak.get(), !rotate.is("Off")));
		// Precise refuses a state that is *wrong*, not one that is merely unfinished: a
		// convergent click is the second of three snow layers, and demanding an exact match
		// would rule those out entirely.
		if (solution == null || (precise.get() && !solution.exact() && !solution.convergent())) {
			note("gave up at " + pos.toShortString() + ": " + (solution == null
					? "no click improves it"
					: "Precise refused, best predicted " + solution.predicted()));
			// nothing clickable gets us closer; stop re-solving it every tick
			unsolvable.put(pos.asLong(), System.currentTimeMillis());
			return null;
		}
		if (!aim(solution)) {
			return null; // still turning
		}
		if (!equip(item)) {
			return null;
		}
		sendClick(solution.hit());
		return solution;
	}

	/**
	 * Points the player where the solution needs them. True once the click may be sent.
	 *
	 * <p>The rotation is spoofed server-side, so the server derives the state we predicted
	 * while the camera stays put. The client's own prediction still uses the camera angle
	 * and can briefly show a differently-turned block; the server's update corrects it.
	 */
	private boolean aim(PlacementSolver.Solution solution) {
		if (rotate.is("Off")) {
			return true; // solved against the current facing already
		}
		if (smoothTurning()) {
			// turn first, act when aimed — a click sent mid-turn carries the wrong angle
			Vec3 target = PlacementSolver.lookPoint(mc().player.getEyePosition(),
					solution.yaw(), solution.pitch(), 4.0);
			boolean aimed = RotationManager.face(target, turnSpeed.get().floatValue());
			// the turn's current step, so the hold resumes from where it got to
			rememberAim(RotationManager.getYaw(), RotationManager.getPitch());
			return aimed;
		}
		RotationManager.rotate(solution.yaw(), solution.pitch());
		rememberAim(solution.yaw(), solution.pitch());
		return true;
	}

	private void rememberAim(float yaw, float pitch) {
		aimYaw = yaw;
		aimPitch = pitch;
		aimAtMs = System.currentTimeMillis();
	}

	/**
	 * Keeps looking where the work is on the ticks between clicks.
	 *
	 * <p>Aiming only on the ticks a click goes out is what made the third-person
	 * rotation invisible, and it took a frame counter to see why: the pose was applied
	 * on <b>20% of frames</b> ({@code .rot} reported 22229 of 137590) and the model sat
	 * at the camera angle for the other 80%. Every part of the chain worked — the state
	 * was posed, and the values we wrote already matched what vanilla had built from the
	 * spoofed pose — so all three earlier fixes were repairs to links that were not
	 * broken. What was missing is that a rotation held for four ticks per burst is a
	 * flicker, and a flicker reads as nothing at all.
	 *
	 * <p>Re-asserted at the head of the tick so a fresh {@link #aim} later in the same
	 * tick still wins. Costs no packets: RotationManager only sends when the angle
	 * actually changes.
	 */
	private void holdAim() {
		if (rotate.is("Off") || aimAtMs == 0L
				|| System.currentTimeMillis() - aimAtMs > AIM_HOLD_MS) {
			return;
		}
		RotationManager.rotate(aimYaw, aimPitch);
	}

	/** Whether the item is somewhere we can get at it this tick. */
	private boolean hasItem(Item item) {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (inventory.getItem(slot).is(item)) {
				return true;
			}
		}
		return mc().player.isCreative() && creativeRestock.get();
	}

	/**
	 * Sends the use-on click with sneak briefly forced, then puts the input back so
	 * movement is unaffected.
	 *
	 * <p>Sneaking is what stops a click being read as an interaction (opening a door,
	 * cycling a repeater) instead of a placement. Both the input record and the entity
	 * flag are set: the flag is what {@code isSecondaryUseActive} reads for the
	 * placement we predict locally, while {@code keyPresses} is what the next input
	 * packet carries. Containers are avoided outright in {@link #clickFor} rather than
	 * trusted to this, since a mis-timed packet there costs an open screen.
	 */
	private void sendClick(BlockHitResult hit) {
		Input original = mc().player.input.keyPresses;
		boolean wasSneaking = mc().player.isShiftKeyDown();
		if (sneak.get()) {
			mc().player.input.keyPresses = new Input(original.forward(), original.backward(),
					original.left(), original.right(), original.jump(), true, original.sprint());
			mc().player.setShiftKeyDown(true);
		}
		try {
			mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
			if (swing.get()) {
				mc().player.swing(InteractionHand.MAIN_HAND);
			}
		} finally {
			mc().player.input.keyPresses = original;
			if (sneak.get()) {
				mc().player.setShiftKeyDown(wasSneaking);
			}
		}
	}

	/** Gets {@code item} into the main hand. False when we simply don't have any. */
	private boolean equip(Item item) {
		Inventory inventory = mc().player.getInventory();
		if (inventory.getItem(inventory.getSelectedSlot()).is(item)) {
			return true;
		}
		int hotbar = InteractUtil.findHotbarItem(item);
		if (hotbar >= 0) {
			rememberSlot();
			select(hotbar);
			return true;
		}
		// only the main inventory left: swap the stack down into a hotbar slot.
		// Above the hotbar, inventory index and inventory-menu slot are the same
		// number, so the index goes straight to swapWithHotbar.
		for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (inventory.getItem(slot).is(item)) {
				rememberSlot();
				InteractUtil.swapWithHotbar(slot, WORK_SLOT);
				select(WORK_SLOT);
				return true;
			}
		}
		if (mc().player.isCreative() && creativeRestock.get() && mc().getConnection() != null) {
			rememberSlot();
			select(WORK_SLOT);
			// set it locally as well as asking the server to: the click we send this
			// same tick is predicted against the client's own held stack, which would
			// still be empty if we only waited for the server to answer
			inventory.setItem(WORK_SLOT, item.getDefaultInstance());
			mc().getConnection().send(new ServerboundSetCreativeModeSlotPacket(
					MENU_HOTBAR_START + WORK_SLOT, item.getDefaultInstance()));
			return true;
		}
		return false;
	}

	/**
	 * Selects a hotbar slot and tells the server immediately.
	 *
	 * <p>Vanilla syncs the carried slot once per tick, which becomes a real bug the
	 * moment a batch uses more than one material: all the use packets arrive while the
	 * server still holds the previous slot, and it places the wrong block — cobblestone
	 * where the schematic wanted carpet, unfixable without breaking. Caught by the first
	 * field report's crosshair trace. Syncing on every switch pairs each click with its
	 * item; vanilla's own once-per-tick send just becomes a harmless duplicate.
	 */
	private void select(int slot) {
		mc().player.getInventory().setSelectedSlot(slot);
		if (mc().getConnection() != null) {
			mc().getConnection().send(new ServerboundSetCarriedItemPacket(slot));
		}
	}

	private void rememberSlot() {
		if (previousSlot < 0) {
			previousSlot = mc().player.getInventory().getSelectedSlot();
		}
	}

	private void restoreSlot() {
		if (previousSlot < 0) {
			return;
		}
		if (returnSlot.get() && mc().player != null) {
			select(previousSlot);
		}
		previousSlot = -1;
	}

	private boolean smoothTurning() {
		return rotate.is("Smooth");
	}

	private void decayFading() {
		for (Iterator<Map.Entry<Long, Integer>> it = fading.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, Integer> entry = it.next();
			if (entry.getValue() <= 1) {
				it.remove();
			} else {
				entry.setValue(entry.getValue() - 1);
			}
		}
	}

	/** Gizmos live one tick, so the fading boxes are re-emitted every tick. */
	private void renderFading() {
		int base = color.get();
		int alpha = (base >>> 24) & 0xFF;
		int rgb = base & 0xFFFFFF;
		int max = Math.max(1, fadeTime.getInt());
		for (Map.Entry<Long, Integer> entry : fading.entrySet()) {
			int faded = (int) (alpha * ((float) entry.getValue() / max));
			if (faded <= 0) {
				continue;
			}
			int fillAlpha = faded / 3;
			BlockPos pos = BlockPos.of(entry.getKey());
			// a zero alpha still reads as "draw a fill" to Render3D unless the whole
			// color is zero, so drop the fill rather than submit an invisible one
			Render3D.blockBox(pos, (faded << 24) | rgb, 1.5f,
					fillAlpha > 0 ? (fillAlpha << 24) | rgb : 0, true);
		}
	}
}
