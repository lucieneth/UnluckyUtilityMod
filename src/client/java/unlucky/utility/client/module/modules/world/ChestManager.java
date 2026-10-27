package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.command.CommandManager;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.modules.render.Shader;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ChestDatabase;
import unlucky.utility.client.util.ChestDatabase.Entry;
import unlucky.utility.client.util.ChestDatabase.Query;
import unlucky.utility.client.util.ChestDatabase.Stored;
import unlucky.utility.client.util.ChestDatabase.Tally;
import unlucky.utility.client.util.ContainerUtil;
import unlucky.utility.client.util.EspColors;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.SwingUtil;
import unlucky.utility.client.util.WorldScan;

/**
 * A searchable record of the chests in your base.
 *
 * <p>{@code .cm add} tags the chest or barrel under the crosshair, {@code .cm click} turns every
 * right-click on one into a tag until it is typed again, and {@code .cm search <item>} lights up
 * every tagged chest holding it — inside shulkers and bundles too — with the Shader glow, a
 * count above it, and the slot itself outlined once you open it.
 *
 * <p><b>Scanning is silent for you and ordinary for everyone else.</b> The head turns on the
 * server only ({@link RotationManager}), the arm swings, the chest opens and its lid lifts for
 * anyone watching, but no window appears on your screen and your camera never moves: the menu
 * goes live without its screen ({@code GuiMixin}), the contents are read the tick the server
 * sends them, and it closes again. One chest is one round trip, full or empty, because a
 * shulker's contents travel inside the chest's own packet.
 *
 * <p><b>Auto mode</b> ({@code .cm auto}, or the Chests panel's button) reads every chest and barrel
 * that comes into reach and isn't recorded yet, one at a time, without a word in chat; one that
 * wouldn't open is tried again half a minute later, and one you open yourself is recorded too.
 * While it or click mode is on, nearby chests are outlined green once recorded and red until then.
 *
 * <p><b>Opening a tracked chest yourself keeps it current</b>, so the record follows what you
 * move by hand without a rescan.
 */
public class ChestManager extends Module {
	/** Ticks to wait for the server's contents before giving a chest up. */
	private static final int OPEN_TIMEOUT = 40;
	/** Ticks the head is turned before the click, so the turn is seen before the lid opens. */
	private static final int LOOK_TICKS = 2;
	/** Ticks before auto mode tries a chest that wouldn't open again. */
	private static final int AUTO_RETRY = 600;

	public final NumberSetting range = add(new NumberSetting("Range",
			"How close a chest must be to scan it", 4.5, 2, 6, 0.5));
	public final NumberSetting delay = add(new NumberSetting("Scan delay",
			"Ticks between one chest closing and the next opening", 5, 1, 20, 1));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Turn your head toward the chest on the server before opening it", true));
	public final BooleanSetting swing = add(new BooleanSetting("Swing",
			"Swing the arm when opening, as a real click does", true));
	public final BooleanSetting refresh = add(new BooleanSetting("Update on open",
			"Re-record a tracked chest whenever you open it yourself", true));

	public final BooleanSetting glow = add(new BooleanSetting("Glow",
			"Shader outline on the chests holding what you searched for", true));
	public final ColorSetting glowColor = add(new ColorSetting("Glow color",
			"Outline colour of matching chests", 0xFF55FFFF), glow::get);
	public final NumberSetting glowExtra = add(new NumberSetting("Glow extra width",
			"How much thicker matching chests are drawn than other Shader outlines", 0.5, 0.0, 3.0, 0.1), glow::get);
	public final BooleanSetting labels = add(new BooleanSetting("Labels",
			"How many matching items each chest holds, above it", true));
	public final NumberSetting labelScale = add(new NumberSetting("Label scale",
			"Size of the count labels", 1.0, 0.5, 2.0, 0.1), labels::get);
	public final BooleanSetting slotHighlight = add(new BooleanSetting("Slot highlight",
			"Mark matching slots, and boxes holding a match, in container screens", true));
	public final BooleanSetting previewHighlight = add(new BooleanSetting("Preview highlight",
			"Mark matching items in shulker and bundle previews", true));
	public final ColorSetting highlightColor = add(new ColorSetting("Highlight color",
			"Fill behind matching slots", 0x7055FFFF), () -> slotHighlight.get() || previewHighlight.get());
	public final BooleanSetting modeOutlines = add(new BooleanSetting("Mode outlines",
			"In auto or click mode, outline nearby chests: recorded in one colour, not yet in another", true));
	public final NumberSetting outlineRange = add(new NumberSetting("Outline range",
			"How far out chests are outlined", 12, 4, 32, 1), modeOutlines::get);
	public final ColorSetting checkedColor = add(new ColorSetting("Checked color",
			"Outline of chests that are recorded", 0xFF55FF55), modeOutlines::get);
	public final ColorSetting uncheckedColor = add(new ColorSetting("Unchecked color",
			"Outline of chests that are not recorded yet", 0xFFFF5555), modeOutlines::get);

