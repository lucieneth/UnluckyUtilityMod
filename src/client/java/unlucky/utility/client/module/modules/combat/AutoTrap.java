package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.PlacementExecutor;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Builds a box around somebody else.
 *
 * <p>Structurally this is Surround pointed at a target instead of at your own feet, and it goes
 * through the same {@link PlacementExecutor} for exactly that reason — the difference between
 * the two modules is which positions they name, and nothing else. Sharing the executor is also
 * what makes the shared placement budget mean something: Surround, HoleFill, Burrow and this can
 * all be on, and between them they still get one hotbar, one rotation and one click per grant.
 *
 * <p><b>The presets are the module.</b> Head denies the two blocks above the shoulders, which is
 * where a crystal goes; Face walls the head ring; Anti-step takes away the block they would step
 * up onto; Full is the lot. Naming them is worth more than exposing eleven checkboxes, and
 * Custom is there for when it is not.
 *
 * <p>Positions are generated into a {@code LinkedHashSet}, which is what guarantees the Custom
 * preset cannot produce the same position twice — two selected regions that overlap are one
 * placement, not two, and the budget should not be charged for the difference.
 */
public class AutoTrap extends Module {
	public final ModeSetting preset = add(new ModeSetting("Preset",
			"Which positions to fill around the target", "Head",
			"Head", "Face", "Anti-step", "Full", "Custom"));

