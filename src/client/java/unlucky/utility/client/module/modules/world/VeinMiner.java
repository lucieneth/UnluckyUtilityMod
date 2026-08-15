package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.BlockGroups;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.MiningActionCoordinator;
import unlucky.utility.client.util.MiningTracker;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;

/**
 * Break one ore, get the whole vein.
 *
 * <p><b>Seeded by a break you made yourself.</b> The trigger is
 * {@code MultiPlayerGameMode.destroyBlock} — the moment a block actually comes apart, not the
 * moment somebody starts swinging — which is the only signal that means "the player committed
 * to this". Everything after it is ours, and the seed is deliberately not re-armed by our own
 * breaks: the vein is the bounded search from where you started, not a walk that keeps finding
 * new reasons to continue.
 *
 * <p><b>Tool selection is AutoTool's, not ours.</b> Mining goes through the ordinary
 * {@code startDestroyBlock}/{@code continueDestroyBlock} pair, which is exactly where AutoTool
 * already hooks — so a vein of deepslate gets the pickaxe without this module knowing what a
 * pickaxe is. That is the whole reason to mine the vanilla way rather than with packets.
 *
 * <p>One trap is worth naming because it costs an hour: vanilla's {@code continueAttack} calls
 * {@code stopDestroyBlock()} on every tick the attack key is not held, and module ticks run
 * after it. A module that mines by calling start/continue itself therefore has its progress
 * reset to zero every single tick while every call it makes returns success. Holding the
 * {@link MiningActionCoordinator} lease is what drops that vanilla pass — the coordinator is
 * also what stops this module and the Printer working the same block from two directions.
 */
public class VeinMiner extends Module {
	/** Faces only — the six blocks that share a side. */
	private static final Direction[] FACES = Direction.values();

	public final ModeSetting listMode = add(new ModeSetting("List mode",
			"Whether Blocks names what to mine or what to leave alone", "Whitelist",
			"Whitelist", "Blacklist"));
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Which blocks start and continue a vein — right-click to pick", BlockGroups.ores()));
	public final ModeSetting connectivity = add(new ModeSetting("Connectivity",
			"Faces follows blocks that share a side. Faces + corners also follows the ones "
					+ "touching only at an edge or a corner, which is how ore veins actually sit.",
			"Faces + corners", "Faces", "Faces + corners"));
	public final NumberSetting depth = add(new NumberSetting("Search depth",
			"How many blocks out from the first one to look", 3, 1, 16, 1));
	public final NumberSetting maxBlocks = add(new NumberSetting("Max blocks",
			"Hard cap on one vein", 32, 1, 256, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between blocks", 0, 0, 20, 1));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Face each block server-side before breaking it — a server rejects a break you are "
					+ "not looking at", true));
	public final BooleanSetting sameBlockOnly = add(new BooleanSetting("Same block only",
			"Follow only the exact block you started on", true));
	public final BooleanSetting includeVariants = add(new BooleanSetting("Include variants",
			"Also follow the deepslate counterpart of the block you started on, and vice versa",
			false), sameBlockOnly::get);
	public final BooleanSetting stopOutOfRange = add(new BooleanSetting("Stop if out of range",
			"Give up on a block you can no longer reach instead of swinging at nothing", true));
	public final BooleanSetting stopOnChange = add(new BooleanSetting("Stop if block changes",
			"Drop a block from the queue if it stops being what we queued", true));
	public final BooleanSetting renderQueue = add(new BooleanSetting("Render queue",
			"Box the blocks still to go", true));
	public final ColorSetting queueColor = add(new ColorSetting("Queue color",
			"Colour of the queued boxes", 0xFF5CC8FF), renderQueue::get);
	public final BooleanSetting renderCurrent = add(new BooleanSetting("Render current",
			"Box the block being broken right now", true));
	public final ColorSetting currentColor = add(new ColorSetting("Current color",
			"Colour of the block being broken", 0xFFFFB347), renderCurrent::get);
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	/**
	 * Still to break, in the order they will be broken. A {@code LinkedHashSet} because both
	 * halves matter: the order is the nearest-first sort, and the set is what stops one block
	 * being queued twice by two different neighbours.
	 */
	private final LinkedHashSet<BlockPos> queue = new LinkedHashSet<>();

