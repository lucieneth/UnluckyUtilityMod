package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.modules.player.AutoEat;

/**
 * Turns "I want a block at this position" into the click that puts one there.
 *
 * <p>Extracted verbatim from the Surround/Scaffold flow, which is the point: that flow is
 * proven, and five more modules were about to grow their own copy of it. The copies would not
 * have stayed identical. Each one is a solve, an aim, a lease, a hotbar swap, a use, a swing, a
 * swap back and a release, in that order, and every step has a failure that must unwind the
 * ones before it — the version that forgets to release on a failed equip leaves the inventory
 * held by a module that is no longer doing anything, and it looks like a bug in whatever asks
 * next.
 *
 * <p><b>One executor per module, not one shared.</b> The delay, the per-tick budget and the
 * planned/placed sets are the module's own; what is shared is the {@link InventoryActionCoordinator}
 * lease and the {@link RotationManager} claim underneath, which is where two placers actually
 * have to be arbitrated. Two modules each holding an executor still contend for exactly one
 * hotbar and one rotation, and the answer comes from the coordinators as it always did.
 *
 * <p>What this deliberately does <em>not</em> decide: which positions to place at, and whether
 * this is a good moment to. Both are the module's job — a ring, a bridge lane, a hole and a
 * trap preset have nothing in common but the click at the end.
 *
 * @see PlacementSolver for which click yields which block state
 */
public final class PlacementExecutor {
	/** How long a placed block stays lit, matching the value both original modules used. */
	private static final long FADE_MS = 700L;

	/** How the server is told where we are looking for a placement. */
	public enum Rotate {
		/** Send the click with whatever rotation the player already has. */
		OFF,
		/** Spoof the rotation on the wire only; the camera does not move. */
		SILENT,
		/** Turn the camera itself, at the configured speed. */
		VISIBLE
	}

	/** How the arm swing that follows a placement is produced. */
	public enum Swing {
		CLIENT,
		PACKET,
		NONE
	}

	/**
	 * Which blocks this module is willing to place, and in what order it prefers them.
	 *
	 * <p>Position-aware because the answer genuinely depends on where it is going: a block that
	 * cannot survive at the target, or whose collision shape is not a full cube, is the right
	 * material somewhere else and the wrong one here.
	 */
	public interface Material {
		boolean accepts(BlockItem item, BlockPos target);

		/**
		 * Preference rank, lower first; equal ranks keep inventory order. The default is "no
		 * opinion", which is exactly what Surround and Scaffold want — they take whatever is in
		 * the hand, then the hotbar, then the bag.
		 */
		default int rank(BlockItem item) {
			return 0;
		}
	}

	/**
	 * Everything the owning module decides, gathered once a tick.
	 *
	 * <p>Passed to {@link #beginTick} rather than to every {@code place} call because none of it
	 * can meaningfully change between two placements in the same tick, and threading it through
	 * each call invites exactly the kind of drift this class exists to prevent.
	 */
	public record Options(Material material, boolean autoSwitch, boolean swapBack, Rotate rotate,
			float rotationSpeed, double range, boolean airPlace, boolean throughWalls, Swing swing,
			int blocksPerTick, int delay, int randomDelay, boolean pauseOnEat) {
	}

	private final Object owner;
	private final int priority;

	private Options options;
	private int delayTicks;
	private int spent;

	private final List<BlockPos> planned = new ArrayList<>(8);
	private BlockHitResult plannedHit;
	private final Map<BlockPos, Long> placed = new LinkedHashMap<>();

	public PlacementExecutor(Object owner) {
		this(owner, InventoryActionCoordinator.PRIORITY_PLACEMENT);
	}

	public PlacementExecutor(Object owner, int priority) {
		this.owner = owner;
		this.priority = priority;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- the tick ----------------------------------------------------------

	/**
	 * Opens a tick: expires the delay, resets the budget and clears last tick's plan.
	 *
	 * @return whether placement may be attempted at all this tick — false for no world, a
	 *         screen, spectator, or a meal in progress under {@code pauseOnEat}
	 */
	public boolean beginTick(Options tickOptions) {
		this.options = tickOptions;
		spent = 0;
		planned.clear();
		plannedHit = null;
		if (delayTicks > 0) {
			delayTicks--;
		}
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null || player.isSpectator()) {
			release();
			return false;
		}
		if (tickOptions.pauseOnEat() && AutoEat.busy()) {
			// Hand the lease back rather than merely standing down: a meal takes thirty-two
			// ticks and holding the inventory for all of them blocks everything below us.
			release();
			return false;
		}
		return true;
	}

	/** Whether another placement is allowed right now — budget left, and the delay expired. */
	public boolean canAct() {
		return options != null && delayTicks <= 0 && spent < Math.max(1, options.blocksPerTick());
	}