	public final BooleanSetting customFeetSides = add(new BooleanSetting("Feet sides",
			"The four blocks beside their feet", false), () -> preset.is("Custom"));
	public final BooleanSetting customWaistSides = add(new BooleanSetting("Waist sides",
			"The four blocks beside their waist", false), () -> preset.is("Custom"));
	public final BooleanSetting customFaceSides = add(new BooleanSetting("Face sides",
			"The four blocks beside their head", true), () -> preset.is("Custom"));
	public final BooleanSetting customTop = add(new BooleanSetting("Top",
			"The block above their head", true), () -> preset.is("Custom"));
	public final BooleanSetting customBottom = add(new BooleanSetting("Bottom",
			"The block under their feet", false), () -> preset.is("Custom"));

	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Placement preference order — right-click to pick",
			Set.of("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_chest")));

	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"How far to look for a target", 6, 1, 12, 1));
	public final ModeSetting targetPriority = add(new ModeSetting("Target priority",
			"Which target is chosen first", "Closest", "Closest", "Lowest health", "Lowest armor"));
	public final BooleanSetting predictMovement = add(new BooleanSetting("Predict movement",
			"Project the target forward before choosing positions", true));
	public final NumberSetting predictionTicks = add(new NumberSetting("Prediction ticks",
			"How far ahead to project", 1, 0, 5, 1), predictMovement::get);
	public final BooleanSetting onlyMoving = add(new BooleanSetting("Only moving targets",
			"Require the target to be moving", false));

	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Placement reach", 5, 1, 8, 0.5));
	public final NumberSetting wallsRange = add(new NumberSetting("Walls range",
			"Reach without line of sight", 3, 0, 8, 0.5));
	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"Shared placement budget", 2, 1, 8, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between placements", 1, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks on top", 0, 0, 10, 1));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"How the placement is aimed", "Silent", "Off", "Silent", "Visible"));
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Maximum degrees turned per tick", 180, 10, 180, 5), () -> !rotation.is("Off"));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select a listed block before placing", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each placement", true), autoSwitch::get);
	public final ModeSetting swing = add(new ModeSetting("Swing",
			"Hand swing after a placement", "Client", "Client", "Packet", "None"));
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Allow a direct target click when no support face exists", false));
	public final BooleanSetting pauseOnEat = addPauseOnEat();
	public final BooleanSetting disableOnComplete = add(new BooleanSetting("Disable when complete",
			"Switch off once every position is filled", false));
	public final BooleanSetting disableOnTargetLost = add(new BooleanSetting("Disable when target lost",
			"Switch off when there is no target, rather than placing blindly", true));

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned",
			"Outline the positions still waiting on a block", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline colour for a planned position", 0xB0FF5C5C), renderPlanned::get);
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed",
			"Fade recently placed positions out", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Colour of the placed-block fade", 0xA055FF88), renderPlaced::get);

	private static final Direction[] SIDES = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	private final PlacementExecutor executor = new PlacementExecutor(this);

	public AutoTrap() {
		super("AutoTrap", "Boxes a target in with a chosen trap preset", Category.COMBAT,
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
		executor.renderPlaced(placedColor.get(), renderPlaced.get());
		if (!executor.beginTick(placementOptions())) {
			return;
		}
		if (mc().gui.screen() != null || player.containerMenu != player.inventoryMenu) {
			executor.release();
			return;
		}

		LivingEntity target = selectTarget(player);
		if (target == null) {
			executor.release();
			if (disableOnTargetLost.get()) {
				setEnabled(false);
			}
			return;
		}

		List<BlockPos> positions = positionsFor(player, target);
		if (positions.isEmpty()) {
			executor.release();
			if (disableOnComplete.get()) {
				setEnabled(false);
			}
			return;
		}
		boolean acted = false;
		for (BlockPos pos : positions) {
			acted |= executor.place(pos);
		}
		if (!acted) {
			executor.release();
		}
		if (renderPlanned.get()) {
			executor.renderPlanned(plannedColor.get(), true, false);
		}
	}

	private LivingEntity selectTarget(LocalPlayer player) {
		TargetingUtil.Filter filter = new TargetingUtil.Filter()
				.range(targetRange.get())
				.priority(switch (targetPriority.get()) {
					case "Lowest health" -> TargetingUtil.Priority.LOWEST_HEALTH;
					case "Lowest armor" -> TargetingUtil.Priority.LOWEST_ARMOR;
					default -> TargetingUtil.Priority.CLOSEST;
				})
				.extra(candidate -> !onlyMoving.get()
						|| candidate.getDeltaMovement().horizontalDistanceSqr() >= 1.0e-4);
		return TargetingUtil.select(player, mc().level.entitiesForRendering(), filter);
	}

	/**
	 * The positions the chosen preset wants, still empty, in reach, and nearest first.
	 *
	 * <p>A {@code LinkedHashSet} rather than a list: the presets overlap by design — Full is
	 * every other preset at once — and a position named twice is still one block. Ordering by
	 * distance then spends the per-tick budget on the placements most likely to succeed, since
	 * the near ones are the ones still in reach if the target moves.
	 */
	private List<BlockPos> positionsFor(LocalPlayer player, LivingEntity target) {
		BlockPos feet = BlockPos.containing(projected(target));
		Set<BlockPos> wanted = new LinkedHashSet<>();

		boolean faceSides = preset.is("Head") || preset.is("Face") || preset.is("Full")
				|| (preset.is("Custom") && customFaceSides.get());
		boolean top = preset.is("Head") || preset.is("Full")
				|| (preset.is("Custom") && customTop.get());
		boolean waistSides = preset.is("Full") || (preset.is("Custom") && customWaistSides.get());
		boolean feetSides = preset.is("Anti-step") || preset.is("Full")
				|| (preset.is("Custom") && customFeetSides.get());
		boolean bottom = preset.is("Custom") && customBottom.get();

		BlockPos head = feet.above();
		if (faceSides) {
			for (Direction side : SIDES) {
				wanted.add(head.relative(side).immutable());
			}
		}
		if (top) {
			wanted.add(head.above().immutable());
		}
		if (waistSides) {
			for (Direction side : SIDES) {
				wanted.add(feet.relative(side).above().immutable());
			}
		}
		if (feetSides) {
			for (Direction side : SIDES) {
				wanted.add(feet.relative(side).immutable());
			}
		}
		if (bottom) {
			wanted.add(feet.below().immutable());
		}

		double reachSqr = placeRange.get() * placeRange.get();
		Vec3 eye = player.getEyePosition();
		List<BlockPos> out = new ArrayList<>(wanted.size());
		for (BlockPos pos : wanted) {
			if (!mc().level.getBlockState(pos).canBeReplaced()) {
				continue; // already filled
			}
			if (Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSqr) {
				continue;
			}
			if (!emptyOfEntities(pos)) {
				continue;
			}
			out.add(pos);
		}
		out.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		return out;
	}

	private Vec3 projected(LivingEntity target) {
		if (!predictMovement.get()) {
			return target.position();
		}
		Vec3 velocity = target.getDeltaMovement();
		int ticks = predictionTicks.getInt();
		return target.position().add(velocity.x * ticks, 0.0, velocity.z * ticks);
	}

	/**
	 * Whether a block may legally appear here.
	 *
	 * <p>The target themselves counts, which is not a mistake: a trap is built <em>around</em>
	 * somebody, and a position their hitbox occupies is one the server refuses. Skipping it is
	 * the difference between a trap and a stream of rejected placements.
	 */
	private boolean emptyOfEntities(BlockPos pos) {
		AABB box = new AABB(pos);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity.isAlive() && !entity.isSpectator() && entity.getBoundingBox().intersects(box)) {
				return false;
			}
		}
		return true;
	}

	private PlacementExecutor.Options placementOptions() {
		List<String> order = new ArrayList<>(blocks.get());
		return new PlacementExecutor.Options(
				new PlacementExecutor.Material() {
					@Override
					public boolean accepts(net.minecraft.world.item.BlockItem item, BlockPos pos) {
						if (!blocks.contains(item.getBlock())) {
							return false;
						}
						var state = item.getBlock().defaultBlockState();
						return !(item.getBlock() instanceof FallingBlock)
								&& state.isCollisionShapeFullBlock(mc().level, pos)
								&& state.canSurvive(mc().level, pos);
					}

					@Override
					public int rank(net.minecraft.world.item.BlockItem item) {
						int index = order.indexOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
								.getKey(item.getBlock()).toString());
						return index < 0 ? order.size() : index;
					}
				},
				autoSwitch.get(), swapBack.get(),
				switch (rotation.get()) {
					case "Silent" -> PlacementExecutor.Rotate.SILENT;
					case "Visible" -> PlacementExecutor.Rotate.VISIBLE;
					default -> PlacementExecutor.Rotate.OFF;
				},
				rotationSpeed.getFloat(), placeRange.get(), airPlace.get(), wallsRange.get() > 0,
				switch (swing.get()) {
					case "Packet" -> PlacementExecutor.Swing.PACKET;
					case "None" -> PlacementExecutor.Swing.NONE;
					default -> PlacementExecutor.Swing.CLIENT;
				},
				blocksPerTick.getInt(), delay.getInt(), randomDelay.getInt(), pauseOnEat.get());
	}
}
