package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.PlacementExecutor;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Drops cobwebs on a target.
 *
 * <p>The opposite of AutoTrap in one respect that matters: a web has no collision shape, so the
 * server lets it be placed <em>inside</em> somebody, and that is the whole idea — it goes where
 * they are standing, not around them. There is deliberately no entity check here for the same
 * reason AutoTrap has one.
 */
public class AutoWeb extends Module {
	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"How far to look for a target", 5, 1, 10, 0.5));
	public final ModeSetting targetPriority = add(new ModeSetting("Target priority",
			"Which target is chosen first", "Closest", "Closest", "Lowest health", "Lowest armor"));
	public final BooleanSetting head = add(new BooleanSetting("Head",
			"Also web the block at their head", false));
	public final BooleanSetting predictMovement = add(new BooleanSetting("Predict movement",
			"Project the target forward before choosing positions", true));
	public final NumberSetting predictionTicks = add(new NumberSetting("Prediction ticks",
			"How far ahead to project", 1, 0, 5, 1), predictMovement::get);
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never web a friend", true));
	public final BooleanSetting fakePlayers = add(new BooleanSetting("Fake players",
			"Include client-side practice players, for testing", true));

	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Placement reach", 4.5, 1, 6, 0.5));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between placements", 2, 0, 20, 1));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"How the placement is aimed", "Silent", "Off", "Silent", "Visible"));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each placement", true));
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Allow a direct click when no support face exists", false));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned",
			"Outline the positions still waiting on a web", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline colour for a planned position", 0xB0E0E0E0), renderPlanned::get);

	private final PlacementExecutor executor = new PlacementExecutor(this);

	public AutoWeb() {
		super("AutoWeb", "Places cobwebs in a target's feet and head", Category.COMBAT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		executor.reset();
	}

	@Override
	protected void onDisable() {
		executor.reset();
	}

	@Override
	protected void onPanic() {
		executor.reset();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (!executor.beginTick(placementOptions())) {
			return;
		}
		if (mc().gui.screen() != null || player.containerMenu != player.inventoryMenu) {
			executor.release();
			return;
		}
		LivingEntity target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(true, false, false)
						.fakePlayers(fakePlayers.get()).ignoreFriends(ignoreFriends.get())
						.range(targetRange.get())
						.priority(switch (targetPriority.get()) {
							case "Lowest health" -> TargetingUtil.Priority.LOWEST_HEALTH;
							case "Lowest armor" -> TargetingUtil.Priority.LOWEST_ARMOR;
							default -> TargetingUtil.Priority.CLOSEST;
						}));
		if (target == null) {
			executor.release();
			return;
		}

		Vec3 at = target.position();
		if (predictMovement.get()) {
			Vec3 velocity = target.getDeltaMovement();
			at = at.add(velocity.x * predictionTicks.getInt(), 0.0, velocity.z * predictionTicks.getInt());
		}
		BlockPos feet = BlockPos.containing(at);
		List<BlockPos> wanted = new ArrayList<>(2);
		wanted.add(feet);
		if (head.get()) {
			wanted.add(feet.above());
		}

		double reachSqr = placeRange.get() * placeRange.get();
		Vec3 eye = player.getEyePosition();
		wanted.removeIf(pos -> mc().level.getBlockState(pos).is(Blocks.COBWEB)
				|| !mc().level.getBlockState(pos).canBeReplaced()
				|| Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSqr);
		wanted.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		boolean acted = false;
		for (BlockPos pos : wanted) {
			acted |= executor.place(pos);
		}
		if (!acted) {
			executor.release();
		}
		if (renderPlanned.get()) {
			executor.renderPlanned(plannedColor.get(), true, false);
		}
	}

	private PlacementExecutor.Options placementOptions() {
		return new PlacementExecutor.Options(
				new PlacementExecutor.Material() {
					@Override
					public boolean accepts(BlockItem item, BlockPos pos) {
						return item.getBlock() == Blocks.COBWEB;
					}

					@Override
					public int rank(BlockItem item) {
						return 0;
					}
				},
				true, swapBack.get(),
				switch (rotation.get()) {
					case "Silent" -> PlacementExecutor.Rotate.SILENT;
					case "Visible" -> PlacementExecutor.Rotate.VISIBLE;
					default -> PlacementExecutor.Rotate.OFF;
				},
				180.0f, placeRange.get(), airPlace.get(), true, PlacementExecutor.Swing.CLIENT,
				2, delay.getInt(), 0, pauseOnEat.get());
	}
}
