package unlucky.utility.client.module.modules.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.util.InteractUtil;

/**
 * Places an ender chest in front of you, then mines it with a pickaxe (holding
 * the break, no silk touch) so it drops obsidian. Auto-switches between the
 * ender chest and the pickaxe. Repeats hands-free.
 */
public class ObsidianFarm extends Module {
	private BlockPos target;
	private int previousSlot = -1;
	private boolean mining;

	public ObsidianFarm() {
		super("ObsidianFarm", "Places and mines ender chests into obsidian", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		target = null;
		mining = false;
		previousSlot = -1;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().gameMode == null) {
			return;
		}

		// pick a placement spot: the block just in front of and below eye level
		if (target == null) {
			BlockPos front = BlockPos.containing(mc().player.getEyePosition()
					.add(mc().player.getLookAngle().scale(2.0))).atY(mc().player.getBlockY());
			target = front.immutable();
		}

		BlockState state = mc().level.getBlockState(target);

		if (state.is(Blocks.ENDER_CHEST)) {
			mineWithPickaxe(target);
		} else if (state.canBeReplaced()) {
			stopMining();
			int slot = InteractUtil.findHotbarItem(Items.ENDER_CHEST);
			if (slot >= 0 && mc().level.getBlockState(target.below()).isSolidRender()) {
				InteractUtil.withHotbarSlot(slot, () -> InteractUtil.useOnBlock(target.below(), Direction.UP));
			}
		} else {
			// obstructed — pick a fresh spot next tick
			stopMining();
			target = null;
		}
	}

	private void mineWithPickaxe(BlockPos pos) {
		unlucky.utility.client.util.RotationManager.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(pos));
		Inventory inventory = mc().player.getInventory();
		if (!mining) {
			int pick = findPickaxe();
			if (pick < 0) {
				return;
			}
			previousSlot = inventory.getSelectedSlot();
			inventory.setSelectedSlot(pick);
			mc().gameMode.startDestroyBlock(pos, Direction.UP);
			mining = true;
		}
		// keep the break going every tick until the chest is gone
		if (!mc().gameMode.continueDestroyBlock(pos, Direction.UP)) {
			stopMining();
			target = null; // chest broken (or progress lost), reset
		}
	}

	private void stopMining() {
		if (mining) {
			mc().gameMode.stopDestroyBlock();
			if (previousSlot >= 0) {
				mc().player.getInventory().setSelectedSlot(previousSlot);
			}
			mining = false;
			previousSlot = -1;
		}
	}

	private int findPickaxe() {
		Inventory inventory = mc().player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			if (inventory.getItem(slot).is(ItemTags.PICKAXES)) {
				return slot;
			}
		}
		return -1;
	}

	@Override
	protected void onDisable() {
		stopMining();
		target = null;
	}
}
