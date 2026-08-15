package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
import unlucky.utility.client.util.HoleUtil;
import unlucky.utility.client.util.PlacementExecutor;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Takes away the hole an opponent is about to stand in.
 *
 * <p><b>Which positions are holes is {@link HoleUtil}'s answer and which click fills one is
 * {@link PlacementExecutor}'s.</b> This module owns exactly one decision — <em>whose</em> hole is
 * worth filling — and that is the only thing in here that is not shared with HoleESP, Surround,
 * Burrow and AutoTrap. A hole the ESP is drawing is a hole this will fill, because both asked
 * the same question.
 *
 * <p><b>Smart mode is the default and it is the interesting one.</b> Filling every hole in range
 * is a lot of obsidian spent on ground nobody was heading for; filling the ones near a target
 * who is not already safe is the actual play. "Ignore safe targets" is on for the same reason —
 * an opponent already standing in a hole cannot be denied it, and the block would land on their
 * head instead.
 *
 * <p>Friends and the local player are never targets: that rule lives in {@link TargetingUtil}
 * and is not re-implemented here, which is what stops it drifting from every other combat module.
 */
public class HoleFill extends Module {
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Placement preference order — right-click to pick",
			Set.of("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_chest")));

	public final BooleanSetting single = add(new BooleanSetting("Single",
			"Fill one-block holes", true));
	public final BooleanSetting doubles = add(new BooleanSetting("Double",
			"Fill two-block holes", true));
	public final BooleanSetting quad = add(new BooleanSetting("Quad",
			"Fill 2x2 holes", false));

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Smart fills only holes a target could reach; All in range fills everything",
			"Smart", "Smart", "All in range"));
	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"How far to look for a target", 8, 1, 16, 1));
	public final ModeSetting targetPriority = add(new ModeSetting("Target priority",
			"Which target is chosen first", "Closest",
			"Closest", "Lowest health", "Lowest distance to hole"));
	public final NumberSetting fillRange = add(new NumberSetting("Fill range",
			"Placement reach", 5, 1, 8, 0.5));
	public final NumberSetting wallsRange = add(new NumberSetting("Walls range",
			"Reach without line of sight", 3, 0, 8, 0.5));
	public final NumberSetting targetToHoleRange = add(new NumberSetting("Target-to-hole range",
			"How close a target must be to a hole for Smart mode to fill it", 3, 0, 8, 0.5),
			() -> mode.is("Smart"));

	public final BooleanSetting predictMovement = add(new BooleanSetting("Predict movement",
			"Project the target forward before measuring", true));
	public final NumberSetting predictionTicks = add(new NumberSetting("Prediction ticks",
			"How far ahead to project", 2, 0, 10, 1), predictMovement::get);
	public final BooleanSetting onlyMoving = add(new BooleanSetting("Only moving targets",
			"Require the target to be moving", false));
	public final BooleanSetting ignoreSafe = add(new BooleanSetting("Ignore safe targets",
			"Skip a target already standing in a hole", true));

	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"Placement budget", 2, 1, 8, 1));
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
			"Allow a direct target click when no support face exists; many servers reject it",
			false));
	public final BooleanSetting pauseOnEat = addPauseOnEat();
	public final BooleanSetting allowSelfFill = add(new BooleanSetting("Allow self-fill",
			"Permit filling the hole you are standing in", false));
	public final BooleanSetting disableOnTargetLost = add(new BooleanSetting("Disable when target lost",
			"Switch off when there is no target left", false));

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned",
			"Outline the holes still waiting on a block", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline colour for a planned fill", 0xB0FF5C5C), renderPlanned::get);
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed",
			"Fade recently filled positions out", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Colour of the placed-block fade", 0xA055FF88), renderPlaced::get);

	private final PlacementExecutor executor = new PlacementExecutor(this);

	public HoleFill() {
		super("HoleFill", "Fills the holes an opponent could use", Category.COMBAT,
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

		List<BlockPos> holes = holesFor(player, target);
		boolean acted = false;
		for (BlockPos hole : holes) {
			acted |= executor.place(hole);
		}
		if (!acted) {
			executor.release();
		}
		if (renderPlanned.get()) {
			executor.renderPlanned(plannedColor.get(), true, false);
		}
	}

	/**
	 * This tick's placement policy.
	 *
	 * <p>A fill block has to be a wall the instant it lands, exactly as Surround's does: a
	 * gravity block falls straight back into the hole it was meant to close, and a non-full
	 * shape leaves the crystal space it was meant to deny.
	 *
	 * <p>The block list is a <em>preference order</em> here rather than a permission set, which
	 * is the difference between "obsidian, and an ender chest if I am out" and "whichever of the
	 * two the inventory scan happened to reach first".
	 */
	private PlacementExecutor.Options placementOptions() {
		List<String> order = new ArrayList<>(blocks.get());
		return new PlacementExecutor.Options(
				new PlacementExecutor.Material() {
					@Override
					public boolean accepts(net.minecraft.world.item.BlockItem item, BlockPos target) {
						if (!blocks.contains(item.getBlock())) {
							return false;
						}
						var state = item.getBlock().defaultBlockState();
						return !(item.getBlock() instanceof FallingBlock)
								&& state.isCollisionShapeFullBlock(mc().level, target)
								&& state.canSurvive(mc().level, target);
					}

					@Override
					public int rank(net.minecraft.world.item.BlockItem item) {
						int index = order.indexOf(
								net.minecraft.core.registries.BuiltInRegistries.BLOCK
										.getKey(item.getBlock()).toString());
						return index < 0 ? order.size() : index;
					}
				},
				autoSwitch.get(), swapBack.get(), rotateMode(), rotationSpeed.getFloat(),
				fillRange.get(), airPlace.get(), wallsRange.get() > 0,
				swingMode(), blocksPerTick.getInt(), delay.getInt(), randomDelay.getInt(),
				pauseOnEat.get());
	}

	private PlacementExecutor.Rotate rotateMode() {
		return switch (rotation.get()) {
			case "Silent" -> PlacementExecutor.Rotate.SILENT;
			case "Visible" -> PlacementExecutor.Rotate.VISIBLE;
			default -> PlacementExecutor.Rotate.OFF;
		};
	}

	private PlacementExecutor.Swing swingMode() {
		return switch (swing.get()) {
			case "Packet" -> PlacementExecutor.Swing.PACKET;
			case "None" -> PlacementExecutor.Swing.NONE;
			default -> PlacementExecutor.Swing.CLIENT;
		};
	}

	/** The opponent whose ground is worth taking away, or null. */
	private LivingEntity selectTarget(LocalPlayer player) {
		TargetingUtil.Filter filter = new TargetingUtil.Filter()
				.range(targetRange.get())
				.priority(switch (targetPriority.get()) {
					case "Lowest health" -> TargetingUtil.Priority.LOWEST_HEALTH;
					default -> TargetingUtil.Priority.CLOSEST;
				})
				.extra(candidate -> {
					if (onlyMoving.get() && candidate.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4) {
						return false;
					}
					// A target already in a hole cannot be denied it, and the block would land
					// on their head rather than under their feet.
					return !ignoreSafe.get() || !inSafeHole(candidate);
				});
		return TargetingUtil.select(player, mc().level.entitiesForRendering(), filter);
	}

	private boolean inSafeHole(LivingEntity entity) {
		HoleUtil.Hole hole = HoleUtil.classify(entity.blockPosition(), holeOptions());
		return hole != null && hole.safe();
	}

	private HoleUtil.Options holeOptions() {
		return new HoleUtil.Options(1, 2, false);
	}

	/**
	 * The holes worth filling, nearest to the player first.
	 *
	 * <p>Scanned around the <em>target</em> rather than around us: the point is the ground they
	 * are about to use. Smart mode then keeps only the ones within reach of where they will
	 * actually be, which is what the movement projection is for — a hole they have already run
	 * past is not worth an obsidian.
	 */
	private List<BlockPos> holesFor(LocalPlayer player, LivingEntity target) {
		Vec3 projected = projected(target);
		BlockPos centre = BlockPos.containing(projected);
		double reachSqr = fillRange.get() * fillRange.get();
		double toHoleSqr = targetToHoleRange.get() * targetToHoleRange.get();
		BlockPos own = player.blockPosition();

		List<BlockPos> out = new ArrayList<>();
		for (HoleUtil.Hole hole : HoleUtil.scan(centre, Math.max(2, (int) Math.ceil(targetToHoleRange.get())),
				2, holeOptions())) {
			for (BlockPos cell : hole.positions()) {
				if (!allowSelfFill.get() && cell.equals(own)) {
					continue;
				}
				if (!wantsShape(hole.shape())) {
					continue;
				}
				if (Vec3.atCenterOf(cell).distanceToSqr(player.getEyePosition()) > reachSqr) {
					continue;
				}
				if (mode.is("Smart") && Vec3.atCenterOf(cell).distanceToSqr(projected) > toHoleSqr) {
					continue;
				}
				if (!emptyOfEntities(cell)) {
					continue;
				}
				out.add(cell);
			}
		}
		Vec3 eye = player.getEyePosition();
		out.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		return out;
	}

	private boolean wantsShape(HoleUtil.Shape shape) {
		return switch (shape) {
			case SINGLE -> single.get();
			case DOUBLE -> doubles.get();
			case QUAD -> quad.get();
		};
	}

	/** Where the target will be, if the projection is on. */
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
	 * <p>Same rule as Surround's, and for the same reason: a position overlapping any living
	 * entity is a placement the server refuses, retried every tick for as long as they stand
	 * there. The target counts — filling the square they are already in is not a fill.
	 */
	private boolean emptyOfEntities(BlockPos pos) {
		AABB box = new AABB(pos);
		for (var entity : mc().level.entitiesForRendering()) {
			if (entity.isAlive() && !entity.isSpectator() && entity.getBoundingBox().intersects(box)) {
				return false;
			}
		}
		return true;
	}
}
