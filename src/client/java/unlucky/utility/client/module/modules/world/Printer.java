package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ChestStash;
import unlucky.utility.client.util.ContainerUtil;
import unlucky.utility.client.util.FlightPath;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.LitematicaBridge;
import unlucky.utility.client.util.MaterialForecast;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.PlacementSolver;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.ShulkerRestock;

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
			"Degrees per tick.", 20, 1, 180, 1), () -> rotate.is("Smooth"));
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
	public final StringSetting restockBase = add(new StringSetting("Restock base",
			"Where refills happen when there is no stash, as x y z. Set it with .pbase "
					+ "while standing there; empty means find a spot near the work.", ""));
	public final BooleanSetting shulkerRestock = add(new BooleanSetting("Shulker restock",
			"When a block runs out and a carried shulker has it: place the box, take what "
					+ "the work ahead needs, break it and put it back in your bags. Stops "
					+ "with one inventory slot still free, which is where the box lands.", true));
	public final BooleanSetting materialPasses = add(new BooleanSetting("Material passes",
			"Survival only: build one block type at a time, commonest first, and route only "
					+ "through where that block goes. Several types share a pass only when "
					+ "all of what is left of them fits in one bag. Creative is untouched - "
					+ "it can pull any block at will, so it has nothing to gain.", true));
	public final ModeSetting restockMode = add(new ModeSetting("Restock mode",
			"Stash only empties borrowed shulkers at the chest and puts them straight back, so "
					+ "you fly home with a full bag of blocks and no cargo - more trips, but "
					+ "nothing to lose out over the build. Carry boxes brings the shulkers with "
					+ "you and opens them where the work is: far fewer trips, a bag mostly full "
					+ "of boxes.", "Stash only", "Stash only", "Carry boxes"));
	public final NumberSetting stashBoxes = add(new NumberSetting("Stash boxes",
			"Loaded shulkers a supply run brings back. This is the whole speed of an AFK "
					+ "print: one box is 1728 blocks, so a dozen of them is a band per trip "
					+ "instead of ten trips. Leave room for the blocks they unload into.",
			16, 1, 27, 1));
	/**
	 * Sized explicitly, because this is a serialised list rather than a line of prose and the
	 * free-text default of 64 characters is a stash of exactly five chests — with nothing
	 * anywhere saying so. A sixth took "52,164,967;" six times to 65 characters, the setting
	 * quietly kept the first 64, and the digit it dropped off the end still left a coordinate
	 * that parsed: 970 read back as 97, so the opening survey lap flew 877 blocks north to a
	 * chest that had never been there. Room for well over a hundred chests, and
	 * {@link #markStash} refuses the mark rather than clipping it if that is ever reached.
	 */
	public final StringSetting stashList = add(new StringSetting("Stash",
			"Marked stash containers, as x,y,z;x,y,z. Set them with .stash while looking "
					+ "at a chest rather than by hand.", "", 4096));
	public final NumberSetting restockFill = add(new NumberSetting("Restock fill",
			"How many inventory slots a refill may fill. Higher means more of the block you "
					+ "burn through fastest and fewer stops, at the cost of a fuller bag; "
					+ "leave room for your shulkers.", 18, 1, 34, 1));
	public final NumberSetting restockAt = add(new NumberSetting("Restock at",
			"Go and refill once the bag can only see this many more blocks of the planned "
					+ "route through. Higher leaves more in hand and refills sooner; 0 waits "
					+ "until something actually runs out mid-lane.", 128, 0, 1024, 16));
	public final BooleanSetting stopWhenDone = add(new BooleanSetting("Stop when done",
			"Switch the module off once there is nothing left it can place.", true));
	public final BooleanSetting showRoute = add(new BooleanSetting("Show route",
			"Draw the lane the printer plans to fly, so what it intends is visible.", true));
	public final BooleanSetting noFallInFlight = add(new BooleanSetting("No fall damage",
			"Tell the server you are grounded while the printer is flying, so the flight it "
					+ "granted itself is never billed as a fall. On by default because NoFall "
					+ "cannot cover this case: its Packet mode watches your fall distance, and "
					+ "vanilla holds that at zero the whole time you are flying.", true));
	public final BooleanSetting showTrip = add(new BooleanSetting("Show trip",
			"Draw the line the printer flies to and from the stash, so a supply run is "
					+ "something you can watch rather than infer.", true));
	public final ColorSetting tripColor = add(new ColorSetting("Trip color",
			"Colour of the supply-run line", 0xC0FFC24A));
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
	/**
	 * As {@link #bandWork}, but support-blind — every position the band wants, placeable yet
	 * or not.
	 *
	 * <p>The route may only fly to what can be placed <em>now</em>, which is what
	 * {@link #bandWork} is for. The <em>forecast</em> must not use that same set, and using it
	 * was a real bug with a real report behind it: at the start of a band no carpet can
	 * survive yet, because its support is the thing this pass is about to lay. So the routed
	 * part of the forecast came out as 6435 positions of pure cobblestone, every carpet was
	 * pushed into the tail beyond the end of the route, and a refill bought eighteen slots of
	 * cobblestone for a lane that spends carpet the whole way along it. The printer lays the
	 * support and the carpet on top of it in one sweep; the forecast has to describe that
	 * sweep, not the instant before it starts.
	 */
	private final List<BlockPos> bandAll = new ArrayList<>();
	/**
	 * The band's materials in descending demand, <b>fixed when the band was first scanned</b>.
	 *
	 * <p>The freeze is the point. Cobblestone opens a band at six thousand blocks and ranks
	 * first; two bagfuls later it is down to fourteen hundred and by the end of its sweep it
	 * is under a hundred, which ranks it ninth. Re-derive the order at that moment and the
	 * printer abandons a nearly-finished sweep to go and start orange, then has to come back
	 * — the same class of bug as a demand map read while it was still being written. A
	 * material keeps its rank until it is actually finished.
	 */
	private List<Item> ranking = List.of();
	/** Materials of this band already built, or given up on for this round. */
	private final Set<Item> groupDone = new HashSet<>();
	/**
	 * The materials this pass may place. Empty means no restriction — creative, or the
	 * setting off.
	 *
	 * <p>Either one material, or every material still left when all of them fit in a single
	 * bag. Never anything in between, and that is deliberate: those two cases are the ones
	 * where <em>nothing has to be decided</em>. Carry one block type and there is no question
	 * of how much of each to bring; carry the whole remainder and the answer is "all of it".
	 * Any other mix needs an allocator, and the allocator is what has gone wrong all evening.
	 */
	private Set<Item> activeGroup = Set.of();
	/** {@link #bandWork} and {@link #bandAll} narrowed to {@link #activeGroup}. */
	private final List<BlockPos> groupWork = new ArrayList<>();
	private final List<BlockPos> groupAll = new ArrayList<>();
	/** Blocks placed when the current round of groups began, so a stuck round can be told. */
	private int placedAtRoundStart;
	/**
	 * What the whole active band still needs, item → count.
	 *
	 * <p>Counted during the scan from the schematic's full requirement, support-blind: a
	 * carpet with no support yet still counts, because the sweep lays the support then places
	 * it behind itself. This is what to <em>carry</em>; {@link #bandWork} is the narrower
	 * "what can be placed right now" that the route flies.
	 *
	 * <p><b>Published, not accumulated.</b> The scan takes many ticks, and a restock that
	 * fired part-way through used to read a half-built map — sometimes only the first few X
	 * columns of the band — and go and fetch a shopping list that was a fraction of the truth.
	 * Which fraction depended on tick timing, so it looked random. The scan now fills
	 * {@link #scanDemand} and this only ever holds a finished count.
	 */
	private Map<Item, Integer> bandDemand = Map.of();
	/** The scan's working total, swapped into {@link #bandDemand} when it completes. */
	private final Map<Item, Integer> scanDemand = new HashMap<>();
	/**
	 * What the band contains <em>in the schematic</em>, built or not.
	 *
	 * <p>Read straight off Litematica's schematic world — which is the {@code .litematic}
	 * file, loaded whole, with no render distance and no dependence on what the server has
	 * sent. So it is the one number here that cannot come out wrong, and {@link #bandDemand}
	 * (which is this minus what is already built, and therefore does depend on the world) can
	 * be checked against it: the remainder can never exceed the total.
	 */
	private Map<Item, Integer> bandTotal = Map.of();
	private final Map<Item, Integer> scanTotal = new HashMap<>();
	/**
	 * What each material stands on: the block the schematic wants beneath one that cannot be
	 * placed yet. Read off the schematic during the scan — see {@link #noteSupport}.
	 */
	private Map<Item, Set<Item>> supportOf = Map.of();
	private final Map<Item, Set<Item>> scanSupport = new HashMap<>();
	/** Whether a material needs anything under it at all — see {@link #needsSupport}. */
	private final Map<Item, Boolean> supportNeeded = new HashMap<>();
	/** The exact band count and the placement tally it was taken at — see {@link #exactBandNeed}. */
	private Map<Item, Integer> exactNeed;
	private int exactNeedStamp = -1;
	private int exactNeedAt = -1;
	/** Ticks the exact count may be reused for even as blocks go down — a CPU floor only. */
	private static final int EXACT_MIN_TICKS = 10;
	/**
	 * Largest band the exact count will walk in one tick.
	 *
	 * <p>Two layers of a 128-wide map art is 32k cells; the background tally does 20k a tick
	 * as a matter of course, so this is comfortably inside one frame's worth of work and it
	 * happens once a trip. The cap is for the case where Auto layers is off and the whole
	 * schematic is one band, where the same walk would be half a million cells.
	 */
	private static final long EXACT_CELL_CAP = 150_000;
	/**
	 * Columns of the band the client had no chunk for when the scan walked them, and the
	 * blocks counted in them.
	 *
	 * <p>Not a statistic — a health check on the number everything else is derived from.
	 * {@code getBlockState} answers air in a chunk the client has not got, so a column out
	 * past render distance reports <em>every</em> block the schematic wants there as still
	 * missing, including the ones already built. The band demand is therefore only as
	 * trustworthy as the part of the region that was loaded when it was counted, and it
	 * changes with where the player happened to be standing at the time.
	 */
	private int unseenColumns;
	private int unseenBlocks;
	/** Materials this band has no source for, so they are skipped and said once, not per block. */
	private final Set<Item> noSource = new HashSet<>();
	/** Set on enable; the stash lap runs on the first tick that has a player to fly. */
	private boolean surveyPending;
	/** Set when a pass starts: swap the previous pass's leftovers for this pass's materials. */
	private boolean clearOutDue;
	/** A support pass that made no progress waits for stock; it never releases dependants. */
	private boolean waitingForPassSupply;
	/** Health and fall distance last tick, so a drop can be attributed — see watchDamage. */
	private float lastHealth = -1.0f;
	private double lastFall;
	/** Whether the lane is parked waiting on a refill, and whether that has been said already. */
	private boolean holdLane;
	private boolean heldLane;
	private int holdTicks;
	/**
	 * Longest the lane will wait on a refill before flying on regardless.
	 *
	 * <p>Ten seconds, not thirty. This only has to cover the gap between deciding to go and
	 * the trip actually setting out — a cooldown of a hundred ticks, plus whatever tick the
	 * decision lands on. Anything longer and the printer is not waiting for a run, it is
	 * waiting for something that is not going to happen, and it should be building instead.
	 */
	private static final int HOLD_MAX = 200;
	private int scanUnseenColumns;
	private int scanUnseenBlocks;
	/**
	 * What the band <em>above</em> this one will need — the lookahead, same publish rule.
	 *
	 * <p>Without it a refill near the end of a band tops up for work that is about to finish,
	 * the band advances, and on a staircased map art every colour changes at once: dry again
	 * one band later, having just been to the base. Counted with the layer range ignored on
	 * purpose, since Auto layers has clamped Litematica's view to the band being built and
	 * would otherwise answer "there is nothing up there".
	 */
	private Map<Item, Integer> aheadDemand = Map.of();
	private final Map<Item, Integer> scanAhead = new HashMap<>();
	/**
	 * What the printer is about to spend, in the order the route spends it.
	 *
	 * <p>The band's demand says how much; this says <em>when</em>, which is the part a
	 * refill actually needs. Rebuilt with the lane, so the two always describe the same pass.
	 */
	private MaterialForecast forecast = MaterialForecast.NONE;
	/** {@link #forecastAhead}'s trimmed view, and the waypoint it was trimmed at. */
	private MaterialForecast forecastCache = MaterialForecast.NONE;
	private int forecastCacheAt = -1;
	/** Blocks of route the bag can still see through, and what ends it. Refreshed periodically. */
	private int coverageLeft = Integer.MAX_VALUE;
	private Item coverageEndsOn;
	private int coverageAge;
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
	/** True while we are waiting on a container open the restock asked for. */
	private boolean restockOpen;
	/** Carried-shulker refills; owns its own place/open/pull/close/break cycle. */
	private final ShulkerRestock restock = new ShulkerRestock();
	/**
	 * Supply runs to marked chests, which is where the boxes {@link #restock} unloads come from.
	 *
	 * <p>The two are a hierarchy, not alternatives: a shortage a carried box can fix costs a
	 * few seconds on the spot, and only a shortage no carried box can fix is worth flying for.
	 */
	private final ChestStash stash = new ChestStash();
	/** The stash string the marked list was last synced from, in either direction. */
	private String stashStamp;
	/** Whether the coming shortage is one a carried box can fix — see {@link #restockDue}. */
	private boolean bottleneckInBag;
	/** Set when a placement was refused for want of the item, cleared each tick. */
	private boolean outOfItem;
	/** The last angle aimed at, re-asserted each tick so the visible pose does not flicker. */
	private float aimYaw;
	private float aimPitch;
	private long aimAtMs;
	/** How long the printer keeps looking at its last target after the last aim. */
	private static final long AIM_HOLD_MS = 1000L;
	/** Set by {@code .pause}: everything stops, nothing is forgotten. */
	private boolean paused;

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
	/** Air kept under the feet along a lane, so the printer never counts as grounded. */
	private static final double LANE_CLEARANCE = 0.25;
	/** The eye above the feet, for working out horizontal reach from lane height. */
	private static final double EYE_HEIGHT = 1.62;
	/** Upcoming waypoints drawn by Show route. */
	private static final int ROUTE_PREVIEW = 16;
	/** Ceiling on the closing verification scan, so a huge placement can't stall a tick. */
	private static final long VERIFY_CELL_CAP = 2_000_000L;
	/** Ticks between re-measuring how far the bag stretches — see {@link #restockDue}. */
	private static final int COVERAGE_EVERY = 20;
	/**
	 * Slots an unload may fill while standing at the stash.
	 *
	 * <p>Deliberately not {@code Restock fill}, which is sized for opening a box out at the
	 * work with cargo still aboard. At the chest the boxes are going straight back, so the
	 * only thing worth leaving room for is the handful of slots the cycle itself needs.
	 *
	 * <p>The same number decides how big a material group may be, and it has to be the same
	 * number: a group is defined as what one fill can carry, so planning against a bag the
	 * refill cannot actually fill would promise sweeps that run dry half way.
	 *
	 * <p>Thirty-two of thirty-six, and the four held back are the point rather than a rounding
	 * error. One is the pickaxe the refill breaks its boxes with — a slot well spent, since
	 * bare-handed a shulker takes seven and a half seconds and can outlast the break timeout
	 * outright. The rest cover food, the box being worked, and somewhere for a drop to land.
	 */
	private static final int BAG_SLOTS = 32;

	public final BooleanSetting pauseOnEat = addPauseOnEat();

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
		// Deferred to the first real tick rather than done here: this also runs at startup,
		// when config load re-enables last session's modules and there is no player yet.
		surveyPending = true;

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
		restock.reset();
		stash.reset();
		restockOpen = false;
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
		bandAll.clear();
		groupWork.clear();
		groupAll.clear();
		ranking = List.of();
		groupDone.clear();
		activeGroup = Set.of();
		forecastCache = MaterialForecast.NONE;
		forecastCacheAt = -1;
		bandDemand = Map.of();
		aheadDemand = Map.of();
		scanDemand.clear();
		scanAhead.clear();
		unseenColumns = 0;
		unseenBlocks = 0;
		scanUnseenColumns = 0;
		scanUnseenBlocks = 0;
		bandTotal = Map.of();
		scanTotal.clear();
		supportOf = Map.of();
		scanSupport.clear();
		exactNeed = null;
		exactNeedStamp = -1;
		noSource.clear();
		forecast = MaterialForecast.NONE;
		coverageLeft = Integer.MAX_VALUE;
		coverageEndsOn = null;
		coverageAge = 0;
		holdLane = false;
		heldLane = false;
		holdTicks = 0;
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
		if (paused) {
			restoreSlot();
			return; // the clock and every counter stop here too: a pause is not work
		}
		// Yield the hand for the meal. Deliberately does *not* touch flight: whatever state
		// the printer is in holds by itself — flying costs no gravity, and a refill that has
		// landed to mine is on the ground already. Asserting flight here would lift a landed
		// player off a shulker mid-break and throw away the progress.
		if (AutoEat.pauses(pauseOnEat)) {
			// AutoEat announces itself two ticks before it changes hands. Close a menu we own in
			// that window: leaving a chest or shulker open stops the meal, while resuming after
			// the meal simply makes the refill re-open its own container.
			restock.yieldToAutoEat();
			stash.yieldToAutoEat();
			restoreSlot();
			return;
		}
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
		watchDamage();
		holdAim();

		// Refill before working, not after failing: a cycle takes a second or two, and the
		// printer flying its lane meanwhile would place the box somewhere it has already
		// left. Demand is only read when a cycle starts, so the walk over the band costs
		// nothing on the ticks in between.
		// Three reasons to refill, in order of how much they cost. A stranded box outranks
		// everything — it is your materials sitting in the world. The forecast is the planned
		// stop, taken with a margin still in the bag. outOfItem is the backstop for anything
		// the forecast did not see coming, and reaching it means the prediction was wrong.
		syncStash();
		// Drawn here rather than beside the lane overlay, which lives in navigate() — and
		// navigate is the one thing a supply run skips, so the trip line would have been
		// invisible for exactly the trip it exists to show.
		if (showTrip.get()) {
			renderTrip();
		}
		// Cleared every tick: only the refill branch below may set it, so the lane is never
		// held by a stale decision from a tick that has been overtaken.
		holdLane = false;
		// One lap of the stash before the first shortage, so every "can this be got" answer
		// below is a fact rather than the optimistic guess an unvisited chest gets.
		if (surveyPending) {
			surveyPending = false;
			if (shulkerRestock.get() && stash.configured() && !movement.is("Off")) {
				// The opening lap also empties the bag: a print that starts with whatever you
				// happened to be carrying starts with fewer slots than it thinks it has, and
				// the forecast is written in slots.
				stash.beginSurvey(true);
			}
		}
		// Only once there is a plan: dumping before the scan has run would go with an empty
		// idea of what the new band wants and put back material it is about to ask for.
		boolean clearOut = clearOutDue && !forecast.isEmpty();
		if (clearOut) {
			clearOutDue = false;
			stash.requestClearOut();
			// A completed material pass is a mandatory inventory boundary. A low-yield trip
			// from the old pass must not make the new pass fly with an empty bag.
			stash.forceNextTrip();
		}
		if (shulkerRestock.get() && (restock.busy() || stash.busy() || restock.hasStrandedBox()
				|| outOfItem || clearOut || restockDue())) {
			// The *capability* only. Asserting flight itself here would fight the refill for
			// it every tick — the printer setting flying true, settleAt cutting it to stand
			// and mine, an abilities packet each way, twenty times a second, and a player who
			// never quite lands. Ownership of `flying` belongs to whichever refill is running;
			// keeping mayfly true underneath means a landed player who slips can always fly
			// back on.
			if (!movement.is("Off")) {
				allowFlight();
			}
			boolean stashOnly = restockMode.is("Stash only");
			restock.setPreferredBase(parseBase());
			// Standing at the chest the bag should be filled, not topped up: the trip's whole
			// purpose is to leave with as much as it can hold, and Restock fill is sized for
			// an unload out at the work where room has to be left for the boxes themselves.
			restock.setFill(stashOnly && stash.busy() ? BAG_SLOTS : restockFill.getInt());
			// Leave the unload somewhere to put what it takes out, but never so much that a
			// trip has no room for boxes: reserve and cargo have to add up to an inventory.
			// Stash only borrows and returns within a round, so the only headroom it needs is a
			// couple of slots for the cycle itself; Carry boxes has to leave room for an unload
			// out at the work with the cargo still aboard.
			stash.setLimits(stashBoxes.getInt(),
					stashOnly ? 2 : Math.min(restockFill.getInt() + 2, 24));
			// Everything this band and the next still want, so a trip shopping for one pass
			// never puts back what a later pass needs — and what the last trip fetched.
			Map<Item, Integer> exact = exactBandNeed();
			// What is still missing, not what the band contains: a colour this band has
			// finished is cargo, and holding on to it costs the slots the next fill wants.
			Set<Item> keep = new HashSet<>(exact != null ? exact.keySet() : bandTotal.keySet());
			keep.addAll(aheadDemand.keySet());
			stash.setKeep(keep);
			restockOpen = restock.expectingOpen() || stash.expectingOpen();
			// Drained to empty, not one line each: both keep a queue now, and stopping after
			// the first would just move the loss from the helper to here.
			for (int i = 0; i < 8; i++) {
				String line = restock.takeEvent();
				if (line.isEmpty()) {
					line = stash.takeEvent();
				}
				if (line.isEmpty()) {
					break;
				}
				note(line);
				ChatUtil.info("Printer: " + line);
			}
			// A supply run already under way finishes before anything else looks at the bag,
			// since half of it is spent with the inventory deliberately mid-shuffle.
			boolean wasBusy = restock.busy() || stash.busy();
			boolean drove;
			if (stash.busy()) {
				drove = stash.tick(forecastForTrip(), restock, this::schematicWants, stashOnly);
			} else if (restock.busy() || restock.hasStrandedBox()
					// In Stash only nothing is carried to open in the field, so a box in the
					// bag is one the last trip could not give back — worth emptying, but never
					// a reason to prefer the on-site path over going to the chest.
					|| (bottleneckInBag && !stashOnly)) {
				// the build is off limits for a base: a box standing in the schematic is a
				// block the printer would try to place through, and breaking it later takes
				// the build with it
				drove = restock.tick(restock.busy() ? MaterialForecast.NONE : forecastAhead(),
						this::schematicWants);
			} else {
				// Nothing in the bag can fix this one, so it is worth the flight — but only
				// if flying is ours to do. With Movement off the printer stays where it is
				// put, and a supply run would grant itself flight the server never agreed to.
				drove = stash.configured() && !movement.is("Off")
						&& stash.tick(forecastForTrip(), restock, this::schematicWants, stashOnly);
			}
			if (drove) {
				outOfItem = false;
				holdTicks = 0; // the run it was waiting for is under way
				heldLane = false;
				return;
			}
			// A refill is warranted but could not set out yet — a trip cooldown, or no chest
			// reachable this second. Hold the lane rather than fly on.
			//
			// Flying on is not merely idle: laneIndex only moves forward and forecastAhead is
			// forecast.from(laneIndex), so every waypoint crossed while empty *deletes that
			// work from the shopping list*. The printer skips the blocks it has no material
			// for, forgets it wanted them, comes back from the stash provisioned for what is
			// left, and rediscovers the skipped ones as a small remainder on a later pass —
			// which is a supply run of its own. Standing still costs a few seconds; flying on
			// costs the trip that would have covered them.
			// Measured off coverage, not off outOfItem. They differ in the case that matters:
			// coverage is computed over the materials a refill could actually get, so a
			// colour the stash has none of does not drive it to zero. Holding on outOfItem
			// would therefore park the printer forever in front of a block nothing can supply
			// — which is the exact state a stash with no cobblestone puts it in.
			// Two conditions, and the second is the one that was missing. Waiting is only
			// right when the trip is actually coming: a stash that just came back empty sits
			// on a minute's cooldown, and holding through that is a minute of standing in a
			// field. Worth waiting through the short cooldown after an ordinary trip; not
			// worth waiting through the long one that means the stash had nothing.
			// Same question the trigger asks, so the two cannot disagree about whether the
			// route is unsupplied — a material pass no longer measures coverage at all.
			boolean unsupplied = byMaterial() && !activeGroup.isEmpty()
					? passIsDry()
					: coverageLeft <= 0;
			boolean worthWaiting = unsupplied
					&& (stash.busy() || stash.readySoon() || restock.busy());
			if (!worthWaiting) {
				holdTicks = 0; // a fresh wait later gets its full budget
			} else {
				holdTicks++;
			}
			holdLane = worthWaiting && holdTicks <= HOLD_MAX;
			if (holdLane && !heldLane) {
				note("holding position - the route is unsupplied and a refill is due");
			} else if (!holdLane && heldLane) {
				note(worthWaiting
						? "waited " + (HOLD_MAX / 20) + "s for a refill that did not start - flying on"
						: "no refill coming just now - carrying on with what can be built");
			}
			heldLane = holdLane;
			if (wasBusy) {
				// A cycle just ended, so the bag is not what it was measured as. Only then:
				// a trigger that keeps firing because nothing can supply what is short would
				// otherwise re-measure every single tick, which is the state a print sits in
				// once both the boxes and the stash are empty.
				coverageAge = 0;
			}
			restockOpen = false;
		}
		if (!holdLane) {
			heldLane = false; // free to fly again; the next hold is a fresh episode
		}
		outOfItem = false;

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
		//
		// Placing still runs above while held: anything already in reach is material we have
		// and work that has to happen anyway. It is only the *advancing* that has to stop.
		if (!movement.is("Off") && !finished && !holdLane) {
			navigate(now);
		} else if (holdLane && mc().player != null) {
			hoverInPlace();
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
		return wantsBlock(pos, true, true, true);
	}

	/**
	 * As {@link #wantsBlock(BlockPos)}; the tally passes {@code honourLayerRange} false
	 * because Auto layers clamps Litematica's view to the band being built, and totals
	 * clamped to one band would tell the HUD the rest of the schematic does not exist.
	 */
	private boolean wantsBlock(BlockPos pos, boolean honourLayerRange) {
		return wantsBlock(pos, honourLayerRange, true, false);
	}

	/**
	 * As {@link #wantsBlock(BlockPos, boolean)}, but {@code requireSurvivable} false also
	 * counts a block the schematic wants where it cannot be placed <em>yet</em> — a carpet
	 * with no support under it is still carpet the restock has to carry, since the sweep lays
	 * the support and places the carpet behind it. Placement always demands survivability;
	 * only the material count relaxes it, so the route is unchanged.
	 */
	private boolean wantsBlock(BlockPos pos, boolean honourLayerRange, boolean requireSurvivable,
			boolean honourGroup) {
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
		Item item = required.getBlock().asItem();
		if (item == Items.AIR) {
			return false; // nothing a player could hold places this
		}
		if (dirtAsGrass.get() && item == Items.GRASS_BLOCK) {
			item = Items.DIRT;
		}
		// Not this pass's material. Filtered here rather than at the router, because a
		// position the placer would still try is one it reports being out of — and a carpet
		// in reach during the cobblestone sweep would set outOfItem on every tick and send
		// the printer to the stash for something it deliberately is not carrying.
		if (honourGroup && !activeGroup.isEmpty() && !activeGroup.contains(item)) {
			return false;
		}
		// count-only callers pass requireSurvivable=false: a block with no support yet is
		// still material to carry, though the route can only reach it once its support exists
		return !requireSurvivable || required.canSurvive(mc().level, pos);
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
		// A new band is a new ranking: its material mix is its own, and carrying the last
		// band's order into it is exactly the staircase case where every colour changes.
		ranking = List.of();
		groupDone.clear();
		activeGroup = Set.of();
		waitingForPassSupply = false;
		passesThisBand = 0;
		placedThisPass = false;
		planCursor = Integer.MIN_VALUE;
		phase = Phase.PLAN;
		exactNeed = null; // a new band is a different question entirely
		// Start the band clear. The last band's leftovers are dead weight in a bag that is
		// about to be filled with a different mix, and the trip that dumps them is the same
		// trip that fetches the new band's first load — so this costs a flight the print was
		// going to make anyway, taken at the useful moment instead of when it first runs dry.
		clearOutDue = true;
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
			bandAll.clear();
			scanDemand.clear();
			scanAhead.clear();
			scanTotal.clear();
			scanSupport.clear();
			scanUnseenColumns = 0;
			scanUnseenBlocks = 0;
			planCursor = region.min().getX();
		}
		int aheadTop = Math.min(bandMaxY + bandHeight.getInt(), region.max().getY());
		int cells = 0;
		BlockPos.MutableBlockPos walk = new BlockPos.MutableBlockPos();
		while (planCursor <= region.max().getX() && cells < PLAN_CELLS_PER_TICK) {
			// Z outer, so the chunk a column sits in is tested once instead of per block.
			for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
				boolean seen = loaded(planCursor, z);
				for (int y = bandMinY; y <= bandMaxY; y++) {
					cells++;
					walk.set(planCursor, y, z);
					// The schematic's own answer, taken straight off Litematica's world (which
					// is the .litematic file, not the server's chunks) and never compared with
					// anything. This is the one count in the module that cannot be wrong: it
					// does not care what is built, loaded, reachable or in the bag. Everything
					// else is checked against it.
					tallyRequired(walk, scanTotal);
					// Count the material need whether or not it can go down yet — carpet
					// waiting on its support is still carpet the restock must carry — but only
					// route to what can be placed now, which is what canSurvive gates.
					if (wantsBlock(walk, true, false, false)) {
						if (!seen) {
							// The world here is not air, it is *unknown*; wantsBlock only said
							// yes because an absent chunk reads as air. Counted apart so the
							// demand stays honest and the gap is visible.
							scanUnseenBlocks++;
							continue;
						}
						tallyRequired(walk, scanDemand);
						BlockPos here = walk.immutable();
						bandAll.add(here); // what the sweep will spend, support or not
						BlockState required = LitematicaBridge.required(walk);
						if (required.canSurvive(mc().level, walk)) {
							bandWork.add(here); // and the subset the route can reach today
						}
						// Asked of every position, placeable or not. Deriving this only from
						// the ones that currently fail made it a fact about the world instead
						// of about the build, and a material all of whose blocks happen to
						// have their floor already recorded no dependency at all — which is
						// exactly how six yellow carpet ended up in a cobblestone-only pass.
						noteSupport(here, required);
					}
				}
				if (!seen) {
					scanUnseenColumns++;
					continue;
				}
				// The band above, counted in the same walk so the lookahead costs one pass
				// rather than a second scan. Layer range ignored: Auto layers has the view
				// clamped to the band being built, and asking about the band above answers no.
				for (int y = bandMaxY + 1; y <= aheadTop; y++) {
					cells++;
					walk.set(planCursor, y, z);
					if (wantsBlock(walk, false, false, false)) {
						tallyRequired(walk, scanAhead);
					}
				}
			}
			planCursor++;
		}
		if (planCursor <= region.max().getX()) {
			return; // scan continues next tick
		}
		planCursor = Integer.MIN_VALUE;
		bandDemand = Map.copyOf(scanDemand); // only now is the count a whole one
		aheadDemand = Map.copyOf(scanAhead);
		unseenColumns = scanUnseenColumns;
		unseenBlocks = scanUnseenBlocks;
		bandTotal = Map.copyOf(scanTotal);
		supportOf = Map.copyOf(scanSupport);
		crossCheck();

		note(String.format(
				"scan band %d..%d: %d placeable of %d wanted (pass %d, placed since last=%b)",
				bandMinY, bandMaxY, bandWork.size(), bandAll.size(), passesThisBand,
				placedThisPass));
		if (unseenColumns > 0) {
			// Said out loud, because it means the plan below is a plan for part of the band.
			// The printer will pick the rest up on a later pass, once flying the part it can
			// see has pulled those chunks in.
			note("scan could not see " + unseenColumns + " column(s) of the band ("
					+ unseenBlocks + " blocks) - out of render distance, counted on a later pass");
		}
		if (byMaterial()) {
			if (!advanceGroups(now)) {
				return; // the band is finished, or a fresh round starts on the next scan
			}
		} else {
			// Creative and the setting off keep the original plan exactly: one route over
			// everything the band still wants, no material order at all. Clearing the group is
			// part of that: left set, it would go on filtering placements after a gamemode
			// change and quietly hide most of the schematic.
			activeGroup = Set.of();
			ranking = List.of();
			groupDone.clear();
			groupWork.clear();
			groupWork.addAll(bandWork);
			groupAll.clear();
			groupAll.addAll(bandAll);
			if (bandWork.isEmpty() || (passesThisBand > 0 && !placedThisPass)) {
				if (!bandWork.isEmpty()) {
					note("giving up on band " + bandMinY + ".." + bandMaxY + " with "
							+ bandWork.size() + " left (no progress last pass)");
				}
				advanceBand(now);
				return;
			}
		}
		buildLane();
		buildForecast();
		note("pass " + (passesThisBand + 1) + ": " + lane.size() + " waypoints, pitch "
				+ lanePitch() + ", forecast " + forecast.size() + " blocks in "
				+ forecast.runs().size() + " runs");
		placedThisPass = false;
		passesThisBand++;
		laneIndex = 0;
		detour = List.of();
		detoured = false;
		resetDriveProgress();
		phase = Phase.DRIVE;
	}

	/** Whether the survival material-at-a-time plan is in force. */
	private boolean byMaterial() {
		return materialPasses.get() && mc().player != null && !mc().player.isCreative();
	}

	/**
	 * Retires a finished group, picks the next, and narrows the work lists to it.
	 *
	 * @return true when a group with work is ready to fly; false when the band is done or a
	 *         new round of groups has been opened and the next scan should decide
	 */
	private boolean advanceGroups(long now) {
		if (ranking.isEmpty()) {
			rankBand();
		}
		if (!activeGroup.isEmpty()) {
			if (waitingForPassSupply) {
				if (!hasLooseActiveSupply()) {
					return false; // preserve the support barrier until its material actually arrives
				}
				waitingForPassSupply = false;
				passesThisBand = 0;
			}
			narrowToGroup();
			boolean stuck = passesThisBand > 0 && !placedThisPass;
			if (groupWork.isEmpty()) {
					note(names(activeGroup) + " done for band " + bandMinY + ".." + bandMaxY);
				groupDone.addAll(activeGroup);
				activeGroup = Set.of();
			} else if (stuck) {
				// A support pass with no stock is not done. Marking it done is exactly what lets
				// carpets try to place on air: waitingOnSupport only knows whether the floor's
				// pass was retired. Keep the barrier, let the refill branch retry, and do not
				// route any dependent material until loose stock really arrives.
				waitingForPassSupply = true;
				note("waiting for supply of " + names(activeGroup) + " with " + groupWork.size()
						+ " left; dependent blocks remain blocked");
				return false;
			}
		}
		boolean selectedPass = false;
		while (activeGroup.isEmpty()) {
			chooseGroup();
			if (activeGroup.isEmpty()) {
				// Every material has had its turn. Anything still standing was blocked rather
				// than impossible — carpet whose support this round has only just laid, most
				// often — so if the round achieved anything at all, go round again with the
				// same ranking. A round that placed nothing is the end of the band.
				if (totalPlaced > placedAtRoundStart && !bandWork.isEmpty()) {
					note("round done, " + bandWork.size() + " left in band " + bandMinY + ".."
							+ bandMaxY + " - going round again");
					groupDone.clear();
					placedAtRoundStart = totalPlaced;
					continue;
				}
				advanceBand(now);
				return false;
			}
			passesThisBand = 0;
			placedThisPass = false;
			narrowToGroup();
			if (groupWork.isEmpty()) {
				// Wanted, but nothing placeable yet: its support is another group's job.
				// Set aside for this round rather than written off.
				note(names(activeGroup) + " not placeable yet, deferring");
				groupDone.addAll(activeGroup);
				activeGroup = Set.of();
			} else if (unobtainable()) {
				// Nothing in the bag, no carried box and nothing in the stash. Flying the
				// route anyway is a whole sweep spent reporting "out of orange_carpet" once a
				// second — which is exactly what a real run did for a minute. Say it once,
				// plainly, and move to something that can actually be built.
				String said = "no source for " + names(activeGroup)
						+ " - check your stash, or carry them yourself";
				note(said);
				ChatUtil.info("Printer: " + said);
				groupDone.addAll(activeGroup);
				activeGroup = Set.of();
			} else {
				selectedPass = true;
			}
		}
		if (selectedPass) {
			// Do not enter a newly chosen pass on leftovers from its predecessor. The next tick
			// returns non-pass blocks to the chest, then loads only this frozen pass before routing.
			clearOutDue = true;
		}
		note("building " + names(activeGroup) + " (" + groupWork.size() + " blocks)");
		return true;
	}

	/** Whether this pass has at least one loose block with which it can resume safely. */
	private boolean hasLooseActiveSupply() {
		for (Item item : activeGroup) {
			if (carriedCount(item) > 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Fixes the band's material order: what stands on nothing first, then commonest.
	 *
	 * <p>Count alone is the obvious rule and it is <em>almost</em> right. It happens to work on
	 * a map art because the floor is also the bulk of it — cobblestone is both the support and
	 * the commonest thing in the band, so "commonest first" and "floor first" agree. They stop
	 * agreeing the moment a build has a rare support under a common block: a hundred torches on
	 * a dozen fence posts ranks the torches first, and the pass discovers there is nowhere to
	 * put any of them.
	 *
	 * <p>So depth comes first and count breaks ties within a depth. Depth is how many things
	 * have to exist underneath a material before it can go down at all, read off the schematic
	 * by {@link #noteSupport}. It costs nothing on a map art, where everything is depth 0 or 1,
	 * and it is simply correct everywhere else.
	 */
	private void rankBand() {
		Map<Item, Integer> depth = new HashMap<>();
		List<Item> order = new ArrayList<>(bandDemand.keySet());
		for (Item item : order) {
			supportDepth(item, depth, new HashSet<>());
		}
		order.sort(Comparator.<Item>comparingInt(item -> depth.getOrDefault(item, 0))
				.thenComparing((a, b) -> Integer.compare(bandDemand.getOrDefault(b, 0),
						bandDemand.getOrDefault(a, 0))));
		ranking = List.copyOf(order);
		groupDone.clear();
		// A fresh band re-asks the question: the stash may have been refilled since, and a
		// colour written off for the last band deserves another look rather than a life sentence.
		noSource.clear();
		placedAtRoundStart = totalPlaced;
		note("band " + bandMinY + ".." + bandMaxY + " order: " + names(ranking));
	}

	/**
	 * How many materials have to be laid underneath this one before it can go down.
	 *
	 * <p>{@code guard} carries the chain being resolved, so a schematic where two materials
	 * end up supporting each other in different places answers zero instead of recursing
	 * until the stack gives out. A cycle has no correct depth; refusing to invent one and
	 * letting count decide is the honest answer.
	 */
	private int supportDepth(Item item, Map<Item, Integer> depth, Set<Item> guard) {
		Integer known = depth.get(item);
		if (known != null) {
			return known;
		}
		if (!guard.add(item)) {
			return 0;
		}
		int deepest = 0;
		for (Item support : supportOf.getOrDefault(item, Set.of())) {
			deepest = Math.max(deepest, supportDepth(support, depth, guard) + 1);
		}
		guard.remove(item);
		depth.put(item, deepest);
		return deepest;
	}

	/**
	 * Picks the next group off the frozen ranking.
	 *
	 * <p>A group is deliberately all-or-one. If <em>every</em> remaining material eligible to
	 * build now fits in a bag, this is the final exact-load pass. Otherwise it is only the
	 * highest-ranked eligible material and every refill contains that one material. Taking the
	 * first few colours that happen to fit creates a half-full bag of the wrong materials and
	 * breaks both the ranking and the no-extra-trip promise.
	 */
	private void chooseGroup() {
		List<Item> eligible = new ArrayList<>();
		int allSlots = 0;
		for (Item item : ranking) {
			if (groupDone.contains(item)) {
				continue;
			}
			int need = bandDemand.getOrDefault(item, 0);
			if (need <= 0) {
				groupDone.add(item);
				continue;
			}
			// Nothing carried, no box, nothing in the stash: leave it out of the group rather
			// than route over it. The whole-group check further down only fires when *every*
			// material is unobtainable, which a fourteen-material group never is — so one
			// missing colour used to come along for the ride and be reported out-of at every
			// position it owned. A real run logged "out of cobblestone" 534 times in eight
			// seconds doing exactly this, having fetched everything else it needed.
			// Its floor is not down yet. Not skipped, just not this pass — it comes back the
			// moment the material it stands on is finished, which is the order the schematic
			// itself dictates.
			if (waitingOnSupport(item)) {
				continue;
			}
			if (!hasSource(item)) {
				if (noSource.add(item)) {
					String said = "no source for " + name(item) + " (" + need
							+ " in this band) - skipping it, the rest of the band still builds";
					note(said);
					ChatUtil.info("Printer: " + said);
				}
				groupDone.add(item);
				continue;
			}
			eligible.add(item);
			int stack = Math.max(1, item.getDefaultInstance().getMaxStackSize());
			allSlots += (need + stack - 1) / stack;
		}
		Set<Item> group = new LinkedHashSet<>();
		if (!eligible.isEmpty()) {
			if (allSlots <= BAG_SLOTS) {
				group.addAll(eligible); // final exact-load pass
			} else {
				group.add(eligible.getFirst()); // one material across however many refills it needs
			}
		}
		// Kept in ranking order rather than Set.copyOf'd. An immutable set hashes its members
		// into whatever order it likes, so the group printed to chat and the report came out
		// scrambled — "orange, brown, light_blue, light_gray..." for a group that had in fact
		// been chosen strictly commonest-first. The choice was right and unreadable, which is
		// indistinguishable from wrong when you are trying to tell whether it is behaving.
		activeGroup = java.util.Collections.unmodifiableSet(group);
	}

	/**
	 * Whether not one of the active group's materials could be got hold of from anywhere.
	 *
	 * <p>Any single source counts — some already in the bag, a carried shulker, or a stash
	 * chest that has been seen to hold it. Deliberately generous: with no stash marked at all
	 * the printer must still build from whatever the player is carrying, so this only fires
	 * when every avenue is genuinely closed.
	 */
	private boolean unobtainable() {
		for (Item item : activeGroup) {
			if (hasSource(item)) {
				return false;
			}
		}
		return true;
	}

	/** Whether this material can be got at all: in the bag, in a carried box, or in the stash. */
	private boolean hasSource(Item item) {
		return carriedCount(item) > 0 || restock.canSupply(item)
				|| (stash.configured() && stash.mightSupply(item));
	}

	/** Narrows the scan's results to the active group — the "route only where it goes" part. */
	private void narrowToGroup() {
		groupWork.clear();
		groupAll.clear();
		for (BlockPos pos : bandWork) {
			Item item = itemFor(pos);
			// null-checked because activeGroup is an immutable Set, and those throw on a null
			// lookup rather than answering false — a crashed tick for a block that cannot be
			// placed anyway would be a poor trade.
			if (item != null && activeGroup.contains(item)) {
				groupWork.add(pos);
			}
		}
		for (BlockPos pos : bandAll) {
			Item item = itemFor(pos);
			if (item != null && activeGroup.contains(item)) {
				groupAll.add(pos);
			}
		}
	}

	/** Item names without namespaces, for a log line that has to stay readable. */
	private static String names(java.util.Collection<Item> items) {
		StringBuilder text = new StringBuilder();
		for (Item item : items) {
			text.append(text.length() == 0 ? "" : ", ").append(name(item));
		}
		return text.toString();
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
		activeGroup = Set.of(); // the closing count is of everything left, not of one pass
		if (autoLayers.get() && region != null) {
			// widen back out before counting, or the tally only sees the last band
			LitematicaBridge.setLayerBand(region.min().getY(), region.max().getY());
		}
		int left = countRemaining(now);
		note("finished: " + left + " left");
		if (unseenColumns > 0) {
			// "Done" would be a lie: part of the band was never in the client's world, so it
			// was never compared against the schematic. Say which way the number is wrong.
			String said = "note: " + unseenColumns + " column(s) were never loaded - raise render "
					+ "distance or this schematic is wider than the client can see at once";
			note(said);
			ChatUtil.info("Printer: " + said);
		}
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
		for (BlockPos pos : groupWork) {
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
		List<BlockPos> sorted = new ArrayList<>(groupWork);
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

	/**
	 * Turns the pass into a consumption forecast: what gets laid, in the order it gets laid.
	 *
	 * <p>Three sections, and the order between them is the whole point.
	 * <ol>
	 * <li><b>The route itself.</b> Every position in {@link #bandWork} is attributed to the
	 *     lane segment that will reach it and sorted by how far along that segment it sits,
	 *     so the run list really is the sequence the printer will spend in. This is what lets
	 *     a refill answer "which block runs out first" instead of "which block is there most
	 *     of", and those are different materials remarkably often.
	 * <li><b>The band's remainder.</b> Blocks the band needs that could not be routed to yet —
	 *     carpet whose support is still missing. They are certainly going to be laid this
	 *     band, just not at a place the lane knows about, so they go on the end rather than
	 *     into the sequence.
	 * <li><b>The band above.</b> The lookahead, last, so it is only provisioned for once this
	 *     band is covered and can never crowd out work the printer is flying toward now.
	 * </ol>
	 *
	 * <p>Lane waypoints come in pairs — segment <i>i</i> is {@code lane[2i]} to
	 * {@code lane[2i+1]} — and a run is tagged with the segment's <em>end</em> waypoint, so
	 * {@link MaterialForecast#from} only discards work once the printer is provably past it.
	 * Tagging with the start would drop a segment's work the moment the printer began flying
	 * it, which is exactly when it still needs the materials.
	 */
	private void buildForecast() {
		if (lane.isEmpty() || groupWork.isEmpty()) {
			forecast = MaterialForecast.NONE;
			return;
		}
		record Placed(BlockPos pos, int segment, double along) {
		}
		// The lane flattened to primitives before the loop. Work times segments is the biggest
		// single computation the plan does — a full band against a wide route is well into the
		// hundreds of thousands of iterations — and doing it through Vec3 allocates a handful
		// of objects per iteration for arithmetic that is four doubles wide. Only the
		// horizontal axes matter: a lane is flat by construction.
		int segments = lane.size() / 2;
		double[] ax = new double[segments];
		double[] az = new double[segments];
		double[] bx = new double[segments];
		double[] bz = new double[segments];
		for (int i = 0; i < segments; i++) {
			ax[i] = lane.get(i * 2).x;
			az[i] = lane.get(i * 2).z;
			bx[i] = lane.get(i * 2 + 1).x;
			bz[i] = lane.get(i * 2 + 1).z;
		}
		// Ordered over every position the sweep will spend, not only the ones placeable this
		// instant: the lane is built from what can be reached now, but a carpet whose support
		// this same pass lays is spent on this same pass and belongs in the sequence at the
		// point its column is flown over — which is exactly where its support sits.
		List<Placed> ordered = new ArrayList<>(groupAll.size());
		for (BlockPos pos : groupAll) {
			double px = pos.getX() + 0.5;
			double pz = pos.getZ() + 0.5;
			int bestSegment = 0;
			double bestAlong = 0.0;
			double bestDistance = Double.MAX_VALUE;
			for (int i = 0; i < segments; i++) {
				double spanX = bx[i] - ax[i];
				double spanZ = bz[i] - az[i];
				double length = spanX * spanX + spanZ * spanZ;
				double t = length < 1.0e-6 ? 0.0
						: Math.max(0.0, Math.min(1.0,
								((px - ax[i]) * spanX + (pz - az[i]) * spanZ) / length));
				double dx = ax[i] + spanX * t - px;
				double dz = az[i] + spanZ * t - pz;
				double distance = dx * dx + dz * dz;
				if (distance < bestDistance) {
					bestDistance = distance;
					bestSegment = i;
					bestAlong = t;
				}
			}
			ordered.add(new Placed(pos, bestSegment, bestAlong));
		}
		ordered.sort(Comparator.comparingInt(Placed::segment)
				.thenComparingDouble(Placed::along));

		MaterialForecast.Builder builder = new MaterialForecast.Builder();
		Map<Item, Integer> routed = new HashMap<>();
		for (Placed placed : ordered) {
			Item item = itemFor(placed.pos());
			if (item == null) {
				continue;
			}
			builder.add(item, placed.segment() * 2 + 1);
			routed.merge(item, 1, Integer::sum);
		}
		// what the band needs beyond what the route can reach this pass
		Map<Item, Integer> unrouted = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : bandDemand.entrySet()) {
			if (!activeGroup.isEmpty() && !activeGroup.contains(entry.getKey())) {
				continue; // another pass's material; not this route's business
			}
			int rest = entry.getValue() - routed.getOrDefault(entry.getKey(), 0);
			if (rest > 0) {
				unrouted.put(entry.getKey(), rest);
			}
		}
		builder.addAhead(unrouted, lane.size());
		// The band above is only worth provisioning for when the bag is not already pledged
		// to one material. Under material passes it would put the next band's colours into a
		// forecast the restock reads as its shopping list, and the whole point of a pass is
		// that it carries one thing.
		if (!byMaterial()) {
			builder.addAhead(aheadDemand, lane.size() + 1);
		}
		forecast = builder.build();
		forecastCacheAt = -1;
		coverageAge = 0; // the picture changed; re-measure before anyone acts on it
	}

	/**
	 * Records that the material at {@code pos} is waiting on the one the schematic wants
	 * underneath it.
	 *
	 * <p>Learned from the schematic rather than hard-coded, so it holds for whatever a build
	 * happens to be made of: the printer asks the block itself whether it can stand here, and
	 * if the answer is no and the schematic wants something in the space below, that is the
	 * dependency. Map art gives carpet-on-cobblestone; a build with torches, rails, doors or
	 * slabs gives its own, with no list to keep up to date.
	 */
	private void noteSupport(BlockPos pos, BlockState required) {
		if (required == null || !needsSupport(required, pos)) {
			return;
		}
		// Taken from the state the caller already has: this runs once per position over tens
		// of thousands of them, and re-asking Litematica for what we are holding is the kind
		// of thing that turns a budgeted scan into a stutter.
		Item self = required.getBlock().asItem();
		if (dirtAsGrass.get() && self == Items.GRASS_BLOCK) {
			self = Items.DIRT;
		}
		Item support = itemFor(pos.below());
		if (self == Items.AIR || support == null || self == support) {
			return;
		}
		scanSupport.computeIfAbsent(self, key -> new HashSet<>()).add(support);
	}

	/**
	 * Whether this block needs something underneath it at all, asked of the block itself.
	 *
	 * <p>Put to it at a position with nothing below — high above the build, in a column the
	 * client certainly has — so the answer is about the block rather than about whatever
	 * happens to be under one particular spot. That difference is the whole fix: testing
	 * "does it survive <em>here</em>" answers no only where the floor is missing <em>yet</em>,
	 * so a material whose blocks all already have their floor looked like it needed nothing,
	 * joined the floor's own pass, and put the printer on a route whose first run it had no
	 * material for.
	 *
	 * <p>Cached per material: it is a property of the block, and the scan asks it once per
	 * position over tens of thousands of them.
	 */
	private boolean needsSupport(BlockState required, BlockPos pos) {
		Item item = required.getBlock().asItem();
		Boolean known = supportNeeded.get(item);
		if (known != null) {
			return known;
		}
		BlockPos sky = new BlockPos(pos.getX(), mc().level.getMaxY() - 1, pos.getZ());
		boolean needs = !required.canSurvive(mc().level, sky);
		supportNeeded.put(item, needs);
		return needs;
	}

	/**
	 * Whether this material is waiting on one that still owes blocks in this band.
	 *
	 * <p>Ranking alone gets this wrong whenever the floor is not also the commonest thing in
	 * the band: the printer picks the carpet first, finds none of it placeable because the
	 * cobblestone under it does not exist yet, defers the whole group, and has spent a pass
	 * discovering what the schematic already said. Worse, in a band where a colour outnumbers
	 * the floor, a supply run fills the bag with a material that cannot be laid at all.
	 *
	 * <p>Only counts a support that can actually be got and has not been given up on, so a
	 * missing floor material cannot wedge everything that stands on it.
	 */
	private boolean waitingOnSupport(Item item) {
		Set<Item> needs = supportOf.get(item);
		if (needs == null) {
			return false;
		}
		for (Item support : needs) {
			// Gated on the support having had its turn, not on its demand reaching zero.
			// Those differ, and the difference is a wedged band: every band has a few
			// positions that never become placeable — 54 of them in one of these reports —
			// so "wait until no cobblestone is owed" would have meant waiting forever, and
			// every carpet in the band would have been abandoned. A group is retired when it
			// finishes *or* when it stops making progress, and either is a fair signal that
			// the floor is as built as it is going to get.
			if (!groupDone.contains(support) && hasSource(support)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks the world-derived remainder against the file-derived total.
	 *
	 * <p>The remainder is "what the schematic wants minus what is already there", so it can
	 * never exceed what the schematic wants. If it does, the two disagree about what a
	 * position needs — a mapping bug (the dirt-for-grass swap, a block whose item differs
	 * from its block) rather than a counting one, and worth naming rather than quietly
	 * over-fetching for the rest of the print.
	 */
	private void crossCheck() {
		for (Map.Entry<Item, Integer> entry : bandDemand.entrySet()) {
			int total = bandTotal.getOrDefault(entry.getKey(), 0);
			if (entry.getValue() > total) {
				note("count disagrees for " + name(entry.getKey()) + ": " + entry.getValue()
						+ " still wanted but the schematic only has " + total + " in this band");
			}
		}
	}

	/**
	 * Whether the client actually has the world at this column.
	 *
	 * <p>The distinction the scan turns on. {@code getBlockState} does not fail on a missing
	 * chunk, it answers air — so without this test "the schematic wants carpet here and there
	 * is none" is indistinguishable from "the schematic wants carpet here and I cannot see
	 * whether there is any", and a band counted from the stash reports the far side of the
	 * map art as entirely unbuilt.
	 */
	private boolean loaded(int x, int z) {
		return mc().level.getChunkSource().hasChunk(x >> 4, z >> 4);
	}

	/** The item the schematic's block at {@code pos} is placed from, dirt-for-grass applied. */
	private Item itemFor(BlockPos pos) {
		BlockState required = LitematicaBridge.required(pos);
		if (required == null || required.isAir()) {
			return null;
		}
		Item item = required.getBlock().asItem();
		if (dirtAsGrass.get() && item == Items.GRASS_BLOCK) {
			item = Items.DIRT;
		}
		return item == Items.AIR ? null : item;
	}

	/**
	 * The flying height for this band: feet just clear of its top.
	 *
	 * <p>The clearance is not cosmetic. At exactly {@code bandMaxY + 1} the feet rest on the
	 * surface just printed, so {@code onGround()} is true — and vanilla's own {@code aiStep}
	 * switches flight off, and tells the server, every single tick a player is on the ground.
	 * The printer turns it straight back on, so the two trade abilities packets twenty times a
	 * second while friction fights the lane: the player crawls, jitters, and looks stuck
	 * without any stall ever being detected, because it <em>is</em> moving, just barely. A
	 * quarter of a block of air underneath ends the argument.
	 */
	private double routeY() {
		return bandMaxY + 1 + LANE_CLEARANCE;
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
	/**
	 * Draws the supply run: the line from where the printer is to the chest it is flying to.
	 *
	 * <p>A trip is the part of an AFK print you cannot see the reasoning behind — the printer
	 * simply leaves. Drawing the leg it is on makes "why has it gone over there" answerable at
	 * a glance, and makes a route that is fighting an obstacle obvious rather than something
	 * you notice only when the run times out.
	 *
	 * <p>Drawn from the player rather than from the path's first node, so the line starts at
	 * the eye and stays attached while flying. Where A* found no route the path is empty and
	 * this is one straight line to the chest — which is exactly what the run will fly.
	 */
	private void renderTrip() {
		BlockPos destination = stash.destination();
		if (destination == null) {
			return;
		}
		int argb = tripColor.get();
		Vec3 from = mc().player.getEyePosition();
		for (BlockPos step : stash.remainingPath()) {
			Vec3 to = Vec3.atCenterOf(step);
			Render3D.line(from, to, argb, 2.0f, true);
			from = to;
		}
		Render3D.line(from, Vec3.atCenterOf(destination), argb, 2.0f, true);
		// the chest itself, so the end of the line reads as a destination and not a stop
		BlockPos chest = stash.targetChest();
		if (chest != null) {
			Render3D.blockBox(chest, argb, 2.0f, (argb & 0x00FFFFFF) | 0x30000000, true);
		}
	}

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
	/**
	 * The same walk, counting only the band being built now.
	 *
	 * <p>Piggy-backed on the whole-region tally rather than given a scan of its own: it is one
	 * range check per cell, and it inherits the property that makes that tally trustworthy —
	 * it cycles, so placements, failures and hand edits all correct themselves without any
	 * bookkeeping to get out of step. The band snapshot the planner keeps ({@link #bandDemand})
	 * is only refreshed between passes, which can be a minute apart; this is live enough to
	 * watch.
	 */
	private final Map<Item, Integer> tallyBand = new HashMap<>();
	private List<Map.Entry<Item, Integer>> bandView = List.of();
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
			tallyBand.clear();
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
							if (region != null && y >= bandMinY && y <= bandMaxY) {
								tallyBand.merge(item, 1, Integer::sum);
							}
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
			List<Map.Entry<Item, Integer>> band = new ArrayList<>(tallyBand.entrySet());
			band.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
			bandView = List.copyOf(band);
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

	// ---- restock (LP3b, carried shulkers) ---------------------------------------

	/**
	 * Whether a container opening right now is one the printer asked for, so
	 * {@code GuiMixin} swallows the window.
	 *
	 * <p>Narrow on purpose, exactly as AutoBrew's is: a shulker <em>you</em> open by hand
	 * while the printer happens to be running must still show.
	 */
	public boolean suppressesScreens() {
		return isEnabled() && restockOpen;
	}

	/** What a refill is doing, for the HUD. Empty when neither is running. */
	public String restockStatus() {
		return stash.busy() ? stash.status() : restock.busy() ? restock.status() : "";
	}

	/**
	 * Whether the outgoing movement packet should claim ground: the restocker's final descent,
	 * or any time the printer is flying on flight it granted itself.
	 *
	 * <p>It used to be only the first, and the second is why the printer kept killing people
	 * with NoFall switched on. NoFall's Packet mode fires on {@code fallDistance >
	 * minFall} — the <em>client's</em> fall distance. While {@code abilities.flying} is set,
	 * vanilla holds that at zero, because as far as the client is concerned there is no fall.
	 * So Packet mode has nothing to react to and never lies, while the server — which may not
	 * have honoured the flight we granted ourselves — is tracking a descent the whole time and
	 * bills it. A live capture shows exactly that: {@code took 5.0 damage (fall=0.00 before,
	 * flying=true, noFall=Packet)}. Not a fall the client ever saw.
	 *
	 * <p>So the printer stops depending on NoFall's mode and covers its own flight, for as
	 * long as it is the one doing the flying.
	 *
	 * <p>Deliberately <em>not</em> keyed on {@link #grantedFlight}. That flag is only set when
	 * this module is the thing that turned {@code mayfly} on, and it is not reliably the
	 * first: both refills assert flight themselves, so whichever runs first leaves the flag
	 * false and the protection silently off for the rest of the session. A guard that depends
	 * on winning a race is not a guard. The honest condition is the plain one — the printer is
	 * running, it is flying, and the player is off the ground.
	 */
	public boolean protectsRestockLanding() {
		if (restock.protectsLanding()) {
			return true;
		}
		return isEnabled() && noFallInFlight.get() && !movement.is("Off")
				&& mc().player != null && !mc().player.onGround()
				&& !mc().player.isCreative();
	}

	/** Whether a {@code setScreen(null)} right now belongs to our own container close. */
	public boolean suppressesClose() {
		return isEnabled() && ContainerUtil.isClosing();
	}

	/**
	 * The forecast from where the printer has actually got to — the demand a restock aims at.
	 *
	 * <p>Trimmed to what is still ahead on every call, because a refill half way along a pass
	 * should provision for the half it has left, not for the whole band it started. Asking for
	 * the lot is what sent the printer home with a bag full of the colour it had just finished
	 * laying.
	 */
	private MaterialForecast forecastAhead() {
		// Cached per waypoint. Properly interleaved, a band's forecast is thousands of runs
		// rather than the dozens it was when carpet all collapsed into one lump at the end,
		// and rebuilding the trimmed view every tick — which is what the declining path does
		// while the shulkers are empty — is a list walk the answer cannot have changed over.
		if (forecastCacheAt != laneIndex) {
			forecastCacheAt = laneIndex;
			forecastCache = forecast.from(laneIndex);
		}
		return forecastCache;
	}

	/**
	 * The shopping list a supply run fills against — {@link #forecastAhead} plus what the band
	 * needs after this group.
	 *
	 * <p>Deliberately <em>not</em> what {@link #restockDue} measures. The trigger has to ask
	 * "is the work I am flying now about to run dry", and asking it against a list that runs
	 * on into the next colour would fire the moment a nearly-finished group was fully stocked —
	 * the exact loop that once sent the printer to the stash and back for ever over a hundred
	 * cobblestone it was already carrying. So: the trigger measures this pass, the trip fills
	 * for the band. Going is decided by what is short; how much to bring back is not.
	 */
	private MaterialForecast forecastForTrip() {
		if (!byMaterial() || activeGroup.isEmpty()) {
			return forecastAhead();
		}
		Map<Item, Integer> band = exactBandNeed();
		if (band == null) {
			return forecastAhead(); // too big to count in hand; the trigger's view will do
		}
		Map<Item, Integer> pass = new LinkedHashMap<>();
		for (Item item : ranking) { // ranking order, so the floor is bought before what stands on it
			if (activeGroup.contains(item)) {
				int left = band.getOrDefault(item, 0);
				if (left > 0) {
					pass.put(item, left);
				}
			}
		}
		if (pass.isEmpty()) {
			return forecastAhead();
		}
		MaterialForecast.Builder builder = new MaterialForecast.Builder();
		builder.addAhead(pass, 0);
		return builder.build();
	}

	/**
	 * Exactly what the band still needs, counted now.
	 *
	 * <p>Every other count here trades accuracy for cheapness and every one of them has been
	 * wrong at the moment it mattered. The scan snapshot is taken once a pass and goes stale
	 * as blocks go down. The rolling tally is a complete answer from a few seconds ago. The
	 * route forecast is trimmed by a lane index that only moves forward, so work flown over
	 * without material silently stops being asked for. Three approximations, three ways to
	 * come home short.
	 *
	 * <p>A trip is worth being exact for. It happens every thirty seconds or so, it commits
	 * the bag for the next several minutes, and the band it has to cover is small — two
	 * layers of a map art is about thirty thousand cells, which is one and a half ticks of
	 * the background tally's own budget. So this walks the whole band, compares every
	 * position against the world, and answers with no snapshot and no window: what is missing
	 * <em>right now</em>.
	 *
	 * <p>Cached against {@link #totalPlaced}, so the walk happens once per trip rather than
	 * once per tick — nothing is placed while a supply run is flying, so the answer cannot go
	 * stale during the run it was taken for.
	 *
	 * @return the count, or null when the band is too large to walk in one tick
	 */
	private Map<Item, Integer> exactBandNeed() {
		if (region == null) {
			return null;
		}
		// Exact against the placement count, with a floor on how often the walk may run. The
		// stamp is what makes it exact; the floor is what stops a printer that is placing and
		// asking in the same tick from walking the band twenty times a second. During a trip
		// nothing is placed, so the stamp never moves and the floor never binds — the answer
		// the run commits to is exact for the whole run, which is the case that matters.
		if (exactNeed != null
				&& (exactNeedStamp == totalPlaced || workTicks - exactNeedAt < EXACT_MIN_TICKS)) {
			return exactNeed;
		}
		long cells = (long) (region.max().getX() - region.min().getX() + 1)
				* (bandMaxY - bandMinY + 1)
				* (region.max().getZ() - region.min().getZ() + 1);
		if (cells > EXACT_CELL_CAP) {
			// A hand-driven layer range can make the "band" the whole schematic. Freezing the
			// game for a second to count it would be a worse bug than the one this fixes.
			return null;
		}
		Map<Item, Integer> need = new HashMap<>();
		BlockPos.MutableBlockPos walk = new BlockPos.MutableBlockPos();
		for (int x = region.min().getX(); x <= region.max().getX(); x++) {
			for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
				if (!loaded(x, z)) {
					continue; // unknown, not empty — the same rule the band scan follows
				}
				for (int y = bandMinY; y <= bandMaxY; y++) {
					walk.set(x, y, z);
					if (wantsBlock(walk, true, false, false)) {
						tallyRequired(walk, need);
					}
				}
			}
		}
		exactNeed = need;
		exactNeedStamp = totalPlaced;
		exactNeedAt = workTicks;
		return need;
	}

	/**
	 * Whether this pass has run out of something it can still get more of.
	 *
	 * <p>The trigger for a material pass, and it is deliberately the plain question rather
	 * than the clever one. Prediction earns its keep when a route interleaves nine colours
	 * and you want to leave <em>before</em> the awkward one runs dry half a lane from home.
	 * A pass carries one material, or a set that all fits in a bag, and it leaves the chest
	 * with every block of it that the bag will hold — so there is nothing to predict. It runs
	 * out when it runs out, and that is exactly the right moment to go.
	 *
	 * <p>What this replaces is a threshold — {@code coverage < min(restockAt, routeLength)} —
	 * and thresholds against a shrinking route are where this module kept hurting itself. Near
	 * the end of a pass the route is shorter than the margin, so the condition read "go and
	 * fetch more" for a bag that already held every block still needed, and the printer flew
	 * to the stash and back for ever. "Do I have none of something I still need and could
	 * still get" cannot do that: fetching satisfies it, so it cannot fire twice for the same
	 * shortage.
	 */
	private boolean passIsDry() {
		Map<Item, Integer> need = exactBandNeed();
		for (Item item : activeGroup) {
			int left = need == null ? bandDemand.getOrDefault(item, 0) : need.getOrDefault(item, 0);
			if (left > 0 && carriedCount(item) <= 0 && hasSource(item)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the bag is close enough to running out to be worth a trip now.
	 *
	 * <p>The change of principle: a refill is triggered by <em>predicting</em> the shortage,
	 * not by hitting it. Waiting for a placement to fail means the printer always runs dry in
	 * the middle of a lane, flies to the base, and flies all the way back to where it was —
	 * the round trip is paid at the worst possible moment, every time. Measuring how far the
	 * route can still be flown lets the trip be taken with a margin in hand.
	 *
	 * <p>Not measured every tick: the walk is cheap but the answer moves slowly, and a
	 * re-measure every {@link #COVERAGE_EVERY} ticks is well inside the margin.
	 */
	private boolean restockDue() {
		if (finished || paused || forecast.isEmpty() || movement.is("Off")) {
			return false;
		}
		if (byMaterial() && !activeGroup.isEmpty()) {
			return passIsDry();
		}
		if (--coverageAge > 0) {
			return coverageLeft < Math.min(restockAt.getInt(), forecastAhead().size());
		}
		coverageAge = COVERAGE_EVERY;
		MaterialForecast ahead = forecastAhead();
		Map<Item, Integer> carried = new HashMap<>();
		// Resolved once per measurement rather than per run: each answer walks the whole
		// inventory and every carried box's contents, and the forecast asks hundreds of times.
		// Distinct materials in a band are a couple of dozen at most.
		Set<Item> fromBag = new HashSet<>();
		Set<Item> fixable = new HashSet<>();
		for (Item item : ahead.totals().keySet()) {
			carried.put(item, carriedCount(item));
			if (restock.canSupply(item)) {
				fromBag.add(item);
				fixable.add(item);
			} else if (stash.mightSupply(item)) {
				fixable.add(item);
			}
		}
		coverageLeft = ahead.coverage(carried, fixable::contains);
		coverageEndsOn = ahead.firstShortfall(carried, fixable::contains);
		// Measured against the work that is actually left, not against the margin alone. At
		// the end of a material the route is shorter than the margin — a hundred blocks of
		// cobblestone against a trigger of a hundred and twenty-eight — so a bag holding
		// every one of them still read as "about to run out", and the printer went to the
		// stash, filled up, came back, and did it again for ever. Covering the whole route
		// is never a reason to fetch more of it.
		// Which of the two refills this shortage wants. A carried box is seconds of work on
		// the spot; the stash is a flight. Asking the question here rather than at the call
		// site means it is answered off the same measurement, so the two can never disagree
		// about what is short.
		bottleneckInBag = coverageEndsOn != null && fromBag.contains(coverageEndsOn);
		return coverageLeft < Math.min(restockAt.getInt(), ahead.size());
	}

	/** Loose count of an item in the inventory — what is actually placeable, shulkers aside. */
	private int carriedCount(Item item) {
		Inventory inventory = mc().player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Blocks of route the bag can still see through, and the material that ends it.
	 *
	 * <p>Exposed so the prediction is visible rather than implied. A forecast you cannot read
	 * off the HUD is one you cannot tell is wrong, and every restock bug in this module so far
	 * has been a wrong number that nothing was printing.
	 */
	public int forecastCoverage() {
		return forecast.isEmpty() ? -1 : coverageLeft;
	}

	/** The material {@link #forecastCoverage} runs out of, or null while the route is covered. */
	public Item forecastShortfall() {
		return coverageEndsOn;
	}

	/** Adds the block the schematic wants at {@code pos} to {@code into}, dirt-for-grass applied. */
	private void tallyRequired(BlockPos pos, Map<Item, Integer> into) {
		Item item = itemFor(pos);
		if (item != null) {
			into.merge(item, 1, Integer::sum);
		}
	}

	/**
	 * Whether the schematic itself claims this position — the restock's "keep off" test.
	 *
	 * <p>Deliberately narrower than the placement region: a build is mostly air the
	 * schematic has no opinion about, and refusing the whole box would push every refill
	 * off the site. What must not be touched is a position the schematic wants a block at
	 * — placed or not — since a shulker standing there is one the printer would try to
	 * build through, and breaking it later would take the build with it. Standing a box
	 * on top of what is already printed is fine, and often the only ground there is.
	 */
	private boolean schematicWants(BlockPos pos) {
		BlockState required = LitematicaBridge.required(pos);
		return required != null && !required.isAir();
	}

	/**
	 * Whether the restock is mining right now, so vanilla can be kept off the destroy.
	 *
	 * <p>{@code Minecraft.continueAttack} runs every tick and calls
	 * {@code stopDestroyBlock()} whenever the attack key is not held. Our module tick
	 * runs after it, so each tick went: vanilla cancels, we start again from zero.
	 * Progress never accumulated and the shulker was never broken — invisibly, because
	 * every individual call succeeded.
	 */
	public boolean isMining() {
		return isEnabled() && restock.mining();
	}

	/** The refill's live shopping list — each block being fetched and how far along, largest first. */
	public java.util.List<ShulkerRestock.Fetch> restockPlan() {
		return restock.fetching();
	}

	/** Whether {@code .pause} has the printer held. */
	public boolean isPaused() {
		return paused;
	}

	/**
	 * Toggles the pause, keeping every bit of state.
	 *
	 * <p>Not the same as switching the module off: the band, the lane and how far along
	 * it is all survive, and so do the counters — including the elapsed clock, which
	 * simply stops ticking, because a pause is not work.
	 */
	public String togglePause() {
		paused = !paused;
		if (paused) {
			restoreSlot();
			releaseFlight();
		}
		return paused ? "Printer paused - .pause again to carry on" : "Printer resumed";
	}

	/**
	 * Keeps the marked-chest list and the saved setting in step, in whichever direction moved.
	 *
	 * <p>The setting is the storage and {@link ChestStash} is the working copy, so this is the
	 * one place they meet. Driven off a stamp rather than a dirty flag because the setting can
	 * also change from underneath us — a config load, or someone editing the file — and a
	 * stash that silently ignored that would be the sort of bug you only find at 3am with an
	 * empty bag.
	 */
	private void syncStash() {
		String saved = stashList.get();
		if (!saved.equals(stashStamp)) {
			stashStamp = saved;
			stash.load(saved);
		}
	}

	/**
	 * {@code .plan}: what the printer is about to lay, in the order it will lay it.
	 *
	 * <p>Answers "what blocks will we be placing on this route" directly, because the honest
	 * answer to "this planning seems random" is that a plan you cannot read is
	 * indistinguishable from no plan. Shows the route ahead in chunks with the materials each
	 * chunk spends, then where the bag gives out and on what — which is exactly the number the
	 * refill trigger fires on, so a refill that looks mistimed can be checked against the
	 * reasoning that timed it.
	 */
	public void planReport(java.util.function.Consumer<String> out) {
		MaterialForecast ahead = forecastAhead();
		if (ahead.isEmpty()) {
			out.accept("No route planned yet - the printer plans a band when Movement is on.");
			return;
		}
		if (byMaterial()) {
			out.accept("Building " + (activeGroup.isEmpty() ? "(nothing yet)" : names(activeGroup))
					+ " - band " + bandMinY + ".." + bandMaxY + " order: " + names(ranking));
			if (!groupDone.isEmpty()) {
				out.accept("Done this round: " + names(groupDone));
			}
		}
		out.accept("Route ahead: " + ahead.size() + " blocks, waypoint "
				+ Math.min(laneIndex + 1, lane.size()) + "/" + lane.size()
				+ " of band " + bandMinY + ".." + bandMaxY);
		// Reported in stretches rather than per run: three thousand runs is data, five lines
		// with counts is an answer.
		int chunk = Math.max(1, ahead.size() / PLAN_CHUNKS);
		Map<Item, Integer> spend = new HashMap<>();
		int seen = 0;
		int shown = 0;
		int from = 0;
		for (MaterialForecast.Run run : ahead.runs()) {
			spend.merge(run.item(), run.count(), Integer::sum);
			seen += run.count();
			if (seen - from >= chunk && shown < PLAN_CHUNKS) {
				out.accept("  " + from + ".." + seen + ": " + topItems(spend));
				spend.clear();
				from = seen;
				shown++;
			}
		}
		if (!spend.isEmpty()) {
			out.accept("  " + from + ".." + seen + ": " + topItems(spend));
		}
		Map<Item, Integer> carried = new HashMap<>();
		for (Item item : ahead.totals().keySet()) {
			carried.put(item, carriedCount(item));
		}
		out.accept("Carried covers " + ahead.coverage(carried) + " blocks"
				+ (coverageEndsOn == null ? "" : ", then out of " + name(coverageEndsOn))
				+ " (refill at " + restockAt.getInt() + ")");
	}

	/** Stretches the {@code .plan} breakdown is cut into. */
	private static final int PLAN_CHUNKS = 6;
	/** Materials named per stretch before the tail is summarised. */
	private static final int PLAN_ITEMS = 5;

	/** The biggest few materials of a stretch, largest first. */
	private String topItems(Map<Item, Integer> spend) {
		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(spend.entrySet());
		sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < sorted.size() && i < PLAN_ITEMS; i++) {
			if (i > 0) {
				text.append(", ");
			}
			text.append(name(sorted.get(i).getKey())).append(' ').append(sorted.get(i).getValue());
		}
		if (sorted.size() > PLAN_ITEMS) {
			text.append(" +").append(sorted.size() - PLAN_ITEMS).append(" more");
		}
		return text.toString();
	}

	/** An item's bare name, without the namespace noise a chat line cannot spare. */
	private static String name(Item item) {
		String id = item.getDescriptionId();
		return id.substring(id.lastIndexOf('.') + 1);
	}

	/** {@code .stash}: marks or unmarks the container being looked at. */
	public String markStash(BlockPos pos) {
		syncStash();
		String said = stash.mark(pos);
		String saving = stash.save();
		stashList.set(saving);
		// Stamp with what the setting actually stored, not with what we handed it. The two
		// differ only if the value was clipped on the way in, and a clipped list is the one
		// kind of corruption nothing downstream can catch: a coordinate missing its last
		// digit still parses, so it becomes a real chest somewhere else entirely and the
		// next survey dutifully flies to it. Refuse the mark instead.
		stashStamp = stashList.get();
		if (!stashStamp.equals(saving)) {
			// Only an add can overflow — a removal shortens the list — so marking the same
			// container again takes it straight back off.
			stash.mark(pos);
			stashStamp = stash.save();
			stashList.set(stashStamp);
			return "Stash list is full - " + pos.toShortString() + " was not added. "
					+ "Remove a chest with .stash while looking at it first.";
		}
		return said;
	}

	/** {@code .stash clear}. */
	public String clearStash() {
		String said = stash.clear();
		stashStamp = stash.save();
		stashList.set(stashStamp);
		return said;
	}

	/** {@code .stash list}. */
	public String describeStash() {
		syncStash();
		return stash.describe();
	}

	/** {@code .stash check}: fly the stash now and re-read every chest. */
	public String checkStash() {
		syncStash();
		if (!isEnabled()) {
			return "Turn the Printer on first - the check flies to the chests";
		}
		if (movement.is("Off")) {
			return "Movement is Off, so the printer cannot fly to the chests";
		}
		// A hand-typed check re-reads the chests without touching the bag: you may well be
		// carrying exactly what you meant to carry.
		return stash.beginSurvey(false)
				? "Checking the stash..."
				: "Nothing to check - no chests marked, or a run is already under way";
	}

	/** {@code .pbase}: remembers where refills should happen, or forgets it. */
	public String setBase(BlockPos here) {
		if (here == null) {
			restockBase.set("");
			return "Restock base cleared - refills will look for a spot near the work";
		}
		restockBase.set(here.getX() + " " + here.getY() + " " + here.getZ());
		return "Restock base set to " + here.toShortString();
	}

	/** The saved base, or null when unset or unreadable. */
	private BlockPos parseBase() {
		String raw = restockBase.get().trim();
		if (raw.isEmpty()) {
			return null;
		}
		String[] parts = raw.split("[ ,]+");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null; // a hand-edited setting must not break the print
		}
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

	/**
	 * One material of the band being built: how much is left, and whether this pass is on it.
	 *
	 * <p>{@code active} is what makes the widget answer "is it fetching the right thing" —
	 * the materials the current pass is allowed to place are exactly the ones a refill should
	 * be bringing back, so a fetch of anything else is visibly wrong.
	 */
	/**
	 * A material in the active layers: how much is still missing, and how much the layers
	 * hold in all. The second is what makes the first judgeable — "2 white carpet" reads as
	 * wrong on a map art covered in white carpet until you can see it is 2 of 2.
	 */
	public record BandItem(Item item, int left, int total, boolean active) {
	}

	/** What the active layers still need, largest first. Empty until the tally has been round. */
	public List<BandItem> bandMaterials() {
		List<BandItem> out = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : bandView) {
			out.add(new BandItem(entry.getKey(), entry.getValue(),
					bandTotal.getOrDefault(entry.getKey(), 0),
					activeGroup.contains(entry.getKey())));
		}
		return out;
	}

	/** The layers being built, as "100" or "100-101"; empty when there is no band yet. */
	public String bandLabel() {
		if (region == null) {
			return "";
		}
		return bandMinY == bandMaxY ? String.valueOf(bandMinY) : bandMinY + "-" + bandMaxY;
	}

	/**
	 * The active group named short enough for a HUD line.
	 *
	 * <p>Spelling out all ten members turned the status into a sentence and the widget into a
	 * banner across half the screen. The group is in ranking order, so the first name is the
	 * one that matters — the rest are along for the ride and a count says as much.
	 */
	private String shortGroup() {
		if (activeGroup.isEmpty()) {
			return "";
		}
		Item first = activeGroup.iterator().next();
		int rest = activeGroup.size() - 1;
		return " [" + name(first) + (rest > 0 ? " +" + rest : "") + "]";
	}

	/** One line of what the automation is doing, for the HUD. */
	public String hudStatus() {
		if (!LitematicaBridge.hasSchematic()) {
			return "waiting for a schematic";
		}
		if (printRegion == null) {
			return scoped ? schematic.get() + " is not placed" : "nothing placed";
		}
		if (paused) {
			return "paused";
		}
		if (stash.busy()) {
			return stash.status();
		}
		if (restock.busy()) {
			return restock.status();
		}
		if (finished) {
			return "done";
		}
		if (movement.is("Off")) {
			return "printing in place";
		}
		String band = "band " + bandMinY + (bandMaxY != bandMinY ? ".." + bandMaxY : "")
				+ " pass " + Math.max(1, passesThisBand);
		// The forecast belongs on the drive line: it is the one moment the number is
		// actionable, and seeing "312 to white_concrete" tick down is how you tell at a
		// glance that the prediction is tracking rather than guessing.
		String stock = coverageEndsOn == null || forecast.isEmpty() ? ""
				: ", " + coverageLeft + " to " + name(coverageEndsOn);
		String job = shortGroup();
		return switch (phase) {
			case PLAN -> "scanning " + band + job;
			case DRIVE -> band + job + ", waypoint " + Math.min(laneIndex + 1, lane.size())
					+ "/" + lane.size() + stock;
			case SETTLE -> band + job + ", settling";
		};
	}

	// ---- in-game bug reports ----------------------------------------------------

	/** Rolling trail of what the automation decided, for {@link #report}. */
	private final ArrayDeque<String> events = new ArrayDeque<>();

	/** Entries the trail keeps; older ones fall off the front. */
	private static final int EVENT_TRAIL = 300;

	/**
	 * Adds to the event trail, collapsing a run of identical entries into one with a count.
	 *
	 * <p>It used to drop an event matching <em>anything</em> still in the trail, to stop a
	 * block written off every few seconds from flooding the history. That silently deleted
	 * the one fact a restock report is read for: <b>how often something happened</b>. A trail
	 * showing "supply run to 401, 99, 583" once, and four "supply run done" lines with no
	 * runs to match them, reads like trips completing out of nowhere — and the complaint
	 * being diagnosed was that trips were too frequent, which is precisely what the filter
	 * was hiding. Collapsing consecutive repeats keeps the flood out and keeps the count.
	 */
	private void note(String event) {
		String last = events.peekLast();
		if (last != null) {
			// "[12:34:56] thing" or "[12:34:56] thing x4"
			int mark = last.indexOf("] ");
			String body = mark < 0 ? last : last.substring(mark + 2);
			int times = 1;
			int x = body.lastIndexOf(" x");
			// x + 2 < length, or a line that merely ends in " x" parses as an empty count
			if (x > 0 && x + 2 < body.length()
					&& body.substring(x + 2).chars().allMatch(Character::isDigit)) {
				times = Integer.parseInt(body.substring(x + 2));
				body = body.substring(0, x);
			}
			if (body.equals(event)) {
				events.removeLast();
				events.addLast(String.format("[%tT] %s x%d", new java.util.Date(), event, times + 1));
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
		lines.add("restock: " + restock.debug());
		lines.add("stash:   " + stash.debug() + " bottleneckInBag=" + bottleneckInBag);
		// The forecast in full, because "it restocked at the wrong time" is only diagnosable
		// against the numbers it decided from: how far it thought the bag stretched, on what
		// it expected to run out, and what the band above was going to want.
		lines.add(String.format("forecast: %d blocks / %d runs | covered %d%s | trigger at %d",
				forecast.size(), forecast.runs().size(), coverageLeft,
				coverageEndsOn == null ? " (route covered)" : " then out of " + coverageEndsOn,
				restockAt.getInt()));
		// And separately what a *trip* would shop for, which is a longer list than the trigger
		// measures: the two being different is the whole design, so both have to be readable.
		MaterialForecast trip = forecastForTrip();
		lines.add(String.format("trip list: %d blocks / %d runs | %s",
				trip.size(), trip.runs().size(), trip.totals()));
		lines.add("material passes: " + byMaterial() + " bag=" + BAG_SLOTS + " slots");
		lines.add("  ranking (frozen): " + names(ranking));
		lines.add("  building now:     " + (activeGroup.isEmpty() ? "(everything)" : names(activeGroup))
				+ " - " + groupWork.size() + " placeable of " + groupAll.size() + " wanted");
		lines.add("  done this round:  " + (groupDone.isEmpty() ? "-" : names(groupDone)));
		lines.add("  no source:        " + (noSource.isEmpty() ? "-" : names(noSource)));
		for (Map.Entry<Item, Set<Item>> entry : supportOf.entrySet()) {
			lines.add("  " + name(entry.getKey()) + " stands on " + names(entry.getValue())
					+ (waitingOnSupport(entry.getKey()) ? " - waiting" : ""));
		}
		lines.add(String.format("scan coverage: %d column(s) unseen at last scan (%d blocks), "
				+ "render distance %d chunks", unseenColumns, unseenBlocks,
				mc().options.renderDistance().get()));
		// Both counts, side by side: what the file says the band contains, and what is still
		// missing from it. A remainder that looks wrong is only judgeable against the total.
		lines.add("band total (from the schematic file): " + bandTotal);
		// Both, because they disagree and the disagreement is the interesting part: the scan
		// snapshot is taken once per pass and goes stale as blocks go down, while the tally
		// re-walks continuously. A trip sized from the wrong one is the whole bug class here.
		lines.add("band demand (scan snapshot): " + bandDemand);
		Map<Item, Integer> live = new LinkedHashMap<>();
		for (Map.Entry<Item, Integer> entry : bandView) {
			live.put(entry.getKey(), entry.getValue());
		}
		lines.add("band left (live tally):     " + live);
		Map<Item, Integer> exact = exactBandNeed();
		lines.add("band left (exact, counted now): "
				+ (exact == null ? "band too large to count in hand" : exact));
		lines.add("next band:   " + aheadDemand);
		lines.add(String.format(
				"state: %s band=%d..%d pass=%d waypoint=%d/%d cand=%d pending=%d unsolvable=%d "
						+ "finished=%b holdingForRefill=%b (%d ticks)",
				phase, bandMinY, bandMaxY, passesThisBand, laneIndex, lane.size(),
				candidates.size(), pending.size(), unsolvable.size(), finished, holdLane,
				holdTicks));
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

	/**
	 * Turns flight on, remembering whether it was ours to give, and telling the server.
	 *
	 * <p>The sync is not optional and its absence was a survival-only disaster. A refill lands
	 * the player to mine at full speed, which sends "I am no longer flying". Flight then goes
	 * back on here — and if that only sets the flag, the server is never told, so it goes on
	 * believing the player is walking while the client flies the lane at a block a tick. The
	 * server rejects the movement, rubber-bands the player, and nothing is ever in reach long
	 * enough to place: a printer that travels and never builds. Creative hides it completely,
	 * because a creative player may fly whatever the client last claimed — which is exactly
	 * why it looked like survival was the broken case rather than the honest one.
	 */
	private void grantFlight() {
		allowFlight();
		Abilities abilities = mc().player.getAbilities();
		if (!abilities.flying) {
			abilities.flying = true;
			mc().player.onUpdateAbilities();
		}
	}

	/**
	 * Grants the ability to fly without asserting flight itself.
	 *
	 * <p>For the stretches where something else decides whether the player is airborne — a
	 * refill that wants to stand on the ground to mine at full speed. Handing it the capability
	 * and not the state is what lets the two coexist without arguing over it every tick.
	 */
	/**
	 * Records every point of damage taken while printing, with the state that explains it.
	 *
	 * <p>"We still take damage landing, and NoFall does not help" has at least four possible
	 * causes that look identical from the outside — NoFall off or in a mode with a threshold,
	 * the spoof not reaching the server, flight cut somewhere without the landing guard, or
	 * damage that was never fall damage at all (a pad broken underfoot, suffocation inside
	 * the build). Guessing between them has a poor record here. Every one of them is
	 * distinguishable from the numbers at the moment the health drops, so take those instead:
	 * the fall distance <em>before</em> the tick that hurt, whether we were flying, whether
	 * the landing guard was asserting ground, and what NoFall was set to.
	 */
	private void watchDamage() {
		net.minecraft.client.player.LocalPlayer player = mc().player;
		float health = player.getHealth();
		if (lastHealth >= 0.0f && health < lastHealth - 0.01f) {
			Abilities abilities = player.getAbilities();
			unlucky.utility.client.module.modules.movement.NoFall noFall =
					unlucky.utility.client.UnluckyClient.INSTANCE.modules
							.get(unlucky.utility.client.module.modules.movement.NoFall.class);
			note(String.format(
					"took %.1f damage (fall=%.2f before, onGround=%b, flying=%b, mayfly=%b, "
							+ "noFall=%s, landingGuard=%b, restock=%s, stash=%s)",
					lastHealth - health, lastFall, player.onGround(), abilities.flying,
					abilities.mayfly,
					noFall.isEnabled() ? noFall.mode.get() : "off",
					restock.protectsLanding(), restock.stage(), stash.stage()));
		}
		lastHealth = health;
		// Sampled before the move, so a drop reports the distance that caused it rather than
		// the zero vanilla leaves behind the instant it lands.
		lastFall = player.fallDistance;
	}

	/**
	 * Parks the player in the air, properly.
	 *
	 * <p>Zeroing the velocity is not enough and was actively harmful: {@code allowFlight}
	 * grants the <em>capability</em> only, so a hold that began after a refill had cut flight
	 * left the player falling. Gravity is re-applied after the velocity is set, so it looked
	 * stationary while descending a fraction of a block a tick — and fall distance accumulates
	 * by distance, not by speed. Ten seconds of that is a real drop, banked silently and
	 * charged the moment anything touched down. Flight has to actually be on.
	 */
	private void hoverInPlace() {
		Abilities abilities = mc().player.getAbilities();
		if (abilities.mayfly && !abilities.flying) {
			abilities.flying = true;
			mc().player.onUpdateAbilities(); // the server bills the arrival if it is not told
		}
		mc().player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		mc().player.fallDistance = 0.0;
	}

	private void allowFlight() {
		Abilities abilities = mc().player.getAbilities();
		if (!abilities.mayfly) {
			abilities.mayfly = true;
			mc().player.onUpdateAbilities();
			grantedFlight = true;
		}
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
			outOfItem = true;
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
		// Never while a refill owns the player. Both this and the refill's own lookAt claim the
		// head at the same priority, so holding the printer's last build angle here meant the
		// two took it in turns every single tick: aim at the schematic, aim at the shulker,
		// aim at the schematic — two rotation packets a tick, twenty times a second, and a
		// player visibly snapping back and forth. The refill is the one interacting; it wins.
		if (restock.busy() || stash.busy()) {
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
