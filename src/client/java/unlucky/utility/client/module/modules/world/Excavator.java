package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.clickgui.AreaPopup;
import unlucky.utility.client.gui.scene.AreaSource;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.EquipmentScorer;
import unlucky.utility.client.util.MiningActionCoordinator;
import unlucky.utility.client.util.MiningTracker;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.MovementActionCoordinator;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.SwingUtil;

/**
 * Digs out the box between two corners, one layer at a time from the top down.
 *
 * <p>Corners come from {@code .excavate pos1} and {@code pos2} — the block under the crosshair,
 * or your feet when you are not looking at one — and live until cleared, so the module can be
 * switched off for a meal and back on without losing the job.
 *
 * <p><b>Top down, one layer at a time.</b> A layer is only left once nothing breakable is left in
 * it, and the block under your own feet goes last. Digging that way means you are always standing
 * on the next layer, and a gravel or sand column that falls in lands somewhere still to be dug,
 * never somewhere already finished.
 *
 * <p>Breaking is vanilla start/continue through the shared coordinator, so AutoTool chooses the
 * tool and the server sees ordinary mining. With Walk on, the module also steps toward work it
 * cannot reach, but never off an edge it could not climb back from.
 */
public class Excavator extends Module {
	/** Widest layer accepted, per side; a scan per tick has to stay cheap. */
	private static final int MAX_SIDE = 128;

	public final NumberSetting range = add(new NumberSetting("Range",
			"Mining reach", 4.5, 2, 6, 0.5));
	public final BooleanSetting walk = add(new BooleanSetting("Walk",
			"Step toward blocks that are out of reach", true));
	public final NumberSetting speed = add(new NumberSetting("Walk speed",
			"Blocks per tick moved toward the work", 0.2, 0.05, 0.28, 0.01), walk::get);
	public final BooleanSetting avoidLiquids = add(new BooleanSetting("Avoid liquids",
			"Leave blocks that hold back water or lava", true));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Face each block before mining it", true));
	public final NumberSetting minDurability = add(new NumberSetting("Min tool durability",
			"Stop when the held tool drops to this percentage", 5, 0, 50, 1));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting renderArea = add(new BooleanSetting("Render area",
			"Outline the selection and the layer being dug", true));
	public final ColorSetting areaColor = add(new ColorSetting("Area color",
			"Outline colour of the selection", 0xB0FFAA33), renderArea::get);
	public final ColorSetting targetColor = add(new ColorSetting("Target color",
			"Colour of the block being mined", 0xA0FF5555), renderArea::get);
	public final ActionSetting preview = add(new ActionSetting("Preview area",
			"Show the box in 3D and drag its corners into place", this::openPreview));

	private static BlockPos pos1;
	private static BlockPos pos2;

	/** Y of the layer being dug; counts down from the top of the box. */
	private int layer;
	private int skipped;
	/** The block being mined, for the preview. */
	private BlockPos mining;

