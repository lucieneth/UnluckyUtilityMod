package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.MiningActionCoordinator;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Mines one block of a target's surround so a crystal can reach their feet.
 *
 * <p>The pick is the side block a crystal could take once it is gone: obsidian or bedrock
 * underneath, and room above. A side that does not open a crystal spot is still broken if it is
 * the only one in reach — an open side lets them be pushed or trapped differently — but a side
 * that does always wins.
 *
 * <p>Mining goes through {@link MiningActionCoordinator#mine}, the same vanilla break path a
 * left-click uses, so the break timing, AutoTool and the server's progress check all apply. The
 * built-in tool switch is for players who do not run AutoTool, and it holds the inventory lease
 * for the whole break so nothing else moves the hand mid-dig.
 */
public class AutoCity extends Module {
	private static final Direction[] SIDES = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"Maximum distance to a target", 6, 1, 12, 0.5));
	public final NumberSetting mineRange = add(new NumberSetting("Mine range",
			"Maximum distance from the eye to the block", 4.5, 1, 6, 0.1));
	public final BooleanSetting onlyCrystalSpots = add(new BooleanSetting("Only crystal spots",
			"Only break a side that opens a crystal position", false));
	public final BooleanSetting switchTool = add(new BooleanSetting("Switch tool",
			"Hold the fastest hotbar tool while breaking", true));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Silently face the block while breaking", true));
	public final BooleanSetting disableWhenDone = add(new BooleanSetting("Disable when done",
			"Switch off once the block is gone", true));
	public final BooleanSetting disableNoTarget = add(new BooleanSetting("Disable without target",
			"Switch off when there is nothing to city", true));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never city a friend", true));
	public final BooleanSetting fakePlayers = add(new BooleanSetting("Fake players",
			"Include client-side practice players, for testing", true));
	public final BooleanSetting render = add(new BooleanSetting("Render",
			"Outline the block being broken", true));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Outline colour", 0xC0FF8030), render::get);

	private BlockPos mining;

	public AutoCity() {
		super("AutoCity", "Breaks a target's surround block to open a crystal spot",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		mining = null;
	}

	@Override
	protected void onDisable() {
		MiningActionCoordinator.release(this);
		InventoryActionCoordinator.release(this);
		mining = null;
	}

	@Override
	protected void onPanic() {
		mining = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			return;
		}
		if (mc().gui.screen() != null || player.isUsingItem()) {
			return;
		}

		if (mining != null && (mc().level.getBlockState(mining).isAir()
				|| player.getEyePosition().distanceTo(Vec3.atCenterOf(mining)) > mineRange.get())) {
			boolean broke = mc().level.getBlockState(mining).isAir();
			finish();
			if (broke && disableWhenDone.get()) {
				setEnabled(false);
				return;
			}
		}
		if (mining == null) {
			mining = pick(player);
			if (mining == null) {
				if (disableNoTarget.get()) {
					setEnabled(false);
				}
				return;
			}
		}

		if (!MiningActionCoordinator.acquire(this, MiningActionCoordinator.PRIORITY_VEIN)) {
			return;
		}
		Vec3 centre = Vec3.atCenterOf(mining);
		if (rotate.get()) {
			RotationManager.lookAt(centre);
		}
		if (switchTool.get()) {
			equipTool(player, mc().level.getBlockState(mining));
		}
		MiningActionCoordinator.mine(this, mining,
				Direction.getApproximateNearest(player.getEyePosition().subtract(centre)));
		if (render.get()) {
			int c = color.get();
			Render3D.blockBox(mining, c, 2.0f, ColorUtil.withAlpha(c, Math.max(24, color.alpha() / 4)), true);
		}
	}

	private void finish() {
		MiningActionCoordinator.stop(this);
		MiningActionCoordinator.release(this);
		InventoryActionCoordinator.release(this);
		mining = null;
	}

	/** The best side block of the nearest target's feet, or null. */
	private BlockPos pick(LocalPlayer player) {
		LivingEntity target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(true, false, false)
						.fakePlayers(fakePlayers.get()).ignoreFriends(ignoreFriends.get())
						.range(targetRange.get()).priority(TargetingUtil.Priority.CLOSEST));
		if (target == null) {
			return null;
		}
		BlockPos feet = target.blockPosition();
		Vec3 eye = player.getEyePosition();
		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (Direction side : SIDES) {
			BlockPos pos = feet.relative(side);
			BlockState state = mc().level.getBlockState(pos);
			if (state.isAir() || state.canBeReplaced()
					|| state.getDestroySpeed(mc().level, pos) < 0.0f) {
				continue; // open already, or bedrock
			}
			double distance = eye.distanceTo(Vec3.atCenterOf(pos));
			if (distance > mineRange.get()) {
				continue;
			}
			boolean crystal = crystalSpot(pos);
			if (onlyCrystalSpots.get() && !crystal) {
				continue;
			}
			double score = distance + (crystal ? 0.0 : 100.0);
			if (score < bestScore) {
				bestScore = score;
				best = pos.immutable();
			}
		}
		return best;
	}

	/** Whether a crystal fits here once this block is gone. */
	private boolean crystalSpot(BlockPos pos) {
		BlockState below = mc().level.getBlockState(pos.below());
		return (below.is(Blocks.OBSIDIAN) || below.is(Blocks.BEDROCK))
				&& mc().level.getBlockState(pos.above()).isAir();
	}

	private void equipTool(LocalPlayer player, BlockState state) {
		Inventory inventory = player.getInventory();
		int best = inventory.getSelectedSlot();
		float bestSpeed = inventory.getItem(best).getDestroySpeed(state);
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			float speed = inventory.getItem(slot).getDestroySpeed(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = slot;
			}
		}
		if (best != inventory.getSelectedSlot()
				&& InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_TOOL)) {
			InventoryActionCoordinator.selectHotbar(this, best);
		}
	}
}
