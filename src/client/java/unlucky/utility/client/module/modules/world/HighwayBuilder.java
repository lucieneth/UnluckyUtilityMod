package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.clickgui.AreaPopup;
import unlucky.utility.client.gui.scene.AreaSource;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.EquipmentScorer;
import unlucky.utility.client.util.MiningActionCoordinator;
import unlucky.utility.client.util.MiningTracker;
import unlucky.utility.client.util.MovementActionCoordinator;
import unlucky.utility.client.util.PlacementExecutor;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.SwingUtil;

/**
 * Digs and paves a straight highway along one of the eight compass directions.
 *
 * <p>The highway is a fixed line, decided once at enable: the start block and a direction
 * (snapped from your yaw, or picked). Everything after that is measured against the line, not
 * against where you happen to be standing, so a nudge from a mob or a lag-back never bends it.
 *
 * <p><b>One job at a time.</b> Mining, paving and walking never overlap: while anything in the
 * work window still needs breaking, nothing is placed — a placement switches the held item, and
 * that resets vanilla's break progress — and the walk forward waits until the next slice is
 * both clear and paved. Slow, but it never walks onto a floor that is not there yet.
 *
 * <p><b>Stopping means disabling.</b> Running out of pave blocks, out of pickaxe, into lava or
 * into bedrock all switch the module off with the reason, as AutoWalk does. A builder that
 * pauses and silently resumes is a builder that resumes somewhere you did not expect.
 *
 * <p>Tools are AutoTool's: breaking goes through the vanilla start/continue pair it hooks.
 * Placement shares the {@link PlacementExecutor} budget with Surround, SelfTrap and the rest.
 */
public class HighwayBuilder extends Module {
	/** Slices ahead of the current one that are worked on before walking on. */
	private static final int LOOKAHEAD = 3;
	/** Pave blocks left before the one-time warning toast. */
	private static final int LOW_BLOCKS = 16;

	public final ModeSetting direction = add(new ModeSetting("Direction",
			"Axis to build along; Auto snaps your yaw to the nearest of the eight", "Auto",
			"Auto", "North", "North-east", "East", "South-east", "South", "South-west", "West",
			"North-west"));
	public final NumberSetting width = add(new NumberSetting("Width",
			"Width of the paved floor and the tunnel above it", 3, 1, 7, 1));
	public final NumberSetting height = add(new NumberSetting("Height",
			"Clear height above the floor", 3, 2, 5, 1));
	public final BlockListSetting blocks = add(new BlockListSetting("Pave blocks",
			"Floor and rail blocks, in preference order — right-click to pick",
			Set.of("minecraft:obsidian")));
	public final BooleanSetting guardRails = add(new BooleanSetting("Guard rails",
			"One-high rail along both edges", false));
	public final BooleanSetting replaceFloor = add(new BooleanSetting("Replace floor",
			"Mine floor blocks that are not a pave block and pave over them", true));

