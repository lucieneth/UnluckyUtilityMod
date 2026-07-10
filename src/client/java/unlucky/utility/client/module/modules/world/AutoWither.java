package unlucky.utility.client.module.modules.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;

/**
 * Builds a wither in front of you in the correct T shape:
 * a soul-sand foot, three soul sand across the top, then three skulls above.
 * Needs soul sand and 3 wither skeleton skulls in your hotbar. Stand back.
 */
public class AutoWither extends Module {
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between placements", 3, 1, 20, 1));
	public final NumberSetting count = add(new NumberSetting("Count", "How many to build", 1, 1, 20, 1));
	public final BooleanSetting unlimited = add(new BooleanSetting("Unlimited", "Keep building until out of materials", false));

	private int step;
	private int ticks;
	private int built;
	private BlockPos foot;

	public AutoWither() {
		super("AutoWither", "Auto-builds a wither", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		step = 0;
		built = 0;
		foot = null;
	}

	private boolean hasMaterials() {
		return InteractUtil.findHotbarItem(Items.SOUL_SAND) >= 0
				&& InteractUtil.findHotbarItem(Items.WITHER_SKELETON_SKULL) >= 0;
	}

	/** All 7 build positions must be free and supported. */
	private boolean canBuildAt(BlockPos footPos) {
		BlockPos center = footPos.above();
		BlockPos[] targets = {footPos, center, center.east(), center.west(),
				center.above(), center.above().east(), center.above().west()};
		for (BlockPos pos : targets) {
			if (!mc().level.getBlockState(pos).canBeReplaced()) {
				return false;
			}
		}
		return mc().level.getBlockState(footPos.below()).isSolidRender();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || --ticks > 0) {
			return;
		}
		ticks = (int) Math.round(delay.get());

		if (foot == null) {
			if (!hasMaterials()) {
				ChatUtil.info("§cAutoWither: out of soul sand or skulls.");
				setEnabledSilently(false);
				return;
			}
			// foot block: 2 ahead at foot level; arms run east/west
			foot = BlockPos.containing(mc().player.getEyePosition()
					.add(mc().player.getLookAngle().scale(2.5))).atY(mc().player.getBlockY()).immutable();
			if (!canBuildAt(foot)) {
				ChatUtil.info("§cAutoWither: no room to build here — clear a 3x3x2 space in front.");
				setEnabledSilently(false);
				foot = null;
				return;
			}
		}

		BlockPos center = foot.above();       // top of the vertical column
		BlockPos armE = center.east();
		BlockPos armW = center.west();

		switch (step) {
			// soul sand: foot on ground, center on foot, then the two arms off the center's sides
			case 0 -> placeSoul(foot.below(), Direction.UP);      // foot
			case 1 -> placeSoul(foot, Direction.UP);              // center (on foot)
			case 2 -> placeSoul(center, Direction.EAST);          // east arm
			case 3 -> placeSoul(center, Direction.WEST);          // west arm
			// skulls on top of the three top soul sands (center + arms); last one spawns it
			case 4 -> placeSkull(center, Direction.UP);
			case 5 -> placeSkull(armE, Direction.UP);
			case 6 -> {
				placeSkull(armW, Direction.UP);
				built++;
				ChatUtil.info("§7AutoWither: wither #" + built + " placed — run!");
				// build another if allowed and possible, else stop
				if (unlimited.get() || built < count.getInt()) {
					step = -1; // becomes 0 after step++
					foot = null;
				} else {
					setEnabledSilently(false);
				}
			}
			default -> setEnabledSilently(false);
		}
		step++;
	}

	/** Places soul sand on the given face of an existing support block. */
	private void placeSoul(BlockPos support, Direction face) {
		int slot = InteractUtil.findHotbarItem(Items.SOUL_SAND);
		if (slot >= 0) {
			InteractUtil.withHotbarSlot(slot, () -> InteractUtil.useOnBlock(support, face));
		}
	}

	/** Places a wither skull on the given face of an existing support block. */
	private void placeSkull(BlockPos support, Direction face) {
		int slot = InteractUtil.findHotbarItem(Items.WITHER_SKELETON_SKULL);
		if (slot >= 0) {
			InteractUtil.withHotbarSlot(slot, () -> InteractUtil.useOnBlock(support, face));
		}
	}
}
