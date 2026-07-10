package unlucky.utility.client.module.modules.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InteractUtil;

/** Harvests grown crops in reach and (optionally) replants, auto-switching to seeds. */
public class AutoFarm extends Module {
	public final BooleanSetting replant = add(new BooleanSetting("Replant", "Replant seeds after harvest", true));
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between actions", 2, 0, 20, 1));

	private int ticks;
	private BlockPos pendingReplant;
	private Item pendingSeed;

	public AutoFarm() {
		super("AutoFarm", "Harvests grown crops around you", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || --ticks > 0) {
			return;
		}
		ticks = (int) Math.round(delay.get());

		// replant a crop we harvested last action, switching to its seed
		if (pendingReplant != null) {
			BlockPos cropPos = pendingReplant;
			Item seed = pendingSeed;
			pendingReplant = null;
			pendingSeed = null;
			if (replant.get() && seed != null && mc().level.getBlockState(cropPos).isAir()) {
				int slot = InteractUtil.findHotbarItem(seed);
				if (slot >= 0) {
					InteractUtil.withHotbarSlot(slot, () -> InteractUtil.useOnBlock(cropPos.below(), Direction.UP));
					return;
				}
			}
		}

		double reach = mc().player.blockInteractionRange();
		int radius = (int) Math.ceil(reach);
		BlockPos origin = mc().player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))) {
			if (pos.distToCenterSqr(mc().player.getEyePosition()) > reach * reach) {
				continue;
			}
			BlockState state = mc().level.getBlockState(pos);
			boolean ripe = (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state))
					|| (state.getBlock() instanceof NetherWartBlock && state.getValue(BlockStateProperties.AGE_3) >= 3);
			if (ripe) {
				BlockPos harvest = pos.immutable();
				InteractUtil.breakBlock(harvest, Direction.UP);
				pendingReplant = harvest;
				pendingSeed = seedFor(state);
				return; // one crop per action tick, replant next tick
			}
		}
	}

	private static Item seedFor(BlockState state) {
		if (state.is(Blocks.WHEAT)) {
			return Items.WHEAT_SEEDS;
		}
		if (state.is(Blocks.CARROTS)) {
			return Items.CARROT;
		}
		if (state.is(Blocks.POTATOES)) {
			return Items.POTATO;
		}
		if (state.is(Blocks.BEETROOTS)) {
			return Items.BEETROOT_SEEDS;
		}
		if (state.is(Blocks.NETHER_WART)) {
			return Items.NETHER_WART;
		}
		return null;
	}
}
