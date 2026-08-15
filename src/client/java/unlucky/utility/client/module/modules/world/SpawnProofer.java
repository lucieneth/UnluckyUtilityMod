package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.PlacementExecutor;
import unlucky.utility.client.util.SpawnUtil;

/**
 * Covers the ground mobs could spawn on, with torches or with anything else that
 * denies the space.
 *
 * <p>Which ground that is comes from {@link SpawnUtil}, the same classifier
 * LightOverlay draws from — so what you see marked is what this covers, and
 * "Light level" means the same number in both modules.
 *
 * <p>Light sources are placed one at a time and darkest-first, which is Meteor's
 * trick and a good one: a torch lights a radius, so placing the darkest spot
 * first often removes several other candidates before the next tick even looks.
 * Blocking blocks — slabs, buttons, carpet — have no such effect on their
 * neighbours and go out at the full per-tick budget.
 *
 * <p>Reference: Meteor's SpawnProofer.
 */
public class SpawnProofer extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Which spawnable ground to cover", "Both", "Always", "Potential", "Both"));
	public final NumberSetting lightLevel = add(new NumberSetting("Light level",
			"Block light at or below which a mob can spawn (pre-1.18 servers: 7)", 0, 0, 15, 1));
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Placement preference order — right-click to pick",
			Set.of("minecraft:torch", "minecraft:stone_button", "minecraft:stone_slab")));

	public final NumberSetting range = add(new NumberSetting("Range",
			"How far a spot can be and still be covered", 4.5, 1.0, 6.0, 0.1));
	public final NumberSetting wallsRange = add(new NumberSetting("Walls range",
			"Range for spots behind terrain (0 disables placing through walls)", 4.5, 0.0, 6.0, 0.1));
	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"How many blocks to place in one tick", 1, 1, 8, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between placements", 1, 0, 20, 1));
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
			"Outline the spot about to be covered", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Colour of the planned outline", 0x60FFC040), renderPlanned::get);
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed",
			"Briefly light up what was just covered", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Colour of the placed flash", 0x6040FF80), renderPlaced::get);

	private final PlacementExecutor executor = new PlacementExecutor(this);
	private final List<BlockPos> targets = new ArrayList<>();

	public SpawnProofer() {
		super("SpawnProofer", "Covers spawnable ground around you", Category.WORLD,
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

	/**
	 * The spawnable positions in reach, darkest first.
	 *
	 * <p>The volume is the placement range and nothing more — a couple of hundred
	 * positions — so it is swept whole every tick rather than budgeted like
	 * LightOverlay's much larger view. Reach is the budget.
	 */
	private void collectTargets() {
		targets.clear();
		int radius = (int) Math.ceil(range.get());
		BlockPos origin = mc().player.blockPosition();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (wants(SpawnUtil.spawnAt(mc().level, pos, lightLevel.getInt()))) {
						targets.add(pos);
					}
				}
			}
		}
		// Darkest first only matters for light sources, but it never hurts the
		// blocking case either: the darkest spot is the one most worth denying.
		targets.sort(Comparator.comparingInt(pos -> mc().level.getMaxLocalRawBrightness(pos)));
	}

	private boolean wants(SpawnUtil.Spawn spawn) {
		return switch (spawn) {
			case ALWAYS -> !mode.is("Potential");
			case POTENTIAL -> !mode.is("Always");
			case NONE -> false;
		};
	}

	private PlacementExecutor.Options placementOptions() {
		List<String> order = new ArrayList<>(blocks.get());
		boolean light = placingLight();
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
				range.get(), false, wallsRange.get() > 0, swingMode(),
				// One light source per tick: the radius it lights usually clears
				// several of the remaining candidates before the next pass.
				light ? 1 : blocksPerTick.getInt(),
				delay.getInt(), randomDelay.getInt(), pauseOnEat.get());
	}

	/** Whether the block we would reach for emits light, which changes the budget. */
	private boolean placingLight() {
		for (String id : blocks.get()) {
			Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
					.getOptional(net.minecraft.resources.Identifier.parse(id)).orElse(null);
			if (block != null && block.defaultBlockState().getLightEmission() > 0) {
				return true;
			}
		}
		return false;
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