	private enum Stage {
		IDLE, LOOK, OPEN, WAIT
	}

	private record Hit(Entry entry, Tally tally, AABB box) {
	}

	/** A chest near the player for the mode outlines, both halves as one. */
	private record Marked(BlockPos pos, AABB box) {
	}

	private final Deque<BlockPos> queue = new ArrayDeque<>();
	private Stage stage = Stage.IDLE;
	private BlockPos target;
	private int stageTicks;
	private int cooldown;
	private int ticks;

	private boolean clickMode;
	private boolean autoMode;
	/**
	 * Chests auto mode has queued, and the tick it did: a locked one waits {@link #AUTO_RETRY}
	 * before its next try instead of being opened over and over.
	 */
	private final Map<BlockPos, Integer> autoTried = new HashMap<>();
	private String autoDimension;
	/** True while our own click is going through {@code useItemOn}, so click mode leaves it be. */
	private boolean selfClick;
	private BlockPos lastIntercept;
	private int lastInterceptTick;

	/** The chest the player last right-clicked themselves, for Update on open. */
	private BlockPos lastUsed;
	private List<Stored> lastSnapshot;

	private Query search;
	private List<Hit> hits = List.of();
	private final List<Shader.Storage> glowTargets = new ArrayList<>();
	private int hitsRevision = -1;
	private List<Marked> marked = List.of();
	/** Mode outlines then search results, rebuilt every tick; later ones win where they overlap. */
	private List<Shader.Storage> maskTargets = List.of();

