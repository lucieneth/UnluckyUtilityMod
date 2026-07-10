package unlucky.utility.client.module.modules.world;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.InteractUtil;

/**
 * Automatically opens doors (and optionally fence gates) you walk up to.
 * Inspired by Stardust's AutoDoors.
 */
public class AutoDoors extends Module {
	public final BooleanSetting fenceGates = add(new BooleanSetting("Fence gates", "Also open fence gates", true));
	public final BooleanSetting closeBehind = add(new BooleanSetting("Close behind", "Close doors we opened once you walk away", true));

	// per-door cooldown so we do not spam interactions
	private final Map<BlockPos, Long> lastInteract = new HashMap<>();
	// doors we opened, waiting to be closed behind the player
	private final Map<BlockPos, Long> openedByUs = new HashMap<>();

	public AutoDoors() {
		super("AutoDoors", "Opens doors for you", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}

		long now = System.currentTimeMillis();
		lastInteract.values().removeIf(time -> now - time > 5000);
		closeDoorsBehind(now);

		double reach = mc().player.blockInteractionRange();
		int radius = (int) Math.ceil(reach);
		BlockPos playerPos = mc().player.blockPosition();

		for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-radius, -1, -radius), playerPos.offset(radius, 2, radius))) {
			double distSq = pos.distToCenterSqr(mc().player.getX(), mc().player.getY() + 1, mc().player.getZ());
			if (distSq > reach * reach || distSq > 9.0) {
				continue; // only doors within ~3 blocks, feels natural
			}
			BlockState state = mc().level.getBlockState(pos);
			boolean isDoor = state.getBlock() instanceof DoorBlock && !state.is(Blocks.IRON_DOOR);
			boolean isGate = fenceGates.get() && state.getBlock() instanceof FenceGateBlock;
			if (!isDoor && !isGate) {
				continue;
			}
			// only handle the lower half so double doors are not toggled twice
			if (isDoor && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
				continue;
			}
			if (state.getValue(BlockStateProperties.OPEN)) {
				continue;
			}
			BlockPos key = pos.immutable();
			Long last = lastInteract.get(key);
			if (last != null && now - last < 1000) {
				continue;
			}
			lastInteract.put(key, now);
			InteractUtil.useOnBlock(key, Direction.UP);
			if (closeBehind.get()) {
				openedByUs.put(key, now);
			}
		}
	}

	private void closeDoorsBehind(long now) {
		if (!closeBehind.get()) {
			openedByUs.clear();
			return;
		}
		Iterator<Map.Entry<BlockPos, Long>> iterator = openedByUs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<BlockPos, Long> entry = iterator.next();
			BlockPos pos = entry.getKey();
			// give up on doors that are gone, far away for long, or already closed
			BlockState state = mc().level.getBlockState(pos);
			boolean stillDoor = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock;
			if (!stillDoor || !state.getValue(BlockStateProperties.OPEN) || now - entry.getValue() > 15000) {
				iterator.remove();
				continue;
			}
			double distSq = pos.distToCenterSqr(mc().player.getX(), mc().player.getY() + 1, mc().player.getZ());
			double reach = mc().player.blockInteractionRange();
			// walked away but still in reach: close it
			if (distSq > 3.5 * 3.5 && distSq <= reach * reach) {
				InteractUtil.useOnBlock(pos, Direction.UP);
				lastInteract.put(pos, now);
				iterator.remove();
			} else if (distSq > reach * reach) {
				// out of reach, too late to close
				iterator.remove();
			}
		}
	}

	@Override
	protected void onDisable() {
		lastInteract.clear();
		openedByUs.clear();
	}
}