	public Excavator() {
		super("Excavator", "Digs out the box between two corners", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	// ---- the command ------------------------------------------------------------

	/** {@code .excavate [pos1|pos2|clear]}; with no argument, says what is selected. */
	public static void command(String[] args, Consumer<String> out) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		switch (action) {
			case "pos1", "pos2" -> {
				BlockPos pos = aimedBlock();
				if (pos == null) {
					out.accept("not in a world");
					return;
				}
				if (action.equals("pos1")) {
					pos1 = pos;
				} else {
					pos2 = pos;
				}
				out.accept(action + " set to " + format(pos) + selectionSize());
			}
			case "clear" -> {
				pos1 = null;
				pos2 = null;
				out.accept("selection cleared");
			}
			default -> out.accept(pos1 == null && pos2 == null
					? "no selection; look at a corner and use 'excavate pos1', then 'excavate pos2'"
					: "pos1 " + (pos1 == null ? "unset" : format(pos1)) + ", pos2 "
							+ (pos2 == null ? "unset" : format(pos2)) + selectionSize());
		}
	}

	private static BlockPos aimedBlock() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			return block.getBlockPos().immutable();
		}
		return mc.player.blockPosition().immutable();
	}

	private static String selectionSize() {
		if (pos1 == null || pos2 == null) {
			return "";
		}
		return " (" + (Math.abs(pos1.getX() - pos2.getX()) + 1) + " x "
				+ (Math.abs(pos1.getY() - pos2.getY()) + 1) + " x "
				+ (Math.abs(pos1.getZ() - pos2.getZ()) + 1) + ")";
	}

	private static String format(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	// ---- the preview ------------------------------------------------------------

	/** Opens the 3D preview; with no selection yet, starts one in front of you to drag into shape. */
	private void openPreview() {
		LocalPlayer player = mc().player;
		if ((pos1 == null || pos2 == null) && player != null) {
			Direction facing = player.getDirection();
			pos1 = player.blockPosition().relative(facing).relative(facing.getCounterClockWise(), 2);
			pos2 = pos1.relative(facing, 4).relative(facing.getClockWise(), 4).above(2);
		}
		AreaPopup.open(new AreaSource() {
			@Override
			public String title() {
				return "Excavator" + selectionSize();
			}

			@Override
			public BlockPos[] bounds() {
				return pos1 == null || pos2 == null ? null : AreaSource.box(pos1, pos2);
			}

			@Override
			public BlockState wanted(BlockPos pos) {
				return Blocks.AIR.defaultBlockState();
			}

			@Override
			public BlockPos target() {
				return isEnabled() ? mining : null;
			}

			@Override
			public BlockPos[] corners() {
				return pos1 == null || pos2 == null ? null : new BlockPos[] {pos1, pos2};
			}

			@Override
			public void setCorners(BlockPos a, BlockPos b) {
				if (Math.abs(a.getX() - b.getX()) >= MAX_SIDE || Math.abs(a.getZ() - b.getZ()) >= MAX_SIDE) {
					return;
				}
				pos1 = a;
				pos2 = b;
				// keep the layer being dug inside the box it now is
				layer = Math.clamp(layer, Math.min(a.getY(), b.getY()), Math.max(a.getY(), b.getY()));
			}
		});
	}

	// ---- the module -------------------------------------------------------------

	@Override
	protected void onEnable() {
		if (pos1 == null || pos2 == null) {
			ChatUtil.info("§eExcavator: set both corners first with .excavate pos1 and .excavate pos2.");
			setEnabled(false);
			return;
		}
		if (Math.abs(pos1.getX() - pos2.getX()) >= MAX_SIDE || Math.abs(pos1.getZ() - pos2.getZ()) >= MAX_SIDE) {
			ChatUtil.info("§eExcavator: the selection is wider than " + MAX_SIDE + " blocks.");
			setEnabled(false);
			return;
		}
		layer = Math.max(pos1.getY(), pos2.getY());
		skipped = 0;
	}

	@Override
	protected void onDisable() {
		releaseAll();
		mining = null;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || pos1 == null || pos2 == null) {
			releaseAll();
			return;
		}
		if (renderArea.get()) {
			render();
		}
		if (mc().gui.screen() != null || AutoEat.pauses(pauseOnEat)) {
			releaseAll();
			return;
		}

		int bottom = Math.min(pos1.getY(), pos2.getY());
		List<BlockPos> work = layerWork(player);
		while (work.isEmpty() && layer > bottom) {
			layer--;
			work = layerWork(player);
		}
		if (work.isEmpty()) {
			finish();
			return;
		}

		Vec3 eye = player.getEyePosition();
		double reachSqr = range.get() * range.get();
		BlockPos underFeet = BlockPos.containing(player.getX(), player.getY() - 0.5, player.getZ());
		BlockPos target = work.stream()
				.filter(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye) <= reachSqr)
				// the block you stand on last, so you drop onto the next layer only once this
				// one is finished around you
				.min(Comparator.<BlockPos>comparingInt(pos -> pos.equals(underFeet) ? 1 : 0)
						.thenComparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)))
				.orElse(null);
		if (target == null) {
			MiningActionCoordinator.stop(this);
			MiningActionCoordinator.release(this);
			if (walk.get()) {
				walkToward(player, work);
			}
			return;
		}
		MovementActionCoordinator.release(this);
		mine(player, target);
	}

	/**
	 * Every block in the current layer that still wants breaking.
	 *
	 * <p>Unbreakable blocks and, with Avoid liquids, blocks touching a fluid are counted once as
	 * skipped and otherwise treated as done — waiting on them would stall the layer for good.
	 */
	private List<BlockPos> layerWork(LocalPlayer player) {
		List<BlockPos> out = new ArrayList<>();
		int skippedHere = 0;
		for (BlockPos pos : BlockPos.betweenClosed(
				Math.min(pos1.getX(), pos2.getX()), layer, Math.min(pos1.getZ(), pos2.getZ()),
				Math.max(pos1.getX(), pos2.getX()), layer, Math.max(pos1.getZ(), pos2.getZ()))) {
			BlockState state = mc().level.getBlockState(pos);
			if (state.canBeReplaced()) {
				continue;
			}
			if (unbreakable(player, pos, state) || (avoidLiquids.get() && touchesFluid(pos))) {
				skippedHere++;
				continue;
			}
			out.add(pos.immutable());
		}
		if (out.isEmpty()) {
			skipped += skippedHere;
		}
		return out;
	}

	private void mine(LocalPlayer player, BlockPos pos) {
		ItemStack held = player.getMainHandItem();
		if (held.isDamageableItem() && EquipmentScorer.durabilityPercent(held) <= minDurability.getInt()) {
			stop("your tool is down to " + minDurability.getInt() + "% durability");
			return;
		}
		if (!MiningActionCoordinator.acquire(this, MiningActionCoordinator.PRIORITY_AREA)) {
			return;
		}
		if (rotate.get()) {
			RotationManager.lookAt(Vec3.atCenterOf(pos));
			MiningTracker.setRotationRequested(true);
		}
		if (renderArea.get()) {
			int color = targetColor.get();
			Render3D.box(new AABB(pos).inflate(0.003), color, 1.5f, ColorUtil.withAlpha(color, 35), true);
		}
		mining = pos;
		if (MiningActionCoordinator.mine(this, pos, faceToward(player, pos))) {
			SwingUtil.attack(player, InteractionHand.MAIN_HAND);
		}
	}

	/**
	 * Moves horizontally toward the nearest remaining block, if the step is safe.
	 *
	 * <p>Safe means there is ground within a short drop on the far side of the step. The whole
	 * point of digging top down is that you are standing on the next layer; walking off the
	 * edge of the pit undoes that.
	 */
	private void walkToward(LocalPlayer player, List<BlockPos> work) {
		if (!player.onGround()) {
			return;
		}
		Vec3 feet = player.position();
		BlockPos nearest = work.stream()
				.min(Comparator.comparingDouble(pos -> Vec3.atBottomCenterOf(pos).distanceToSqr(feet)))
				.orElse(null);
		if (nearest == null) {
			return;
		}
		Vec3 offset = new Vec3(nearest.getX() + 0.5 - feet.x, 0.0, nearest.getZ() + 0.5 - feet.z);
		double distance = offset.length();
		if (distance < 1.0e-3) {
			return;
		}
		Vec3 step = offset.scale(Math.min(speed.get(), distance) / distance);
		Vec3 ahead = feet.add(step.normalize().scale(0.6));
		BlockPos ground = BlockPos.containing(ahead.x, feet.y - 0.5, ahead.z);
		if (!MoveUtil.solidSupport(ground) && !MoveUtil.solidSupport(ground.below())) {
			return; // a drop of more than one block: let the player decide
		}
		if (MoveUtil.hazardous(ground) || MoveUtil.hazardous(ground.above())) {
			return;
		}
		MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
				velocity -> new Vec3(step.x, velocity.y, step.z));
	}

	private void render() {
		int minX = Math.min(pos1.getX(), pos2.getX());
		int minY = Math.min(pos1.getY(), pos2.getY());
		int minZ = Math.min(pos1.getZ(), pos2.getZ());
		AABB box = new AABB(minX, minY, minZ, Math.max(pos1.getX(), pos2.getX()) + 1,
				Math.max(pos1.getY(), pos2.getY()) + 1, Math.max(pos1.getZ(), pos2.getZ()) + 1);
		int color = areaColor.get();
		Render3D.box(box.inflate(0.002), color, 1.5f, 0, false);
		if (isEnabled()) {
			Render3D.box(new AABB(box.minX, layer, box.minZ, box.maxX, layer + 1, box.maxZ).inflate(0.004),
					color, 1.0f, ColorUtil.withAlpha(color, 20), false);
		}
	}

	private void finish() {
		String detail = skipped > 0 ? " (" + skipped + " blocks left: unbreakable or holding back liquid)" : "";
		ChatUtil.info("§aExcavator finished" + detail + ".");
		UnluckyClient.INSTANCE.notifications.add("Excavator", "Excavation complete", ItemStack.EMPTY);
		setEnabled(false);
	}

	private void stop(String reason) {
		ChatUtil.info("§eExcavator stopped: " + reason + ".");
		UnluckyClient.INSTANCE.notifications.add("Excavator", "Stopped: " + reason, ItemStack.EMPTY);
		setEnabled(false);
	}

	private void releaseAll() {
		MiningActionCoordinator.stop(this);
		MiningActionCoordinator.release(this);
		MovementActionCoordinator.release(this);
	}

	// ---- helpers ----------------------------------------------------------------

	private boolean unbreakable(LocalPlayer player, BlockPos pos, BlockState state) {
		return state.getDestroySpeed(mc().level, pos) < 0.0f
				|| state.getDestroyProgress(player, mc().level, pos) <= 0.0f;
	}

	/** Whether breaking this block would let a fluid flow in — any side but the bottom. */
	private boolean touchesFluid(BlockPos pos) {
		for (Direction side : Direction.values()) {
			if (side == Direction.DOWN) {
				continue;
			}
			var fluid = mc().level.getFluidState(pos.relative(side));
			if (fluid.is(FluidTags.LAVA) || fluid.is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	private Direction faceToward(LocalPlayer player, BlockPos pos) {
		Vec3 diff = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(diff.x, diff.y, diff.z);
	}
}