	public ChestManager() {
		super("ChestManager", "Searchable record of the chests in your base", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		abortScan();
		queue.clear();
		clickMode = false;
		autoMode = false;
		autoTried.clear();
		glowTargets.clear();
		ChestDatabase.flush();
	}

	@Override
	public void onTick() {
		ChestDatabase.sync();
		ChestDatabase.tick();
		ticks++;
		if (mc().player == null || mc().level == null) {
			abortScan();
			queue.clear();
			return;
		}
		if (autoMode && ticks % 10 == 0) {
			findUnrecorded();
		}
		if (cooldown > 0) {
			cooldown--;
		} else {
			tickScan();
		}
		if (stage == Stage.IDLE) {
			watchManualOpen();
		}
		refreshHits();
		drawModeOutlines();
		if (labels.get()) {
			for (Hit hit : hits) {
				Render3D.blockLabel(label(hit.tally()), hit.entry().pos(), glowColor.get() | 0xFF000000,
						labelScale.getFloat());
			}
		}
	}

	// ---- scanning ------------------------------------------------------------------

	private void tickScan() {
		LocalPlayer player = mc().player;
		if (stage == Stage.IDLE) {
			if (queue.isEmpty() || !free()) {
				return;
			}
			target = queue.pollFirst();
			String problem = problem(target);
			if (problem != null) {
				if (!autoTried.containsKey(target)) {
					ChatUtil.info("ChestManager: " + format(target) + " skipped, " + problem);
				}
				target = null;
				return;
			}
			stage = rotate.get() ? Stage.LOOK : Stage.OPEN;
			stageTicks = 0;
		}
		stageTicks++;
		switch (stage) {
			case LOOK -> {
				if (!free()) {
					requeue(); // the player opened something; try again once they are done
					return;
				}
				face();
				if (stageTicks >= LOOK_TICKS) {
					stage = Stage.OPEN;
				}
			}
			case OPEN -> {
				if (!free()) {
					requeue();
					return;
				}
				face();
				click();
				stage = Stage.WAIT;
				stageTicks = 0;
			}
			case WAIT -> {
				face();
				AbstractContainerMenu menu = player.containerMenu;
				// stateId stays 0 until the server's content packet stamps it: a menu read
				// before then is empty, and would record a full chest as bare.
				if (menu != player.inventoryMenu && menu.getStateId() != 0) {
					finishScan(menu);
				} else if (stageTicks > OPEN_TIMEOUT) {
					if (!autoTried.containsKey(target)) {
						ChatUtil.info("ChestManager: " + format(target) + " didn't open (blocked, or no permission?)");
					}
					endScan();
				}
			}
			default -> {
			}
		}
	}

	/** Nothing of the player's is in the way: no screen, no container, not eating, not sneaking. */
	private boolean free() {
		LocalPlayer player = mc().player;
		return mc().gui.screen() == null
				&& player.containerMenu == player.inventoryMenu
				&& !AutoEat.busy()
				// sneaking with something in hand turns the click into a placement
				&& !player.isShiftKeyDown()
				&& !UnluckyClient.INSTANCE.modules.get(Printer.class).suppressesScreens()
				&& !UnluckyClient.INSTANCE.modules.get(AutoBrew.class).suppressesScreens();
	}

	/** Why a chest can't be scanned right now, or null when it can. */
	private String problem(BlockPos pos) {
		Level level = mc().level;
		BlockState state = level.getBlockState(pos);
		if (!ChestDatabase.trackable(state)) {
			return "it is no longer a chest or barrel";
		}
		if (Vec3.atCenterOf(pos).distanceTo(mc().player.getEyePosition()) > range.get() + 0.5) {
			return "out of reach (walk closer and run .cm add again)";
		}
		if (state.getBlock() instanceof ChestBlock) {
			BlockPos other = ChestDatabase.partner(level, pos);
			if (ChestBlock.isChestBlockedAt(level, pos) || (other != null && ChestBlock.isChestBlockedAt(level, other))) {
				return "something on top stops the lid opening";
			}
		}
		return null;
	}

	private void face() {
		if (rotate.get()) {
			RotationManager.lookAt(Vec3.atCenterOf(target));
		}
	}

	private void click() {
		LocalPlayer player = mc().player;
		Vec3 centre = Vec3.atCenterOf(target);
		Vec3 eye = player.getEyePosition();
		Direction side = Direction.getApproximateNearest(eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
		Vec3 point = centre.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
		selfClick = true;
		try {
			InteractionResult result = mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(point, side, target, false));
			if (swing.get() && result.consumesAction()) {
				SwingUtil.interact(player, InteractionHand.MAIN_HAND);
			}
		} finally {
			selfClick = false;
		}
	}

	private void finishScan(AbstractContainerMenu menu) {
		Level level = mc().level;
		BlockPos other = ChestDatabase.partner(level, target);
		List<Stored> items = new ArrayList<>();
		int size = 0;
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				continue;
			}
			Stored stored = ChestDatabase.record(size, slot.getItem());
			if (stored != null) {
				items.add(stored);
			}
			size++;
		}
		ContainerUtil.closeMenu();
		int expected = other != null ? 54 : 27;
		if (size != expected) {
			// Not the menu we asked for: a server GUI, or the chest changed shape mid-open.
			if (!autoTried.containsKey(target)) {
				ChatUtil.info("ChestManager: " + format(target) + " opened as " + size + " slots, expected "
						+ expected + "; not recorded");
			}
			endScan();
			return;
		}
		save(target, other, items, size);
		if (autoTried.containsKey(target)) {
			endScan(); // auto mode reads quietly; the panel's count is the report
			return;
		}
		int stacks = items.size();
		int nested = 0;
		for (Stored stored : items) {
			nested += stored.inner() == null ? 0 : stored.inner().size();
		}
		ChatUtil.info("ChestManager: recorded " + (other != null ? "double chest" : "container") + " at "
				+ format(ChestDatabase.canonical(target, other)) + " (" + stacks + "/" + size + " slots"
				+ (nested > 0 ? ", " + nested + " stacks inside boxes" : "") + ")");
		endScan();
	}

	private void save(BlockPos pos, BlockPos other, List<Stored> items, int size) {
		BlockState state = mc().level.getBlockState(pos);
		ChestDatabase.put(new Entry(ChestDatabase.canonical(pos, other),
				other == null ? null : (ChestDatabase.canonical(pos, other).equals(pos) ? other : pos.immutable()),
				ChestDatabase.blockId(state), System.currentTimeMillis(), size, List.copyOf(items),
				ChestDatabase.facing(state)));
	}

	private void requeue() {
		if (target != null) {
			queue.addFirst(target);
		}
		stage = Stage.IDLE;
		target = null;
	}

	private void endScan() {
		stage = Stage.IDLE;
		target = null;
		cooldown = delay.getInt();
	}

	private void abortScan() {
		if (stage == Stage.WAIT && mc().player != null && mc().player.containerMenu != mc().player.inventoryMenu
				&& mc().gui.screen() == null) {
			ContainerUtil.closeMenu();
		}
		stage = Stage.IDLE;
		target = null;
	}

	/**
	 * Auto mode: queues the chests and barrels in reach that have no record yet. Only while the
	 * queue is idle, so a manual rescan goes first and nothing piles up while you walk.
	 */
	private void findUnrecorded() {
		String dimension = ChestDatabase.currentDimension();
		if (!java.util.Objects.equals(dimension, autoDimension)) {
			autoDimension = dimension;
			autoTried.clear();
		}
		if (!queue.isEmpty() || stage != Stage.IDLE || !free()) {
			return;
		}
		autoTried.values().removeIf(tried -> ticks - tried > AUTO_RETRY);
		Level level = mc().level;
		Vec3 eye = mc().player.getEyePosition();
		double reach = range.get() + 0.5;
		List<BlockPos> found = new ArrayList<>();
		for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(reach + 2)) {
			BlockPos pos = blockEntity.getBlockPos();
			if (Vec3.atCenterOf(pos).distanceTo(eye) > reach || ChestDatabase.at(pos) != null
					|| !ChestDatabase.trackable(blockEntity.getBlockState())) {
				continue;
			}
			BlockPos canonical = ChestDatabase.canonical(pos, ChestDatabase.partner(level, pos));
			if (autoTried.containsKey(canonical) || autoTried.containsKey(pos) || problem(pos) != null) {
				continue;
			}
			autoTried.put(canonical, ticks);
			autoTried.put(pos, ticks);
			found.add(pos);
		}
		found.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		for (BlockPos pos : found) {
			enqueue(pos);
		}
	}

	private void enqueue(BlockPos pos) {
		BlockPos canonical = ChestDatabase.canonical(pos, ChestDatabase.partner(mc().level, pos));
		if (!queue.contains(canonical) && !canonical.equals(target)) {
			queue.addLast(canonical);
		}
	}

	// ---- keeping records current ---------------------------------------------------

	/**
	 * Update on open: while a tracked chest the player opened by hand is on screen, re-read it
	 * every few ticks and record it when it changed; in auto mode an untracked one is recorded too,
	 * since its contents are right there. Only a few dozen slots compared, so it costs no frames. The click is the only honest source of
	 * which chest this is (see AutoBrew.onBlockUsed for why), and the slot count must still
	 * match the chest's shape, or this is some other window.
	 */
	private void watchManualOpen() {
		LocalPlayer player = mc().player;
		AbstractContainerMenu menu = player.containerMenu;
		if (!refresh.get() || lastUsed == null || menu == player.inventoryMenu || menu.getStateId() == 0
				|| !(mc().gui.screen() instanceof AbstractContainerScreen<?>) || ticks % 5 != 0) {
			if (menu == player.inventoryMenu) {
				lastSnapshot = null;
			}
			return;
		}
		Entry entry = ChestDatabase.at(lastUsed);
		if (entry == null && !(autoMode && ChestDatabase.trackable(mc().level.getBlockState(lastUsed)))) {
			return;
		}
		BlockPos pos = entry != null ? entry.pos() : lastUsed;
		List<Stored> items = new ArrayList<>();
		int size = 0;
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				continue;
			}
			Stored stored = ChestDatabase.record(size, slot.getItem());
			if (stored != null) {
				items.add(stored);
			}
			size++;
		}
		BlockPos other = ChestDatabase.partner(mc().level, pos);
		if (size != (other != null ? 54 : 27) || items.equals(lastSnapshot)) {
			return;
		}
		lastSnapshot = items;
		if (entry == null || !items.equals(entry.items()) || !java.util.Objects.equals(other, entry.other())) {
			save(pos, other, items, size);
		}
	}

	// ---- hooks -----------------------------------------------------------------------

	/**
	 * From {@code MultiPlayerGameModeMixin}, for every block the player right-clicks.
	 *
	 * @return true to swallow the click: click mode tags (or untags) the container instead of
	 *         opening it
	 */
	public boolean onUse(BlockHitResult hit) {
		if (selfClick || !isEnabled() || mc().level == null) {
			return false;
		}
		BlockPos pos = hit.getBlockPos().immutable();
		lastUsed = pos;
		lastSnapshot = null;
		if (!clickMode || !ChestDatabase.trackable(mc().level.getBlockState(pos))) {
			return false;
		}
		// A held right-click repeats every few ticks; one press is one toggle.
		if (pos.equals(lastIntercept) && ticks - lastInterceptTick < 10) {
			lastInterceptTick = ticks;
			return true;
		}
		lastIntercept = pos;
		lastInterceptTick = ticks;
		Entry existing = ChestDatabase.at(pos);
		if (existing != null) {
			ChestDatabase.remove(pos);
			ChatUtil.info("ChestManager: untagged " + format(existing.pos()));
		} else {
			enqueue(pos);
			ChatUtil.info("ChestManager: tagged " + format(pos)
					+ (ChestDatabase.partner(mc().level, pos) != null ? " (double chest)" : ""));
		}
		return true;
	}

	/** Whether {@code GuiMixin} should drop the window of a container opening right now. */
	public boolean suppressesScreens() {
		return isEnabled() && (stage == Stage.OPEN || stage == Stage.WAIT);
	}

	/** Whether a {@code setScreen(null)} right now is our own close. */
	public boolean suppressesClose() {
		return isEnabled() && ContainerUtil.isClosing();
	}

	/**
	 * What {@code StorageEspRenderer} draws into the Shader mask for us: the mode outlines, then
	 * the search results on top, tagged to be drawn {@link #glowExtra} wider.
	 */
	public List<Shader.Storage> glowTargets() {
		return isEnabled() ? maskTargets : List.of();
	}

	/** Fill colour for a container slot, or 0. */
	public static int slotColor(ItemStack stack) {
		ChestManager cm = UnluckyClient.INSTANCE.modules.get(ChestManager.class);
		return cm.slotHighlight.get() ? cm.highlight(stack) : 0;
	}

	/** Fill colour for an item in a shulker or bundle preview, or 0. */
	public static int previewColor(ItemStack stack) {
		ChestManager cm = UnluckyClient.INSTANCE.modules.get(ChestManager.class);
		return cm.previewHighlight.get() ? cm.highlight(stack) : 0;
	}

	private int highlight(ItemStack stack) {
		if (!isEnabled() || search == null || stack.isEmpty()) {
			return 0;
		}
		return search.matchesDeep(stack) ? highlightColor.get() : 0;
	}

	// ---- for the ChestManager screen -------------------------------------------------

	/** The search showing in the world, or null. */
	public Query search() {
		return search;
	}

	/** Shows a search in the world, or clears it on blank text, as {@code .cm search} does. */
	public void setSearch(String text) {
		setSearch(new Query(text));
	}

	/** Shows a ready-made query in the world, such as one exact item; null or empty clears it. */
	public void setSearch(Query query) {
		search = query == null || query.isEmpty() ? null : query;
		hitsRevision = -1;
		if (mc().level != null) {
			refreshHits();
		}
	}

	public boolean clickMode() {
		return clickMode;
	}

	public void toggleClickMode() {
		if (!isEnabled()) {
			setEnabled(true);
		}
		clickMode = !clickMode;
	}

	public boolean autoMode() {
		return autoMode;
	}

	public void toggleAutoMode() {
		if (!isEnabled()) {
			setEnabled(true);
		}
		autoMode = !autoMode;
		autoTried.clear();
	}

	/** Chests waiting for their turn, plus the one being read. */
	public int pending() {
		return queue.size() + (target != null ? 1 : 0);
	}

	/** Queues one tracked chest for a rescan, or says why it can't be. */
	public String rescan(Entry entry) {
		if (mc().player == null || mc().level == null) {
			return "not in a world";
		}
		Vec3 eye = mc().player.getEyePosition();
		BlockPos pos = entry.other() != null && Vec3.atCenterOf(entry.other()).distanceTo(eye)
				< Vec3.atCenterOf(entry.pos()).distanceTo(eye) ? entry.other() : entry.pos();
		String problem = problem(pos);
		if (problem != null) {
			return problem;
		}
		if (!isEnabled()) {
			setEnabled(true);
		}
		enqueue(pos);
		return "queued " + format(entry.pos());
	}

	// ---- search results --------------------------------------------------------------

	private void refreshHits() {
		if (search == null) {
			hits = List.of();
			glowTargets.clear();
			return;
		}
		if (hitsRevision == ChestDatabase.revision() && ticks % 20 != 0) {
			return;
		}
		hitsRevision = ChestDatabase.revision();
		List<Hit> found = new ArrayList<>();
		for (Entry entry : ChestDatabase.all()) {
			Tally tally = ChestDatabase.tally(entry, search);
			if (tally == null || gone(entry)) {
				continue;
			}
			found.add(new Hit(entry, tally, box(entry)));
		}
		hits = found;
		glowTargets.clear();
		int color = glowColor.get() | 0xFF000000;
		for (Hit hit : hits) {
			// an id from the position, so neighbouring results keep the line between them
			int id = (int) (hit.entry().pos().asLong() * 0x9E3779B97F4A7C15L >>> 58);
			glowTargets.add(new Shader.Storage(hit.box(), EspColors.wide(color, id), hit.entry().pos(),
					label(hit.tally())));
		}
	}

	/** Loaded, and no longer a container: broken since it was recorded. */
	private boolean gone(Entry entry) {
		Level level = mc().level;
		return level.isLoaded(entry.pos()) && !ChestDatabase.trackable(level.getBlockState(entry.pos()));
	}

	/**
	 * While auto or click mode is on: every chest and barrel nearby, outlined through the Shader
	 * mask by whether it has a record, with the search glow laid over them. They are looked up twice a second, from the loaded chunks' block entities rather than
	 * block by block, so the range costs next to nothing; whether each is recorded, every tick, so a
	 * chest turns green the moment it is read.
	 */
	private void drawModeOutlines() {
		if (!modeOutlines.get() || (!clickMode && !autoMode)) {
			marked = List.of();
		} else if (ticks % 10 == 0 || marked.isEmpty()) {
			Level level = mc().level;
			Set<BlockPos> seen = new HashSet<>();
			List<Marked> found = new ArrayList<>();
			for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(outlineRange.get())) {
				if (!ChestDatabase.trackable(blockEntity.getBlockState())) {
					continue;
				}
				BlockPos pos = blockEntity.getBlockPos();
				BlockPos other = ChestDatabase.partner(level, pos);
				BlockPos canonical = ChestDatabase.canonical(pos, other);
				if (seen.add(canonical)) {
					AABB box = halfBox(pos);
					found.add(new Marked(canonical, other == null ? box : box.minmax(halfBox(other))));
				}
			}
			marked = found;
		}
		List<Shader.Storage> targets = new ArrayList<>();
		int checked = EspColors.plain(checkedColor.get() | 0xFF000000);
		int unchecked = EspColors.plain(uncheckedColor.get() | 0xFF000000);
		for (Marked mark : marked) {
			targets.add(new Shader.Storage(mark.box(), ChestDatabase.at(mark.pos()) != null ? checked : unchecked,
					mark.pos(), null));
		}
		if (glow.get()) {
			targets.addAll(glowTargets);
		}
		maskTargets = targets;
	}

	/** Both halves of a double chest as one box, from their real shapes when loaded. */
	private AABB box(Entry entry) {
		AABB box = halfBox(entry.pos());
		return entry.other() == null ? box : box.minmax(halfBox(entry.other()));
	}

	private AABB halfBox(BlockPos pos) {
		Level level = mc().level;
		if (level.isLoaded(pos)) {
			VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
			if (!shape.isEmpty()) {
				return shape.bounds().move(pos);
			}
		}
		return new AABB(pos).deflate(0.03);
	}

	private static String label(Tally tally) {
		Item item = ChestDatabase.item(tally.top());
		String name = item == null ? tally.top() : item.getDefaultInstance().getHoverName().getString();
		return tally.total() + " " + name;
	}

	// ---- the command -----------------------------------------------------------------

	/** {@code .chestmanager} / {@code .cm}. */
	public void command(String[] args, Consumer<String> out) {
		if (mc().player == null || mc().level == null) {
			out.accept("not in a world");
			return;
		}
		ChestDatabase.sync();
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		String rest = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
		switch (action) {
			case "add" -> add(out);
			case "click" -> {
				ensureEnabled(out);
				clickMode = !clickMode;
				out.accept(clickMode
						? "click mode on: right-click chests and barrels to tag them (again to untag); .cm click to stop"
						: "click mode off");
			}
			case "remove" -> {
				BlockPos pos = aimed();
				Entry removed = pos == null ? null : ChestDatabase.remove(pos);
				out.accept(removed == null ? "not looking at a tracked chest" : "forgot " + format(removed.pos()));
			}
			case "auto" -> {
				ensureEnabled(out);
				toggleAutoMode();
				out.accept(autoMode
						? "auto mode on: every chest and barrel in reach without a record gets read; .cm auto to stop"
						: "auto mode off");
			}
			case "search", "find" -> search(rest, out);
			case "total" -> total(rest, out);
			case "list" -> list(out);
			case "rescan" -> rescan(out);
			case "clear" -> {
				if (!rest.equalsIgnoreCase("confirm")) {
					out.accept("this forgets all " + ChestDatabase.size()
							+ " tracked chests in this dimension; type .cm clear confirm");
					return;
				}
				out.accept("forgot " + ChestDatabase.clear() + " chests");
			}
			default -> out.accept(ChestDatabase.size() + " chests tracked here"
					+ (clickMode ? ", click mode on" : "")
					+ (autoMode ? ", auto mode on" : "")
					+ (queue.isEmpty() ? "" : ", " + queue.size() + " waiting to scan")
					+ (search == null ? "" : ", showing '" + search.raw() + "'")
					+ ". Use: add, click, auto, remove, search <item>, total <item>, list, rescan, clear");
		}
	}

	private void add(Consumer<String> out) {
		BlockPos pos = aimed();
		if (pos == null || !ChestDatabase.trackable(mc().level.getBlockState(pos))) {
			out.accept("look at a chest or barrel first");
			return;
		}
		ensureEnabled(out);
		boolean known = ChestDatabase.at(pos) != null;
		enqueue(pos);
		out.accept((known ? "rescanning " : "adding ") + format(pos)
				+ (ChestDatabase.partner(mc().level, pos) != null ? " (double chest)" : ""));
	}

	private void search(String text, Consumer<String> out) {
		Query query = new Query(text);
		if (query.isEmpty()) {
			search = null;
			refreshHits();
			out.accept("search cleared");
			return;
		}
		ensureEnabled(out);
		search = query;
		hitsRevision = -1;
		refreshHits();
		if (hits.isEmpty()) {
			out.accept("no tracked chest holds '" + text + "'");
			return;
		}
		Vec3 eye = mc().player.getEyePosition();
		List<Hit> sorted = new ArrayList<>(hits);
		sorted.sort(Comparator.comparingDouble(hit -> hit.box().getCenter().distanceToSqr(eye)));
		int total = sorted.stream().mapToInt(hit -> hit.tally().total()).sum();
		out.accept(total + " matching in " + sorted.size() + (sorted.size() == 1 ? " chest" : " chests")
				+ " (nearest first; .cm search to clear):");
		for (int i = 0; i < Math.min(8, sorted.size()); i++) {
			Hit hit = sorted.get(i);
			out.accept("  " + format(hit.entry().pos()) + "  " + label(hit.tally())
					+ (hit.tally().boxes() > 0 ? "  (" + hit.tally().nested() + " in " + hit.tally().boxes()
							+ (hit.tally().boxes() == 1 ? " box)" : " boxes)") : "")
					+ "  " + (int) Math.sqrt(hit.box().getCenter().distanceToSqr(eye)) + "m");
		}
		if (sorted.size() > 8) {
			out.accept("  ...and " + (sorted.size() - 8) + " more, all glowing");
		}
	}

	private void total(String text, Consumer<String> out) {
		Query query = new Query(text);
		if (query.isEmpty()) {
			out.accept("total of what? e.g. .cm total obsidian");
			return;
		}
		int loose = 0;
		int nested = 0;
		int boxes = 0;
		int chests = 0;
		for (Entry entry : ChestDatabase.all()) {
			Tally tally = ChestDatabase.tally(entry, query);
			if (tally != null) {
				loose += tally.loose();
				nested += tally.nested();
				boxes += tally.boxes();
				chests++;
			}
		}
		if (chests == 0) {
			out.accept("none in the tracked chests");
			return;
		}
		out.accept("'" + text + "': " + (loose + nested) + " total in " + chests + (chests == 1 ? " chest" : " chests")
				+ " (" + loose + " loose, " + nested + " inside " + boxes + (boxes == 1 ? " box)" : " boxes)")
				+ " = " + String.format(Locale.ROOT, "%.1f", (loose + nested) / 64.0) + " stacks");
	}

	private void list(Consumer<String> out) {
		if (ChestDatabase.size() == 0) {
			out.accept("no chests tracked in this dimension; look at one and .cm add");
			return;
		}
		Vec3 eye = mc().player.getEyePosition();
		List<Entry> sorted = new ArrayList<>(ChestDatabase.all());
		sorted.sort(Comparator.comparingDouble(entry -> Vec3.atCenterOf(entry.pos()).distanceToSqr(eye)));
		out.accept(sorted.size() + " tracked (nearest first):");
		long now = System.currentTimeMillis();
		for (int i = 0; i < Math.min(10, sorted.size()); i++) {
			Entry entry = sorted.get(i);
			out.accept("  " + format(entry.pos()) + "  " + (entry.isDouble() ? "double " : "") + entry.block()
					+ "  " + entry.usedSlots() + "/" + entry.size() + " slots, scanned " + ago(now - entry.scanned()));
		}
		if (sorted.size() > 10) {
			out.accept("  ...and " + (sorted.size() - 10) + " more");
		}
	}

	/** Queues every tracked chest within reach; the rest are named as too far. */
	public void rescan(Consumer<String> out) {
		ensureEnabled(out);
		Vec3 eye = mc().player.getEyePosition();
		int queued = 0;
		int far = 0;
		for (Entry entry : ChestDatabase.all()) {
			if (Vec3.atCenterOf(entry.pos()).distanceTo(eye) <= range.get() + 0.5
					|| (entry.other() != null && Vec3.atCenterOf(entry.other()).distanceTo(eye) <= range.get() + 0.5)) {
				enqueue(Vec3.atCenterOf(entry.pos()).distanceTo(eye) <= range.get() + 0.5 ? entry.pos() : entry.other());
				queued++;
			} else {
				far++;
			}
		}
		out.accept("rescanning " + queued + (queued == 1 ? " chest" : " chests") + " in reach"
				+ (far > 0 ? "; " + far + " too far, walk closer and rescan" : ""));
	}

	private void ensureEnabled(Consumer<String> out) {
		if (!isEnabled()) {
			setEnabled(true);
			out.accept("ChestManager enabled");
		}
	}

	private BlockPos aimed() {
		HitResult hit = mc().hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			return block.getBlockPos().immutable();
		}
		return null;
	}

	/** Completions for everything after {@code .cm }. */
	public static List<CommandManager.Completion> completions(String arguments, int argumentStart) {
		int space = arguments.indexOf(' ');
		if (space < 0) {
			List<CommandManager.Completion> result = new ArrayList<>();
			String[][] values = {
					{"add", "tag the chest or barrel you are looking at"},
					{"click", "toggle tagging by right-click"},
					{"auto", "toggle reading every unrecorded chest in reach"},
					{"remove", "forget the chest you are looking at"},
					{"search", "glow the chests holding an item (no item clears)"},
					{"total", "count an item across every tracked chest"},
					{"list", "tracked chests, nearest first"},
					{"rescan", "re-read every tracked chest in reach"},
					{"clear", "forget every chest in this dimension"}};
			for (String[] value : values) {
				result.add(new CommandManager.Completion(value[0], value[1], argumentStart));
			}
			return result;
		}
		String sub = arguments.substring(0, space).toLowerCase(Locale.ROOT);
		if (!sub.equals("search") && !sub.equals("find") && !sub.equals("total")) {
			return List.of();
		}
		int start = space + 1;
		while (start < arguments.length() && arguments.charAt(start) == ' ') {
			start++;
		}
		ChestDatabase.sync();
		List<CommandManager.Completion> result = new ArrayList<>();
		for (String id : ChestDatabase.knownIds()) {
			result.add(new CommandManager.Completion(id, "in your tracked chests", argumentStart + start));
		}
		return result;
	}

	private static String format(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	public static String ago(long millis) {
		long minutes = millis / 60_000;
		if (minutes < 1) {
			return "just now";
		}
		if (minutes < 60) {
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		return hours < 48 ? hours + "h ago" : hours / 24 + "d ago";
	}
}