	public final NumberSetting range = add(new NumberSetting("Range",
			"Mining and placement reach", 4.5, 2, 6, 0.5));
	public final NumberSetting speed = add(new NumberSetting("Walk speed",
			"Blocks per tick moved toward the next slice", 0.2, 0.05, 0.28, 0.01));
	public final NumberSetting minDurability = add(new NumberSetting("Min pickaxe durability",
			"Stop when no hotbar pickaxe is above this percentage", 5, 0, 50, 1));
	public final BooleanSetting stopOnLava = add(new BooleanSetting("Stop on lava",
			"Disable when lava reaches the tunnel", true));

	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"Shared placement budget", 2, 1, 8, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between placements", 0, 0, 20, 1));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"How placements are aimed", "Silent", "Off", "Silent", "Visible"));
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Maximum degrees turned per tick", 180, 10, 180, 5), () -> !rotation.is("Off"));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each placement", true));
	public final ModeSetting swing = add(new ModeSetting("Swing",
			"Hand swing after a placement", "Client", "Client", "Packet", "None"));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned",
			"Outline floor and rail positions still waiting on a block", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline colour for a planned position", 0xB05C9CFF), renderPlanned::get);
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed",
			"Fade recently placed positions out", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Colour of the placed-block fade", 0xA055FF88), renderPlaced::get);
	public final ActionSetting preview = add(new ActionSetting("Preview area",
			"Show the stretch around you in 3D: what is paved, what is left to dig and pave", this::openPreview));

	private final PlacementExecutor executor = new PlacementExecutor(this);

	/** The block the highway starts at, at foot level. */
	private BlockPos origin;
	/** Step along the axis; each component is -1, 0 or 1. */
	private int dx;
	private int dz;
	private boolean warnedLow;
	/** The block being mined, for the preview. */
	private BlockPos mining;

	public HighwayBuilder() {
		super("HighwayBuilder", "Digs and paves a straight highway", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		executor.reset();
		warnedLow = false;
		LocalPlayer player = mc().player;
		if (player == null) {
			origin = null;
			return;
		}
		origin = player.blockPosition();
		int octant = octant(player);
		dx = stepX(octant);
		dz = stepZ(octant);
		ChatUtil.info("HighwayBuilder: building " + NAMES[octant].toLowerCase() + " from "
				+ origin.getX() + ", " + origin.getY() + ", " + origin.getZ() + ".");
	}

	@Override
	protected void onDisable() {
		releaseAll();
		executor.reset();
		mining = null;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		executor.renderPlaced(placedColor.get(), renderPlaced.get());
		if (player == null || mc().level == null || origin == null) {
			releaseAll();
			return;
		}
		if (mc().gui.screen() != null || AutoEat.pauses(pauseOnEat)) {
			releaseAll();
			return;
		}
		if (Math.abs(lateral(player.position())) > half() + 2.0
				|| Math.abs(player.getY() - origin.getY()) > 1.5) {
			stop("you left the highway");
			return;
		}

		int current = (int) Math.floor(along(player.position()) + 0.5);
		List<BlockPos> toMine = new ArrayList<>();
		List<BlockPos> toPave = new ArrayList<>();
		String problem = survey(player, current, toMine, toPave);
		if (problem != null) {
			stop(problem);
			return;
		}

		// Work on the next slice that is out of reach still holds the walk: stepping onto a
		// slice is only ever done once it is finished.
		boolean nextUnfinished = toMine.stream().anyMatch(pos -> along(Vec3.atCenterOf(pos)) < current + 1.5)
				|| toPave.stream().anyMatch(pos -> along(Vec3.atCenterOf(pos)) < current + 1.5);
		Vec3 eye = player.getEyePosition();
		double reachSqr = range.get() * range.get();
		toMine.removeIf(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSqr);
		toPave.removeIf(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSqr);
		toMine.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		if (!toMine.isEmpty()) {
			executor.release();
			MovementActionCoordinator.release(this);
			mine(player, toMine.get(0));
			return;
		}
		MiningActionCoordinator.stop(this);
		MiningActionCoordinator.release(this);

		if (!toPave.isEmpty()) {
			pave(player, toPave);
			return;
		}
		executor.release();
		if (!nextUnfinished) {
			walk(player, current + 1);
		}
	}

	// ---- the work --------------------------------------------------------------

	/**
	 * Fills both lists from the work window and returns a reason to stop, or null.
	 *
	 * <p>Slices from just behind you to {@link #LOOKAHEAD} ahead: behind so a block that fell
	 * into a finished slice is noticed, ahead so the walk never outruns the work.
	 */
	private String survey(LocalPlayer player, int current, List<BlockPos> toMine, List<BlockPos> toPave) {
		AABB body = player.getBoundingBox().inflate(0.05, 0.0, 0.05);
		int floorY = origin.getY() - 1;
		for (int slice = current - 1; slice <= current + LOOKAHEAD; slice++) {
			for (int[] column : columns(slice, false)) {
				for (int y = origin.getY(); y < origin.getY() + height.getInt(); y++) {
					BlockPos pos = new BlockPos(column[0], y, column[1]);
					BlockState state = mc().level.getBlockState(pos);
					if (stopOnLava.get() && state.getFluidState().is(FluidTags.LAVA)) {
						return "there is lava in the tunnel";
					}
					if (!state.canBeReplaced()) {
						if (unbreakable(player, pos, state)) {
							return "there is an unbreakable block in the way";
						}
						toMine.add(pos);
					}
				}
				floorCell(player, body, new BlockPos(column[0], floorY, column[1]), slice > current, toMine, toPave);
			}
			if (guardRails.get()) {
				for (int[] column : columns(slice, true)) {
					floorCell(player, body, new BlockPos(column[0], floorY, column[1]), slice > current, toMine, toPave);
					floorCell(player, body, new BlockPos(column[0], origin.getY(), column[1]), true, toMine, toPave);
				}
			}
		}
		return null;
	}

	/** Sorts one floor or rail cell into "mine first", "pave" or "already right". */
	private void floorCell(LocalPlayer player, AABB body, BlockPos pos, boolean mayMine,
			List<BlockPos> toMine, List<BlockPos> toPave) {
		BlockState state = mc().level.getBlockState(pos);
		if (state.canBeReplaced()) {
			if (emptyOfEntities(pos)) {
				toPave.add(pos);
			}
			return;
		}
		// Never the block under your own feet: mining it is how a highway builder falls into
		// the nether ceiling it was meant to be walking over.
		boolean underfoot = body.intersects(new AABB(pos).expandTowards(0.0, 2.0, 0.0));
		if (replaceFloor.get() && mayMine && !underfoot && !blocks.contains(state.getBlock())
				&& !unbreakable(player, pos, state)) {
			toMine.add(pos);
		}
	}

	private void mine(LocalPlayer player, BlockPos pos) {
		if (!hasPickaxe(player.getInventory())) {
			stop("no pickaxe above " + minDurability.getInt() + "% durability in the hotbar");
			return;
		}
		if (!MiningActionCoordinator.acquire(this, MiningActionCoordinator.PRIORITY_AREA)) {
			return;
		}
		RotationManager.lookAt(Vec3.atCenterOf(pos));
		MiningTracker.setRotationRequested(true);
		mining = pos;
		if (MiningActionCoordinator.mine(this, pos, faceToward(player, pos))) {
			SwingUtil.attack(player, InteractionHand.MAIN_HAND);
		}
	}

	private void pave(LocalPlayer player, List<BlockPos> targets) {
		int left = paveBlocksLeft(player.getInventory());
		if (left == 0) {
			stop("out of pave blocks");
			return;
		}
		if (left < LOW_BLOCKS && !warnedLow) {
			warnedLow = true;
			UnluckyClient.INSTANCE.notifications.add("HighwayBuilder", left + " pave blocks left",
					icon(player.getInventory()));
		}
		if (!executor.beginTick(placementOptions())) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		targets.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		boolean acted = false;
		for (BlockPos pos : targets) {
			acted |= executor.place(pos);
		}
		if (!acted) {
			executor.release();
		}
		if (renderPlanned.get()) {
			executor.renderPlanned(plannedColor.get(), true, false);
		}
	}

	/**
	 * Steps toward the centre of {@code slice} on the axis line.
	 *
	 * <p>Aiming at the line rather than "forward" is what keeps the walk on the highway: any
	 * sideways drift is corrected by the same vector that moves you on.
	 */
	private void walk(LocalPlayer player, int slice) {
		if (!player.onGround()) {
			return;
		}
		Vec3 target = new Vec3(origin.getX() + 0.5 + dx * slice, player.getY(),
				origin.getZ() + 0.5 + dz * slice);
		Vec3 offset = target.subtract(player.position()).multiply(1.0, 0.0, 1.0);
		double distance = offset.length();
		if (distance < 1.0e-3) {
			return;
		}
		Vec3 step = offset.scale(Math.min(speed.get(), distance) / distance);
		MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
				velocity -> new Vec3(step.x, velocity.y, step.z));
	}

	private void stop(String reason) {
		ChatUtil.info("§eHighwayBuilder stopped: " + reason + ".");
		LocalPlayer player = mc().player;
		UnluckyClient.INSTANCE.notifications.add("HighwayBuilder", "Stopped: " + reason,
				player == null ? ItemStack.EMPTY : icon(player.getInventory()));
		setEnabled(false);
	}

	private void releaseAll() {
		MiningActionCoordinator.stop(this);
		MiningActionCoordinator.release(this);
		MovementActionCoordinator.release(this);
		executor.release();
	}

	// ---- geometry --------------------------------------------------------------

	private static final String[] NAMES = {
			"South", "South-west", "West", "North-west", "North", "North-east", "East", "South-east"
	};

	/** Minecraft yaw octant: 0 is south, counting clockwise seen from above. */
	private static int octantOf(String name) {
		for (int i = 0; i < NAMES.length; i++) {
			if (NAMES[i].equals(name)) {
				return i;
			}
		}
		return 0;
	}

	/** The Direction setting as a yaw octant; Auto snaps your yaw. */
	private int octant(LocalPlayer player) {
		return direction.is("Auto")
				? Math.floorMod(Math.round(player.getYRot() / 45.0f), 8)
				: octantOf(direction.get());
	}

	private static int stepX(int octant) {
		return (int) Math.round(-Math.sin(Math.toRadians(octant * 45.0)));
	}

	private static int stepZ(int octant) {
		return (int) Math.round(Math.cos(Math.toRadians(octant * 45.0)));
	}

	private double half() {
		return (width.getInt() - 1) / 2.0;
	}

	/** Position along the axis, in slices from the origin. */
	private double along(Vec3 pos) {
		double x = pos.x - (origin.getX() + 0.5);
		double z = pos.z - (origin.getZ() + 0.5);
		return (x * dx + z * dz) / (dx * dx + dz * dz);
	}

	/** Signed sideways distance from the axis line, in lateral steps. */
	private double lateral(Vec3 pos) {
		double x = pos.x - (origin.getX() + 0.5);
		double z = pos.z - (origin.getZ() + 0.5);
		return (x * -dz + z * dx) / (dx * dx + dz * dz);
	}

	/**
	 * The (x, z) columns of one slice: the floor's width, or with {@code rails} just the two
	 * columns outside it.
	 *
	 * <p>A diagonal slice also takes each column's neighbour one step along x. Without it the
	 * floor is a chain of corner-touching squares you cannot walk along without falling
	 * between them.
	 */
	private List<int[]> columns(int slice, boolean rails) {
		return columns(origin, dx, dz, slice, rails);
	}

	private List<int[]> columns(BlockPos origin, int dx, int dz, int slice, boolean rails) {
		int lo = -(width.getInt() - 1) / 2;
		int hi = width.getInt() / 2;
		int[] offsets = rails ? new int[]{lo - 1, hi + 1} : range(lo, hi);
		boolean diagonal = dx != 0 && dz != 0;
		int cx = origin.getX() + dx * slice;
		int cz = origin.getZ() + dz * slice;
		List<int[]> out = new ArrayList<>(offsets.length * 2);
		for (int k : offsets) {
			int x = cx - dz * k;
			int z = cz + dx * k;
			out.add(new int[]{x, z});
			if (diagonal) {
				out.add(new int[]{x + dx, z});
			}
		}
		return out;
	}

	private static int[] range(int lo, int hi) {
		int[] out = new int[hi - lo + 1];
		for (int i = 0; i < out.length; i++) {
			out[i] = lo + i;
		}
		return out;
	}

	// ---- the preview -----------------------------------------------------------

	/**
	 * Opens the 3D preview of the stretch around you: two slices back to twelve ahead. Before
	 * the module is on it shows where it would start, from your feet and the Direction setting.
	 */
	private void openPreview() {
		AreaPopup.open(new AreaSource() {
			private final Map<BlockPos, BlockState> plan = new HashMap<>();
			private BlockPos[] bounds;

			@Override
			public String title() {
				return origin == null ? "HighwayBuilder (where it would start)" : "HighwayBuilder";
			}

			@Override
			public void refresh() {
				plan.clear();
				bounds = null;
				LocalPlayer player = mc().player;
				if (player == null) {
					return;
				}
				BlockPos start = origin != null ? origin : player.blockPosition();
				int octant = octant(player);
				int ax = origin != null ? dx : stepX(octant);
				int az = origin != null ? dz : stepZ(octant);
				int current = origin != null ? (int) Math.floor(along(player.position()) + 0.5) : 0;
				BlockState air = Blocks.AIR.defaultBlockState();
				BlockState pave = paveState();
				for (int slice = current - 2; slice <= current + 12; slice++) {
					for (int[] column : columns(start, ax, az, slice, false)) {
						for (int y = start.getY(); y < start.getY() + height.getInt(); y++) {
							plan.put(new BlockPos(column[0], y, column[1]), air);
						}
						plan.put(new BlockPos(column[0], start.getY() - 1, column[1]), pave);
					}
					if (guardRails.get()) {
						for (int[] column : columns(start, ax, az, slice, true)) {
							plan.put(new BlockPos(column[0], start.getY() - 1, column[1]), pave);
							plan.put(new BlockPos(column[0], start.getY(), column[1]), pave);
						}
					}
				}
				for (BlockPos pos : plan.keySet()) {
					bounds = bounds == null ? new BlockPos[] {pos, pos} : AreaSource.box(
							AreaSource.box(bounds[0], pos)[0], AreaSource.box(bounds[1], pos)[1]);
				}
			}

			@Override
			public BlockPos[] bounds() {
				return bounds;
			}

			/** Any pave block counts as paved, not only the first in the list. */
			@Override
			public BlockState wanted(BlockPos pos) {
				BlockState wanted = plan.get(pos);
				if (wanted != null && !wanted.isAir() && mc().level != null) {
					BlockState world = mc().level.getBlockState(pos);
					if (blocks.contains(world.getBlock())) {
						return world;
					}
				}
				return wanted;
			}

			@Override
			public BlockPos target() {
				return isEnabled() ? mining : null;
			}
		});
	}

	/** The first pave block in the list, as what the preview shows still to place. */
	private BlockState paveState() {
		for (String id : blocks.get()) {
			net.minecraft.resources.Identifier parsed = net.minecraft.resources.Identifier.tryParse(id);
			if (parsed != null) {
				Block block = BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null);
				if (block != null && block != Blocks.AIR) {
					return block.defaultBlockState();
				}
			}
		}
		return Blocks.OBSIDIAN.defaultBlockState();
	}

	// ---- helpers ---------------------------------------------------------------

	private boolean unbreakable(LocalPlayer player, BlockPos pos, BlockState state) {
		return state.getDestroySpeed(mc().level, pos) < 0.0f
				|| state.getDestroyProgress(player, mc().level, pos) <= 0.0f;
	}

	private Direction faceToward(LocalPlayer player, BlockPos pos) {
		Vec3 diff = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(diff.x, diff.y, diff.z);
	}

	private boolean emptyOfEntities(BlockPos pos) {
		AABB box = new AABB(pos);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity.isAlive() && !entity.isSpectator() && entity.getBoundingBox().intersects(box)) {
				return false;
			}
		}
		return true;
	}

	private boolean hasPickaxe(Inventory inventory) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(ItemTags.PICKAXES)
					&& EquipmentScorer.durabilityPercent(stack) > minDurability.getInt()) {
				return true;
			}
		}
		return false;
	}

	private boolean isPaveBlock(ItemStack stack) {
		return stack.getItem() instanceof BlockItem item && blocks.contains(item.getBlock());
	}

	private int paveBlocksLeft(Inventory inventory) {
		int count = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isPaveBlock(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private ItemStack icon(Inventory inventory) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (isPaveBlock(inventory.getItem(slot))) {
				return inventory.getItem(slot);
			}
		}
		return ItemStack.EMPTY;
	}

	private PlacementExecutor.Options placementOptions() {
		List<String> order = new ArrayList<>(blocks.get());
		return new PlacementExecutor.Options(
				new PlacementExecutor.Material() {
					@Override
					public boolean accepts(BlockItem item, BlockPos pos) {
						if (!blocks.contains(item.getBlock())) {
							return false;
						}
						var state = item.getBlock().defaultBlockState();
						return !(item.getBlock() instanceof FallingBlock)
								&& state.isCollisionShapeFullBlock(mc().level, pos)
								&& state.canSurvive(mc().level, pos);
					}

					@Override
					public int rank(BlockItem item) {
						int index = order.indexOf(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
						return index < 0 ? order.size() : index;
					}
				},
				true, swapBack.get(),
				switch (rotation.get()) {
					case "Silent" -> PlacementExecutor.Rotate.SILENT;
					case "Visible" -> PlacementExecutor.Rotate.VISIBLE;
					default -> PlacementExecutor.Rotate.OFF;
				},
				rotationSpeed.getFloat(), range.get(), false, true,
				switch (swing.get()) {
					case "Packet" -> PlacementExecutor.Swing.PACKET;
					case "None" -> PlacementExecutor.Swing.NONE;
					default -> PlacementExecutor.Swing.CLIENT;
				},
				blocksPerTick.getInt(), delay.getInt(), 0, pauseOnEat.get());
	}
}