	/**
	 * Charges one unit of the per-tick budget for something that was not a placement.
	 *
	 * <p>Surround's crystal break is the case: it is an action aimed at a ring square, taken
	 * instead of placing into it, and it has to cost the same as the placement it replaced or
	 * the budget stops meaning anything.
	 */
	public void spend() {
		spent++;
	}

	/** Records a position that will not be placed this tick, so it renders as planned. */
	public void plan(BlockPos target) {
		if (target != null) {
			planned.add(target.immutable());
		}
	}

	/**
	 * Solves, aims, equips and sends one ordinary block-use click at {@code target}.
	 *
	 * <p>Every early exit records the target as planned, so a caller can simply loop over its
	 * targets and let the render tell the player which ones did not happen and why they are
	 * still wanted.
	 *
	 * @return whether a click was actually sent
	 */
	public boolean place(BlockPos target) {
		if (options == null || target == null) {
			return false;
		}
		if (!canAct()) {
			plan(target);
			return false;
		}
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			plan(target);
			return false;
		}
		Choice choice = chooseBlock(target);
		if (choice == null) {
			plan(target);
			return false;
		}
		BlockState wanted = choice.item().getBlock().defaultBlockState();
		PlacementSolver.Solution solution = PlacementSolver.solve(target, wanted, choice.stack(),
				new PlacementSolver.Options(options.range(), options.airPlace(),
						options.throughWalls(), true, options.rotate() != Rotate.OFF));
		if (solution == null) {
			plan(target);
			return false;
		}
		plannedHit = solution.hit();
		if (!aim(solution)) {
			// Still turning. The click has to wait for the rotation to arrive, or the server
			// derives a different state from the one the solver predicted.
			plan(target);
			return false;
		}
		if (!InventoryActionCoordinator.acquire(owner, priority)
				|| !InventoryActionCoordinator.owns(owner)) {
			plan(target);
			return false;
		}

