package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.PlacementExecutor;

/**
 * Fills liquid source blocks in reach with whatever cheap block you are holding
 * — the lava-lake crossing and pool-draining module.
 *
 * <p>Source blocks only, deliberately. Flowing liquid refills from its source the
 * moment you cover it, so filling flow is a placement you make twice and a block
 * you lose; the source is the one placement that ends the problem.
 *
 * <p>Sorting is the setting worth understanding. Furthest-first fills the far
 * edge of a pool and walks the shoreline toward you, which is what you want when
 * crossing; closest-first plugs the block under your feet first, which is what
 * you want when something is already burning.
 *
 * <p>Reference: Meteor's LiquidFiller.
 */
public class LiquidFiller extends Module {
	public final ModeSetting placeIn = add(new ModeSetting("Place in",
			"Which liquids to fill", "Both", "Both", "Water", "Lava"));
	public final ModeSetting sortMode = add(new ModeSetting("Sort",
			"Which spaces to fill first", "Furthest", "Furthest", "Closest", "Top down", "Bottom up", "None"));
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Placement preference order — right-click to pick",
			Set.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:stone",
					"minecraft:netherrack", "minecraft:diorite", "minecraft:granite",
					"minecraft:andesite")));

	public final NumberSetting range = add(new NumberSetting("Range",
			"How far a source block can be and still be filled", 4.5, 1.0, 6.0, 0.1));
	public final NumberSetting wallsRange = add(new NumberSetting("Walls range",
			"Range for sources behind terrain (0 disables placing through walls)", 4.5, 0.0, 6.0, 0.1));
	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"How many blocks to place in one tick", 1, 1, 10, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between placements", 0, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks added to the delay", 0, 0, 10, 1));

	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"How the placement is aimed", "Silent", "Off", "Silent", "Visible"));
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Degrees per tick for visible rotation", 180, 10, 360, 10));
	public final ModeSetting swing = add(new ModeSetting("Swing",
			"How the arm swing is produced", "Client", "Client", "Packet", "None"));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Switch to the block automatically", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the previous slot afterwards", true));
	public final BooleanSetting pauseOnEat = add(new BooleanSetting("Pause on eat",
			"Hold off while you are eating", true));

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned",
			"Outline the source about to be filled", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Colour of the planned outline", 0x6040A0FF), renderPlanned::get);
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed",
			"Briefly light up what was just filled", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Colour of the placed flash", 0x6040FF80), renderPlaced::get);

	private final PlacementExecutor executor = new PlacementExecutor(this);
	private final List<BlockPos> targets = new ArrayList<>();

	public LiquidFiller() {
		super("LiquidFiller", "Fills liquid sources in reach", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		executor.release();
		targets.clear();
	}

	@Override
	protected void onPanic() {
		executor.reset();
	}

	@Override
	public void onTick() {
		executor.renderPlaced(placedColor.get(), renderPlaced.get());
		if (!executor.beginTick(placementOptions())) {
			return;
		}
		if (mc().gui.screen() != null || mc().player.containerMenu != mc().player.inventoryMenu) {
			executor.release();
			return;
		}

		collectTargets();
		if (targets.isEmpty()) {
			executor.release();
			return;
		}

		boolean acted = false;
		for (BlockPos target : targets) {
			acted |= executor.place(target);
		}
		if (!acted) {
			executor.release();
		}
		if (renderPlanned.get()) {
			executor.renderPlanned(plannedColor.get(), true, false);
		}
	}

	/** Every source block in reach, in the configured order. */
	private void collectTargets() {
		targets.clear();
		int radius = (int) Math.ceil(range.get());
		BlockPos origin = mc().player.blockPosition();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (isFillable(pos)) {
						targets.add(pos);
					}
				}
			}
		}
		sort(targets);
	}

	private boolean isFillable(BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		if (fluid.isEmpty() || !fluid.isSource()) {
			return false; // flowing liquid just refills from its source
		}
		boolean water = fluid.getType() == Fluids.WATER;
		boolean lava = fluid.getType() == Fluids.LAVA;
		if (!water && !lava) {
			return false;
		}
		return switch (placeIn.get()) {
			case "Water" -> water;
			case "Lava" -> lava;
			default -> true;
		};
	}

	private void sort(List<BlockPos> list) {
		switch (sortMode.get()) {
			case "Closest" -> list.sort(Comparator.comparingDouble(this::distanceSqr));
			case "Furthest" -> list.sort(Comparator.comparingDouble(this::distanceSqr).reversed());
			// reversed() erases the inferred element type, so it has to be named here
			case "Top down" -> list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
			case "Bottom up" -> list.sort(Comparator.comparingInt(BlockPos::getY));
			default -> {
				// None: whatever order the sweep produced
			}
		}
	}

	private double distanceSqr(BlockPos pos) {
		return mc().player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/**
	 * Air-place is on: the target <i>is</i> the liquid, so there is frequently no
	 * neighbouring face to click against in the middle of a pool.
	 */
	private PlacementExecutor.Options placementOptions() {
		List<String> order = new ArrayList<>(blocks.get());
		return new PlacementExecutor.Options(
				new PlacementExecutor.Material() {
					@Override
					public boolean accepts(BlockItem item, BlockPos target) {
						return blocks.contains(item.getBlock())
								&& item.getBlock().defaultBlockState().canSurvive(mc().level, target);
					}

					@Override
					public int rank(BlockItem item) {
						int index = order.indexOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
								.getKey(item.getBlock()).toString());
						return index < 0 ? order.size() : index;
					}
				},
				autoSwitch.get(), swapBack.get(), rotateMode(), rotationSpeed.getFloat(),
				range.get(), true, wallsRange.get() > 0, swingMode(),
				blocksPerTick.getInt(), delay.getInt(), randomDelay.getInt(), pauseOnEat.get());
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
}