	/** What each queued position was when it was queued, for the "block changed" check. */
	private final java.util.Map<BlockPos, Block> queuedAs = new java.util.HashMap<>();

	private BlockPos current;
	private int delayTicks;

	/**
	 * The world the queue belongs to.
	 *
	 * <p>A queue is a list of coordinates, and coordinates mean nothing across a dimension
	 * change — the same numbers resolve to whatever happens to be at them in the Nether. With
	 * "stop if block changes" on that self-heals, because the recorded block will not match;
	 * with it off the module would quietly mine a handful of arbitrary blocks at coordinates
	 * copied from another world.
	 */
	private net.minecraft.world.level.Level lastLevel;

	public VeinMiner() {
		super("VeinMiner", "Break one ore, get the whole vein", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	/** True while this module is driving a break. Kept for the HUD read-outs. */
	public boolean isMining() {
		return isEnabled() && current != null;
	}

	@Override
	protected void onDisable() {
		clear();
	}

	@Override
	protected void onPanic() {
		clear();
	}

	private void clear() {
		queue.clear();
		queuedAs.clear();
		current = null;
		delayTicks = 0;
		// Releasing closes the break on the wire, so this covers the stop as well as the lease.
		MiningActionCoordinator.release(this);
	}

	/**
	 * A block just came apart. If it was ours to follow and we are not already working a vein,
	 * this is the seed.
	 *
	 * <p>Called from {@code MultiPlayerGameModeMixin} at the head of {@code destroyBlock}, so
	 * the state is read before vanilla replaces it with air.
	 */
	public void onBlockDestroyed(BlockPos pos) {
		if (!isEnabled() || mc().level == null || current != null || !queue.isEmpty()) {
			return;
		}
		BlockState state = mc().level.getBlockState(pos);
		if (state.isAir() || !allowed(state.getBlock())) {
			return;
		}
		search(pos, state.getBlock());
	}

	/** Bounded breadth-first walk out from the seed, collecting everything that matches. */
	private void search(BlockPos seed, Block seedBlock) {
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> frontier = new ArrayDeque<>();
		Deque<Integer> depths = new ArrayDeque<>();
		seen.add(seed);
		frontier.add(seed);
		depths.add(0);
		List<BlockPos> found = new ArrayList<>();
		int cap = maxBlocks.getInt();
		int limit = depth.getInt();

		while (!frontier.isEmpty() && found.size() < cap) {
			BlockPos pos = frontier.poll();
			int distance = depths.poll();
			if (distance >= limit) {
				continue;
			}
			for (BlockPos next : neighbours(pos)) {
				if (!seen.add(next)) {
					continue;
				}
				BlockState state = mc().level.getBlockState(next);
				if (state.isAir() || !matches(state.getBlock(), seedBlock)) {
					continue;
				}
				found.add(next);
				frontier.add(next);
				depths.add(distance + 1);
				if (found.size() >= cap) {
					break;
				}
			}
		}

		// Nearest first: a vein mined outward from the hand keeps every block in reach for as
		// long as possible, and a break that goes out of range takes the rest of the queue
		// with it.
		Vec3 eye = mc().player.getEyePosition();
		found.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		for (BlockPos pos : found) {
			queue.add(pos);
			queuedAs.put(pos, mc().level.getBlockState(pos).getBlock());
		}
	}

	private Iterable<BlockPos> neighbours(BlockPos pos) {
		if (connectivity.is("Faces")) {
			List<BlockPos> list = new ArrayList<>(6);
			for (Direction face : FACES) {
				list.add(pos.relative(face));
			}
			return list;
		}
		List<BlockPos> list = new ArrayList<>(26);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dy != 0 || dz != 0) {
						list.add(pos.offset(dx, dy, dz));
					}
				}
			}
		}
		return list;
	}

	/** Whether the block list lets us touch this block at all. */
	private boolean allowed(Block block) {
		return listMode.is("Whitelist") == blocks.contains(block);
	}

	/** Whether a neighbour continues the vein that started on {@code seedBlock}. */
	private boolean matches(Block block, Block seedBlock) {
		if (!allowed(block)) {
			return false;
		}
		if (!sameBlockOnly.get()) {
			return true;
		}
		return block == seedBlock || (includeVariants.get() && isVariantOf(block, seedBlock));
	}

	/**
	 * The deepslate/stone pair, and only that.
	 *
	 * <p>A deliberately small rule for a specific annoyance: a diamond vein straddling the
	 * deepslate boundary is two blocks by id and one vein to anybody holding a pickaxe. It is
	 * done by name because that is the only thing the two share — there is no property, tag or
	 * class that relates {@code diamond_ore} to {@code deepslate_diamond_ore}.
	 */
	private static boolean isVariantOf(Block block, Block seed) {
		String a = BuiltInRegistries.BLOCK.getKey(block).getPath();
		String b = BuiltInRegistries.BLOCK.getKey(seed).getPath();
		return strip(a).equals(strip(b));
	}

	private static String strip(String path) {
		return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			clear();
			lastLevel = null;
			return;
		}
		if (mc().level != lastLevel) {
			lastLevel = mc().level;
			clear();
			return;
		}
		if (AutoEat.pauses(pauseOnEat) || mc().gui.screen() != null) {
			return; // hold the queue, do not throw it away
		}
		render();

		if (current == null) {
			if (delayTicks > 0) {
				delayTicks--;
				return;
			}
			current = nextTarget(player);
			if (current == null) {
				return;
			}
		}

		BlockState state = mc().level.getBlockState(current);
		if (state.isAir() || (stopOnChange.get() && queuedAs.get(current) != state.getBlock())) {
			finishCurrent();
			return;
		}
		if (stopOutOfRange.get() && !inRange(player, current)) {
			finishCurrent();
			return;
		}

		// Taken every tick, not once at the seed: the lease can be lost to the Printer or to the
		// player's own left-click between ticks, and finding out here is the whole contract.
		if (!MiningActionCoordinator.acquire(this, MiningActionCoordinator.PRIORITY_VEIN)) {
			return;
		}
		Direction face = faceToward(player, current);
		if (rotate.get()) {
			RotationManager.lookAt(Vec3.atCenterOf(current));
			MiningTracker.setRotationRequested(true);
		}
		if (MiningActionCoordinator.mine(this, current, face)) {
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	/** Drops the current target and starts the configured delay before the next one. */
	private void finishCurrent() {
		if (current != null) {
			queue.remove(current);
			queuedAs.remove(current);
			current = null;
		}
		delayTicks = delay.getInt();
		// The STOP that closes this target's START. The lease is kept — the next block in the
		// queue is the same job, and handing it back between blocks invites a hand-off mid-vein.
		MiningActionCoordinator.stop(this);
	}

	/** The next queued block that is still worth breaking, discarding the ones that are not. */
	private BlockPos nextTarget(LocalPlayer player) {
		var iterator = queue.iterator();
		while (iterator.hasNext()) {
			BlockPos pos = iterator.next();
			BlockState state = mc().level.getBlockState(pos);
			boolean gone = state.isAir()
					|| (stopOnChange.get() && queuedAs.get(pos) != state.getBlock())
					|| (stopOutOfRange.get() && !inRange(player, pos));
			if (gone) {
				iterator.remove();
				queuedAs.remove(pos);
				continue;
			}
			return pos;
		}
		return null;
	}

	private static boolean inRange(LocalPlayer player, BlockPos pos) {
		double reach = player.blockInteractionRange();
		return Vec3.atCenterOf(pos).distanceToSqr(player.getEyePosition()) <= reach * reach;
	}

	/** The face pointing back at the player, so the break is aimed at a side they can see. */
	private static Direction faceToward(LocalPlayer player, BlockPos pos) {
		Vec3 delta = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(delta.x, delta.y, delta.z);
	}

	private void render() {
		if (queue.isEmpty()) {
			return;
		}
		if (renderQueue.get()) {
			int outline = queueColor.get() | 0xFF000000;
			int fill = ColorUtil.withAlpha(queueColor.get(), 15);
			for (BlockPos pos : queue) {
				if (!pos.equals(current)) {
					Render3D.blockBox(pos, outline, 1.5f, fill, true);
				}
			}
		}
		if (renderCurrent.get() && current != null) {
			Render3D.blockBox(current, currentColor.get() | 0xFF000000, 2.5f,
					ColorUtil.withAlpha(currentColor.get(), 30), true);
		}
	}
}