		int swappedMainSlot = equip(choice);
		if (swappedMainSlot == Integer.MIN_VALUE || !InventoryActionCoordinator.owns(owner)) {
			InventoryActionCoordinator.release(owner);
			plan(target);
			return false;
		}
		mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, solution.hit());
		swing(player);
		placed.put(target.immutable(), System.currentTimeMillis());
		spent++;
		delayTicks = Math.max(0, options.delay())
				+ (options.randomDelay() > 0 ? (int) (Math.random() * (options.randomDelay() + 1)) : 0);

		if (options.swapBack() && swappedMainSlot >= 0 && InventoryActionCoordinator.owns(owner)) {
			InventoryActionCoordinator.swapToHotbar(owner, player.inventoryMenu, swappedMainSlot,
					player.getInventory().getSelectedSlot());
		}
		if (!options.swapBack()) {
			InventoryActionCoordinator.keepHotbar(owner);
		}
		InventoryActionCoordinator.release(owner);
		return true;
	}

	private void swing(LocalPlayer player) {
		switch (options.swing()) {
			case CLIENT -> player.swing(InteractionHand.MAIN_HAND);
			case PACKET -> player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			case NONE -> { }
		}
	}

	// ---- aiming ------------------------------------------------------------

	/**
	 * Points at the solved click, and answers whether we are there yet.
	 *
	 * <p>Silent asks {@link RotationManager} to spoof it on the wire; visible turns the camera
	 * itself, one step of {@code rotationSpeed} per tick, and only reports success once the
	 * remaining error is under a degree. Sending the click before then would place the block
	 * from an angle the solver did not simulate.
	 */
	private boolean aim(PlacementSolver.Solution solution) {
		if (options.rotate() == Rotate.OFF) {
			return true;
		}
		LocalPlayer player = mc().player;
		Vec3 point = PlacementSolver.lookPoint(player.getEyePosition(), solution.yaw(),
				solution.pitch(), 4.0);
		if (options.rotate() == Rotate.SILENT) {
			return RotationManager.face(point, options.rotationSpeed(), RotationManager.PRIORITY_PLACEMENT);
		}
		float nextYaw = approach(player.getYRot(), solution.yaw(), options.rotationSpeed());
		float nextPitch = approach(player.getXRot(), solution.pitch(), options.rotationSpeed());
		if (!RotationManager.rotateIfAllowed(nextYaw, nextPitch, RotationManager.PRIORITY_PLACEMENT)) {
			return false;
		}
		player.setYRot(nextYaw);
		player.setXRot(Mth.clamp(nextPitch, -90.0f, 90.0f));
		return Math.abs(Mth.wrapDegrees(solution.yaw() - nextYaw)) < 1.0f
				&& Math.abs(solution.pitch() - nextPitch) < 1.0f;
	}

	private static float approach(float from, float to, float speed) {
		return from + Mth.clamp(Mth.wrapDegrees(to - from), -speed, speed);
	}

	// ---- material ----------------------------------------------------------

	/** A stack the module will place, and where it currently is. */
	private record Choice(ItemStack stack, BlockItem item, int inventorySlot) {
	}

	/**
	 * The stack to place with: the held one if it qualifies, then the hotbar, then the bag.
	 *
	 * <p>Held-first is not just an optimisation — it is what keeps a module from swapping the
	 * hand on every single placement when the thing already in it would have done.
	 *
	 * <p>When the material expresses a preference (a BlockList in priority order), the best
	 * rank wins outright and position only breaks ties, so a module told "obsidian, then ender
	 * chest" does not spend the obsidian last because it happened to sit further along.
	 */
	private Choice chooseBlock(BlockPos target) {
		Inventory inventory = mc().player.getInventory();
		int selected = inventory.getSelectedSlot();
		Choice best = choice(inventory.getItem(selected), selected, target);
		if (best != null && (options.material().rank(best.item()) == 0 || !options.autoSwitch())) {
			return best;
		}
		if (!options.autoSwitch()) {
			return best;
		}
		int bestRank = best == null ? Integer.MAX_VALUE : options.material().rank(best.item());
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (slot == selected) {
				continue;
			}
			Choice candidate = choice(inventory.getItem(slot), slot, target);
			if (candidate == null) {
				continue;
			}
			int rank = options.material().rank(candidate.item());
			if (rank < bestRank) {
				bestRank = rank;
				best = candidate;
			}
		}
		return best;
	}

	private Choice choice(ItemStack stack, int slot, BlockPos target) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)
				|| !options.material().accepts(item, target)) {
			return null;
		}
		return new Choice(stack, item, slot);
	}

	/**
	 * Gets the chosen stack into the hand.
	 *
	 * @return main-inventory menu slot to swap back, -1 for a hotbar selection, or MIN_VALUE
	 *         on failure
	 */
	private int equip(Choice choice) {
		Inventory inventory = mc().player.getInventory();
		int selected = inventory.getSelectedSlot();
		if (choice.inventorySlot() == selected) {
			return -1;
		}
		if (!options.autoSwitch()) {
			return Integer.MIN_VALUE;
		}
		if (choice.inventorySlot() < Inventory.SELECTION_SIZE) {
			return InventoryActionCoordinator.selectHotbar(owner, choice.inventorySlot())
					? -1 : Integer.MIN_VALUE;
		}
		return InventoryActionCoordinator.swapToHotbar(owner, mc().player.inventoryMenu,
				choice.inventorySlot(), selected) ? choice.inventorySlot() : Integer.MIN_VALUE;
	}

	// ---- rendering ---------------------------------------------------------

	/** Positions this tick wanted a block at and did not get one into. */
	public List<BlockPos> planned() {
		return planned;
	}

	/** The support click behind the last solved placement, for the face marker. */
	public BlockHitResult plannedHit() {
		return plannedHit;
	}

	/** Boxes every planned position, and optionally marks the face the click would land on. */
	public void renderPlanned(int color, boolean boxes, boolean supportFace) {
		int alpha = ColorUtil.alpha(color);
		if (boxes) {
			for (BlockPos pos : planned) {
				Render3D.blockBox(pos, color, 2.0f, ColorUtil.withAlpha(color, Math.max(24, alpha / 4)), true);
			}
		}
		if (supportFace && plannedHit != null) {
			Vec3 hit = plannedHit.getLocation();
			Direction face = plannedHit.getDirection();
			Render3D.line(hit.subtract(face.getStepX() * 0.16, face.getStepY() * 0.16, face.getStepZ() * 0.16),
					hit.add(face.getStepX() * 0.16, face.getStepY() * 0.16, face.getStepZ() * 0.16),
					color, 2.0f, true);
		}
	}

	/**
	 * Fades recently placed blocks out, and expires them.
	 *
	 * <p>Called every tick whether or not the fade is being drawn — the map has to be pruned
	 * regardless, or a long session accumulates every block the module ever placed.
	 */
	public void renderPlaced(int color, boolean enabled) {
		long now = System.currentTimeMillis();
		placed.entrySet().removeIf(entry -> now - entry.getValue() >= FADE_MS);
		if (!enabled) {
			return;
		}
		int base = ColorUtil.alpha(color);
		for (Map.Entry<BlockPos, Long> entry : placed.entrySet()) {
			double life = 1.0 - (double) (now - entry.getValue()) / FADE_MS;
			int alpha = (int) (base * Mth.clamp(life, 0.0, 1.0));
			Render3D.blockBox(entry.getKey(), ColorUtil.withAlpha(color, alpha), 1.5f,
					ColorUtil.withAlpha(color, Math.max(0, alpha / 4)), true);
		}
	}

	// ---- lifecycle ---------------------------------------------------------

	/** Drops the lease without clearing the render state — for a tick that decided not to act. */
	public void release() {
		InventoryActionCoordinator.release(owner);
	}

	/** Full stop: disable, panic, world change. */
	public void reset() {
		release();
		options = null;
		delayTicks = 0;
		spent = 0;
		planned.clear();
		plannedHit = null;
		placed.clear();
	}
}
