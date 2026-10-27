package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
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
import unlucky.utility.client.settings.PatternSetting;
import unlucky.utility.client.util.HoleUtil;
import unlucky.utility.client.util.PlacementExecutor;

/**
 * Covers your own head, so nothing lands a crystal or an anchor on top of you.
 *
 * <p>Surround guards the feet ring; this is the other half of a hole. Top takes the block above
 * your head — the position a crystal over a hole needs — Face walls the head ring as well, and
 * Full adds the feet ring for when there is no Surround running. Same {@link PlacementExecutor}
 * as AutoTrap and Surround, so the three share one placement budget rather than fighting over
 * the hotbar. Custom places whatever shape the 3D pattern editor draws instead.
 */
public class SelfTrap extends Module {
	public final ModeSetting preset = add(new ModeSetting("Preset",
			"Which positions to fill around you", "Top", "Top", "Face", "Full", "Custom"));
	public final PatternSetting pattern = add(new PatternSetting("Pattern",
			"The blocks to place around your feet — click to edit in 3D", "Face"), () -> preset.is("Custom"));
	public final BooleanSetting onlyInHole = add(new BooleanSetting("Only in hole",
			"Only build while you stand in a safe hole", true));
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Placement preference order — right-click to pick",
			Set.of("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_chest")));

	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Placement reach", 5, 1, 8, 0.5));
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
			"Allow a direct click when no support face exists", false));
	public final BooleanSetting pauseOnEat = addPauseOnEat();
	public final BooleanSetting disableOnComplete = add(new BooleanSetting("Disable when complete",
			"Switch off once every position is filled", false));

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned",
			"Outline the positions still waiting on a block", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline colour for a planned position", 0xB05C9CFF), renderPlanned::get);
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed",
			"Fade recently placed positions out", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Colour of the placed-block fade", 0xA055FF88), renderPlaced::get);

	private static final Direction[] SIDES = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	private final PlacementExecutor executor = new PlacementExecutor(this);

	public SelfTrap() {
		super("SelfTrap", "Covers your own head against crystals and anchors", Category.COMBAT,
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
		if (mc().gui.screen() != null || player.containerMenu != player.inventoryMenu
				|| (onlyInHole.get() && HoleUtil.playerHole(new HoleUtil.Options(1, 2, false)) == null)) {
			executor.release();
			return;
		}

		List<BlockPos> positions = positions(player);
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

	/** The preset's positions that are still open, in reach and clear, nearest first. */
	private List<BlockPos> positions(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		BlockPos head = feet.above();
		Set<BlockPos> wanted = new LinkedHashSet<>();
		if (preset.is("Custom")) {
			wanted.addAll(pattern.around(feet));
		} else {
			wanted.add(head.above().immutable());
		}
		if (preset.is("Face") || preset.is("Full")) {
			for (Direction side : SIDES) {
				wanted.add(head.relative(side).immutable());
			}
		}
		if (preset.is("Full")) {
			for (Direction side : SIDES) {
				wanted.add(feet.relative(side).immutable());
			}
		}

		double reachSqr = placeRange.get() * placeRange.get();
		Vec3 eye = player.getEyePosition();
		List<BlockPos> out = new ArrayList<>(wanted.size());
		for (BlockPos pos : wanted) {
			if (mc().level.getBlockState(pos).canBeReplaced()
					&& Vec3.atCenterOf(pos).distanceToSqr(eye) <= reachSqr && emptyOfEntities(pos)) {
				out.add(pos);
			}
		}
		out.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
		return out;
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
				autoSwitch.get(), swapBack.get(),
				switch (rotation.get()) {
					case "Silent" -> PlacementExecutor.Rotate.SILENT;
					case "Visible" -> PlacementExecutor.Rotate.VISIBLE;
					default -> PlacementExecutor.Rotate.OFF;
				},
				rotationSpeed.getFloat(), placeRange.get(), airPlace.get(), true,
				switch (swing.get()) {
					case "Packet" -> PlacementExecutor.Swing.PACKET;
					case "None" -> PlacementExecutor.Swing.NONE;
					default -> PlacementExecutor.Swing.CLIENT;
				},
				blocksPerTick.getInt(), delay.getInt(), randomDelay.getInt(), pauseOnEat.get());
	}
}
